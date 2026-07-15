// native-stitch.cpp
//
// JNI bridge to cv::Stitcher for the panorama feature. The stitching module is
// absent from the Maven OpenCV artifact, so it is compiled from source into
// this library (see CMakeLists.txt) and linked against the prebuilt
// libopencv_java4.so for its cv:: dependencies.
//
// Input/output are OpenCV Java Mats, passed by their native handle. Both this
// library and the Java bindings share the same libopencv_java4.so instance, so
// a Java Mat's nativeObj is a cv::Mat* we can use directly here.

#include <jni.h>
#include <android/log.h>
#include <vector>

#include "opencv2/core.hpp"
#include "opencv2/stitching.hpp"
#include "opencv2/stitching/warpers.hpp"

#define LOG_TAG "NativeStitch"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Extra status codes beyond cv::Stitcher::Status (which are 0..3).
static constexpr jint STATUS_EXCEPTION = 100;

// Projection (surface) the panorama is warped onto. Must match the ordinals of
// NativeStitch.Projection on the Kotlin side.
enum Projection {
    PROJ_SPHERICAL = 0,
    PROJ_CYLINDRICAL = 1,
    PROJ_PLANE = 2,
};

static cv::Ptr<cv::WarperCreator> makeWarper(jint projection) {
    switch (projection) {
        case PROJ_CYLINDRICAL: return cv::makePtr<cv::CylindricalWarper>();
        case PROJ_PLANE:       return cv::makePtr<cv::PlaneWarper>();
        case PROJ_SPHERICAL:
        default:               return cv::makePtr<cv::SphericalWarper>();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hdrstacker_panorama_NativeStitch_nativeStitch(
        JNIEnv *env, jobject /*thiz*/, jlongArray inputMatAddrs, jint projection,
        jlong outputMatAddr) {
    const jsize n = env->GetArrayLength(inputMatAddrs);
    if (n < 2) return static_cast<jint>(cv::Stitcher::ERR_NEED_MORE_IMGS);

    jlong *addrs = env->GetLongArrayElements(inputMatAddrs, nullptr);
    if (addrs == nullptr) return STATUS_EXCEPTION;

    std::vector<cv::Mat> images;
    images.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        images.push_back(*reinterpret_cast<cv::Mat *>(addrs[i]));
    }
    env->ReleaseLongArrayElements(inputMatAddrs, addrs, JNI_ABORT);

    cv::Mat &pano = *reinterpret_cast<cv::Mat *>(outputMatAddr);

    try {
        cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(cv::Stitcher::PANORAMA);
        stitcher->setWarper(makeWarper(projection));
        cv::Stitcher::Status status = stitcher->stitch(images, pano);
        return static_cast<jint>(status);
    } catch (const cv::Exception &e) {
        LOGE("cv::Exception during stitch: %s", e.what());
        return STATUS_EXCEPTION;
    } catch (const std::exception &e) {
        LOGE("std::exception during stitch: %s", e.what());
        return STATUS_EXCEPTION;
    } catch (...) {
        LOGE("unknown exception during stitch");
        return STATUS_EXCEPTION;
    }
}
