package com.hdrstacker.panorama

import org.opencv.android.OpenCVLoader

/**
 * JNI bridge to the natively-compiled OpenCV stitching module
 * (see cpp/native-stitch.cpp). The Maven OpenCV artifact omits `stitching`,
 * so we build it ourselves and call it here.
 */
object NativeStitch {
    /** cv::Stitcher::Status values plus our own exception marker. */
    const val OK = 0
    const val ERR_NEED_MORE_IMGS = 1
    const val ERR_HOMOGRAPHY_EST_FAIL = 2
    const val ERR_CAMERA_PARAMS_ADJUST_FAIL = 3
    const val ERR_EXCEPTION = 100

    init {
        // Ensure libopencv_java4.so (which libpanostitcher.so links against) is
        // loaded before we pull in our own native library.
        OpenCVLoader.initLocal()
        System.loadLibrary("panostitcher")
    }

    /**
     * Stitch the given OpenCV Mats (8-bit 3-channel) into [outputMatAddr].
     *
     * @param inputMatAddrs native handles (Mat.nativeObj) of the input images
     * @param outputMatAddr native handle of the destination Mat
     * @return a cv::Stitcher status code, or [ERR_EXCEPTION]
     */
    external fun nativeStitch(inputMatAddrs: LongArray, outputMatAddr: Long): Int
}
