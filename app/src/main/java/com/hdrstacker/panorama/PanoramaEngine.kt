package com.hdrstacker.panorama

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Why a stitch failed, so the UI can show something actionable. */
enum class PanoramaError {
    /** Fewer than two usable images, or the stitcher couldn't find enough. */
    NEED_MORE_IMAGES,

    /** Not enough overlap / matching features to estimate the geometry. */
    NOT_ENOUGH_OVERLAP,

    /** Feature matches found but camera parameter adjustment failed. */
    CAMERA_PARAMS_FAILED,

    /** An input image could not be decoded. */
    DECODE_FAILED,

    UNKNOWN,
}

class PanoramaException(val kind: PanoramaError, message: String) : Exception(message)

data class PanoramaResult(val uri: Uri, val displayName: String)

/**
 * Panorama stitching, fully independent of the HDR pipeline.
 *
 * Loads a set of overlapping photos, runs OpenCV's [Stitcher] (feature
 * detection + homography estimation + multi-band blending), and saves the
 * merged result to the gallery. Runs entirely off the main thread and reports
 * failures as a [PanoramaException] rather than crashing.
 */
object PanoramaEngine {
    private const val TAG = "PanoramaEngine"

    /**
     * Inputs are downscaled so their long edge is at most this many pixels
     * before stitching. Full-resolution phone photos make the stitcher very
     * memory- and time-hungry on-device; this keeps a multi-image panorama
     * tractable. Raising it improves output detail at the cost of memory.
     */
    private const val MAX_INPUT_LONG_EDGE = 2000

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
     * Stitch [sources] (2+ overlapping images) into a single panorama and return
     * it as a Bitmap. Saving is a separate step ([save]) so the caller can let
     * the user crop first.
     *
     * @param projection surface to warp onto (spherical / cylindrical / plane)
     * @param onProgress reports (fraction 0..1, human-readable stage label)
     * @throws PanoramaException on any recoverable failure (too few images, not
     *         enough overlap, undecodable input, etc.)
     */
    suspend fun stitch(
        context: Context,
        sources: List<Uri>,
        projection: NativeStitch.Projection = NativeStitch.Projection.SPHERICAL,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Bitmap = withContext(Dispatchers.Default) {
        if (sources.size < 2) {
            throw PanoramaException(
                PanoramaError.NEED_MORE_IMAGES,
                "Select at least two overlapping photos.",
            )
        }
        ensureOpenCv()

        val images = ArrayList<Mat>(sources.size)
        try {
            // ---- 1. Load + downscale each image into an OpenCV RGB Mat ----
            sources.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                onProgress(index / (sources.size + 2f), "Loading image ${index + 1}/${sources.size}")

                val bmp = decodeDownscaled(context, uri)
                    ?: throw PanoramaException(
                        PanoramaError.DECODE_FAILED,
                        "Could not read image ${index + 1}.",
                    )
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)          // CV_8UC4, RGBA
                bmp.recycle()
                val rgb = Mat()
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                rgba.release()
                images.add(rgb)
            }

            // ---- 2. Stitch (native OpenCV Stitcher; see NativeStitch) ----
            coroutineContext.ensureActive()
            onProgress(sources.size / (sources.size + 1f), "Stitching panorama")
            val pano = Mat()
            val status = try {
                NativeStitch.nativeStitch(
                    images.map { it.nativeObj }.toLongArray(),
                    projection.ordinal,
                    pano.nativeObj,
                )
            } catch (t: Throwable) {
                pano.release()
                if (t is kotlinx.coroutines.CancellationException) throw t
                throw PanoramaException(
                    PanoramaError.UNKNOWN,
                    "Stitching failed: ${t.message ?: t.javaClass.simpleName}",
                )
            }
            if (status != NativeStitch.OK) {
                pano.release()
                throw statusToException(status)
            }

            // ---- 3. Encode to a Bitmap (saved later, after optional crop) ----
            val outBmp = Bitmap.createBitmap(pano.cols(), pano.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(pano, outBmp)
            pano.release()
            onProgress(1f, "Done")
            outBmp
        } finally {
            images.forEach { it.release() }
        }
    }

    /** Save an (optionally cropped) panorama Bitmap to Pictures/Panoramas. */
    suspend fun save(context: Context, bitmap: Bitmap): PanoramaResult =
        withContext(Dispatchers.IO) {
            val saved = saveToGallery(context, bitmap)
            Log.i(TAG, "panorama saved: ${saved.displayName}")
            saved
        }

    private fun statusToException(status: Int): PanoramaException = when (status) {
        NativeStitch.ERR_NEED_MORE_IMGS -> PanoramaException(
            PanoramaError.NOT_ENOUGH_OVERLAP,
            "Couldn't find enough overlap between the photos. Use shots that " +
                "overlap by roughly 30–50% and try again.",
        )
        NativeStitch.ERR_HOMOGRAPHY_EST_FAIL -> PanoramaException(
            PanoramaError.NOT_ENOUGH_OVERLAP,
            "Couldn't match features between the photos. They may not overlap " +
                "enough, or the scene is too flat/repetitive.",
        )
        NativeStitch.ERR_CAMERA_PARAMS_ADJUST_FAIL -> PanoramaException(
            PanoramaError.CAMERA_PARAMS_FAILED,
            "Matched the photos but couldn't solve the camera geometry. Try " +
                "shots taken by rotating on the spot rather than moving sideways.",
        )
        else -> PanoramaException(PanoramaError.UNKNOWN, "Stitching failed (code $status).")
    }

    /** Decode a content [uri] to a software Bitmap, downscaled to [MAX_INPUT_LONG_EDGE]. */
    private fun decodeDownscaled(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // OpenCV can't read HARDWARE bitmaps, so force a software allocation.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val longEdge = max(w, h)
            if (longEdge > MAX_INPUT_LONG_EDGE) {
                val scale = MAX_INPUT_LONG_EDGE.toFloat() / longEdge
                decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
            }
        }
    }.getOrNull()

    private fun saveToGallery(context: Context, bitmap: Bitmap): PanoramaResult {
        val name = "PANO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
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
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Panoramas")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: error("Failed to create gallery entry")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error("Failed to open output stream")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return PanoramaResult(uri, name)
    }
}
