package com.hdrstacker

/**
 * A single decoded RAW frame handed back from native code.
 *
 * [pixels] is ARGB_8888 (0xAARRGGBB), row-major, length == width * height.
 * [shutter] is the exposure time in seconds read from the RAW metadata
 * (0 if unknown). The fields are @JvmField so the JNI constructor lookup
 * stays simple.
 */
class DecodedImage(
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val shutter: Float,
    @JvmField val pixels: IntArray,
)
