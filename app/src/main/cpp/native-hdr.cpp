// native-hdr.cpp
//
// JNI bridge to LibRaw. The only job of the native layer is to turn a Nikon
// NEF (delivered as a byte[] read from a SAF Uri) into demosaiced RGB pixels
// that Kotlin can wrap in a Bitmap. Everything downstream — alignment and
// exposure fusion — happens in Kotlin via the OpenCV Java bindings.
//
// A deliberate choice for exposure fusion: no_auto_bright = 1. Auto-brighten
// would normalise every bracket to roughly the same lightness, erasing the
// exposure differences that fusion relies on. We want the brackets to stay
// dark/normal/bright so the merge can pick the best-exposed pixels.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>

#include "libraw/libraw.h"

#define LOG_TAG "NativeHdr"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Build a com.hdrstacker.DecodedImage(width, height, shutter, int[] pixels).
jobject makeDecodedImage(JNIEnv *env, int width, int height, float shutter,
                         const std::vector<jint> &pixels) {
    jclass cls = env->FindClass("com/hdrstacker/DecodedImage");
    if (cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IIF[I)V");
    if (ctor == nullptr) return nullptr;

    jintArray arr = env->NewIntArray(static_cast<jsize>(pixels.size()));
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(pixels.size()), pixels.data());

    jobject obj = env->NewObject(cls, ctor, width, height, shutter, arr);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(cls);
    return obj;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hdrstacker_NativeHdr_libRawVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(LibRaw::version());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_hdrstacker_NativeHdr_decodeNef(JNIEnv *env, jobject /*thiz*/,
                                        jbyteArray nefBytes, jboolean halfSize) {
    jsize len = env->GetArrayLength(nefBytes);
    if (len <= 0) {
        LOGE("empty NEF buffer");
        return nullptr;
    }

    jbyte *raw = env->GetByteArrayElements(nefBytes, nullptr);
    if (raw == nullptr) return nullptr;

    LibRaw processor;

    // Processing parameters tuned for exposure fusion.
    auto &p = processor.imgdata.params;
    p.output_bps = 8;          // 8-bit per channel — fusion output is displayable anyway
    p.output_color = 1;        // sRGB
    p.use_camera_wb = 1;       // trust the camera's white balance
    p.no_auto_bright = 1;      // KEEP the real per-bracket brightness (see file header)
    p.user_qual = 3;           // AHD demosaic — good quality/speed trade-off
    p.gamm[0] = 1.0 / 2.4;     // sRGB gamma curve
    p.gamm[1] = 12.92;
    p.half_size = halfSize ? 1 : 0;

    jobject result = nullptr;
    do {
        int rc = processor.open_buffer(raw, static_cast<size_t>(len));
        if (rc != LIBRAW_SUCCESS) {
            LOGE("open_buffer failed: %s", libraw_strerror(rc));
            break;
        }
        rc = processor.unpack();
        if (rc != LIBRAW_SUCCESS) {
            LOGE("unpack failed: %s", libraw_strerror(rc));
            break;
        }
        rc = processor.dcraw_process();
        if (rc != LIBRAW_SUCCESS) {
            LOGE("dcraw_process failed: %s", libraw_strerror(rc));
            break;
        }

        int err = 0;
        libraw_processed_image_t *img = processor.dcraw_make_mem_image(&err);
        if (img == nullptr || err != LIBRAW_SUCCESS) {
            LOGE("make_mem_image failed: %s", libraw_strerror(err));
            break;
        }
        if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 8) {
            LOGE("unexpected image format: type=%d colors=%d bits=%d",
                 img->type, img->colors, img->bits);
            LibRaw::dcraw_clear_mem(img);
            break;
        }

        const int w = img->width;
        const int h = img->height;
        const unsigned char *rgb = img->data;

        // Pack interleaved RGB into ARGB_8888 (0xAARRGGBB), fully opaque.
        std::vector<jint> pixels(static_cast<size_t>(w) * h);
        for (size_t i = 0, n = pixels.size(); i < n; ++i) {
            const unsigned char r = rgb[i * 3 + 0];
            const unsigned char g = rgb[i * 3 + 1];
            const unsigned char b = rgb[i * 3 + 2];
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        LibRaw::dcraw_clear_mem(img);

        // Ground-truth shutter speed (seconds) straight from the NEF metadata,
        // used by the Debevec HDR path.
        const float shutter = processor.imgdata.other.shutter;

        LOGI("decoded NEF %dx%d shutter=%.5fs (half=%d)", w, h, shutter, halfSize);
        result = makeDecodedImage(env, w, h, shutter, pixels);
    } while (false);

    processor.recycle();
    env->ReleaseByteArrayElements(nefBytes, raw, JNI_ABORT);  // we didn't modify it
    return result;
}
