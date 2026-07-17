package com.hdrstacker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Tonemap / fusion algorithm used to combine the aligned brackets. */
enum class FusionMode {
    /** Mertens exposure fusion — no exposure times needed, directly displayable. */
    EXPOSURE_FUSION,

    /** Debevec HDR reconstruction + Mantiuk tonemap — needs exposure times. */
    DEBEVEC_HDR,
}

data class StackResult(val uri: Uri, val displayName: String)

/**
 * The HDR pipeline. Native LibRaw decodes each NEF; OpenCV aligns the frames
 * and fuses them. Runs entirely off the main thread.
 */
object HdrEngine {
    private const val TAG = "HdrEngine"

    @Volatile
    private var openCvReady = false

    private fun ensureOpenCv() {
        if (!openCvReady) {
            check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
            openCvReady = true
            Log.i(TAG, "OpenCV initialised")
        }
    }

    /**
     * Decode, align and fuse [sources] into a single image saved to the
     * device gallery (Pictures/HDRStacker).
     *
     * Exposure times for the Debevec path are taken straight from each NEF's
     * metadata during decode, so the caller never has to supply them.
     *
     * @param onProgress reports (fraction 0..1, human-readable stage label)
     */
    suspend fun stack(
        context: Context,
        sources: List<Uri>,
        mode: FusionMode = FusionMode.EXPOSURE_FUSION,
        halfResolution: Boolean = false,
        diagnostic: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): StackResult = withContext(Dispatchers.Default) {
        require(sources.size >= 2) { "Need at least two bracketed frames" }
        ensureOpenCv()

        val mats = ArrayList<Mat>(sources.size)
        val shutters = FloatArray(sources.size)
        try {
            // ---- 1a. Decode every NEF FIRST, holding only JVM int[] buffers.
            // No OpenCV Mats are allocated during this phase. Diagnostics showed
            // the decoded frames come out clean, yet a *different* frame's pixel
            // buffer ends up corrupted (a moving victim) — the signature of a
            // heap overrun inside the native RAW decoder clobbering a
            // neighbouring native allocation. By deferring all Mat allocation
            // until after every decode finishes, there is no adjacent frame Mat
            // for a decoder overrun to corrupt (the int[] results live on the
            // managed JVM heap, separate from the native malloc heap).
            val decodedFrames = arrayOfNulls<DecodedImage>(sources.size)
            sources.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                onProgress(index / (sources.size + 2f), "Decoding frame ${index + 1}/${sources.size}")

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot open $uri")

                val d = NativeHdr.decodeNef(bytes, halfResolution)
                    ?: error("Not a decodable RAW: $uri")
                decodedFrames[index] = d
                shutters[index] = d.shutter
            }

            // ---- 1b. Convert the decoded buffers to Mats (no RAW decoding here) ----
            for (index in decodedFrames.indices) {
                coroutineContext.ensureActive()
                val d = decodedFrames[index]!!
                val bmp = Bitmap.createBitmap(d.pixels, d.width, d.height, Bitmap.Config.ARGB_8888)
                decodedFrames[index] = null       // drop the int[] as soon as it's copied
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)      // CV_8UC4, RGBA
                bmp.recycle()
                val rgb = Mat()
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                rgba.release()
                mats.add(rgb)

                if (diagnostic) {
                    dumpMat(context, rgb, "HDRDIAG_1decoded_$index")
                }
            }

            require(mats.allSameSize()) {
                "Brackets have different dimensions — were they all shot at the same resolution?"
            }

            // ---- 2. Align hand-held brackets ----
            // Align each frame to the first using AlignMTB's per-frame Mat->Mat
            // API (calculateShift + shiftMat). We deliberately avoid the
            // process(List, List) overload: its vector-of-Mat marshalling is
            // unreliable — the separate-list form comes back empty, and the
            // in-place form returns the zero-shift reference frame as a
            // use-after-free (a rainbow-striped frame 0). Doing it per frame
            // keeps every buffer independently owned.
            coroutineContext.ensureActive()
            onProgress(sources.size / (sources.size + 2f), "Aligning frames")
            run {
                val align = Photo.createAlignMTB()
                // calculateShift requires single-channel input, so compute the
                // shift on grayscale versions and apply it to the colour frame.
                val refGray = Mat()
                Imgproc.cvtColor(mats[0], refGray, Imgproc.COLOR_RGB2GRAY)
                val aligned = ArrayList<Mat>(mats.size)
                aligned.add(mats[0].clone())                // reference: unshifted
                for (i in 1 until mats.size) {
                    coroutineContext.ensureActive()
                    val gray = Mat()
                    Imgproc.cvtColor(mats[i], gray, Imgproc.COLOR_RGB2GRAY)
                    val shift = align.calculateShift(refGray, gray)
                    gray.release()
                    val shifted = Mat()
                    align.shiftMat(mats[i], shifted, shift)  // shift the colour frame
                    aligned.add(shifted)
                }
                refGray.release()
                mats.forEach { it.release() }
                mats.clear()
                mats.addAll(aligned)
            }

            if (diagnostic) {
                mats.forEachIndexed { i, m -> dumpMat(context, m, "HDRDIAG_2aligned_$i") }
            }

            // ---- 3. Fuse ----
            coroutineContext.ensureActive()
            check(mats.isNotEmpty()) { "Alignment produced no frames" }
            onProgress((sources.size + 1) / (sources.size + 2f), "Merging HDR")
            val result8 = when (mode) {
                FusionMode.EXPOSURE_FUSION -> mertens(mats)
                FusionMode.DEBEVEC_HDR -> debevec(mats, resolveExposureTimes(shutters))
            }

            // ---- 4. Encode to a Bitmap and save to the gallery ----
            onProgress(0.98f, "Saving")
            val outBmp = Bitmap.createBitmap(
                result8.cols(), result8.rows(), Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(result8, outBmp)
            result8.release()

            val saved = saveToGallery(context, outBmp)
            outBmp.recycle()
            onProgress(1f, "Done")
            saved
        } finally {
            mats.forEach { it.release() }
        }
    }

    /** Mertens exposure fusion → 8-bit RGB Mat. */
    private fun mertens(mats: List<Mat>): Mat {
        val fused = Mat()                                   // CV_32FC3 in [0,1]
        Photo.createMergeMertens().process(mats, fused)
        val out = Mat()
        fused.convertTo(out, CvType.CV_8UC3, 255.0)         // scale then saturating cast to [0,255]
        fused.release()
        return out
    }

    /** Debevec HDR reconstruction + Mantiuk tonemap → 8-bit RGB Mat. */
    private fun debevec(mats: List<Mat>, exposures: FloatArray): Mat {
        val times = Mat(exposures.size, 1, CvType.CV_32F)
        times.put(0, 0, exposures)

        val response = Mat()
        Photo.createCalibrateDebevec().process(mats, response, times)

        val hdr = Mat()                                     // CV_32FC3, scene-linear radiance
        Photo.createMergeDebevec().process(mats, hdr, times, response)
        response.release()
        times.release()

        val ldr = Mat()                                     // CV_32FC3 in [0,1]
        Photo.createTonemapMantiuk(2.2f, 0.85f, 1.2f).process(hdr, ldr)
        hdr.release()

        val out = Mat()
        ldr.convertTo(out, CvType.CV_8UC3, 255.0)
        ldr.release()
        return out
    }

    /**
     * Debevec needs one distinct, positive exposure time per frame. Use the
     * metadata shutter speeds when they're all present and distinct; otherwise
     * fall back to an assumed even 2-EV bracket spacing so the merge still runs.
     */
    private fun resolveExposureTimes(shutters: FloatArray): FloatArray {
        val allValid = shutters.all { it > 0f }
        val distinct = shutters.toSet().size == shutters.size
        if (allValid && distinct) return shutters
        // Synthetic 2-EV steps: 1/8, 1/4, 1/2, 1, 2, ... seconds.
        return FloatArray(shutters.size) { i -> (0.125 * Math.pow(2.0, i.toDouble())).toFloat() }
    }

    private fun List<Mat>.allSameSize(): Boolean {
        if (isEmpty()) return true
        val s = this[0].size()
        return all { it.size() == s }
    }

    /**
     * Save a single Mat straight to the gallery for diagnostics, so the user
     * can eyeball where an artifact first appears without needing logcat.
     */
    private fun dumpMat(context: Context, mat: Mat, label: String) {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        runCatching { saveToGallery(context, bmp, label) }
            .onFailure { Log.e(TAG, "diagnostic dump failed for $label", it) }
        bmp.recycle()
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String? = null): StackResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = (baseName ?: "HDR") + "_" + stamp + ".jpg"
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/HDRStacker",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: error("Failed to create gallery entry")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error("Failed to open output stream")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return StackResult(uri, name)
    }
}
