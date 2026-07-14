package com.hdrstacker

/**
 * Thin wrapper over the native LibRaw bridge (see cpp/native-hdr.cpp).
 */
object NativeHdr {
    init {
        System.loadLibrary("hdrstacker")
    }

    /** Version string of the bundled LibRaw build, for diagnostics. */
    external fun libRawVersion(): String

    /**
     * Decode a Nikon NEF (or any other LibRaw-supported RAW) into RGB pixels.
     *
     * @param nefBytes the full file contents read from the source Uri
     * @param halfSize decode at half resolution (¼ the pixels) — much lighter
     *                 on memory, useful for large brackets on modest phones
     * @return the decoded frame, or null if the file could not be decoded
     */
    external fun decodeNef(nefBytes: ByteArray, halfSize: Boolean): DecodedImage?
}
