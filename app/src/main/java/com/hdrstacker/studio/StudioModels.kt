package com.hdrstacker.studio

import android.net.Uri

/** Where a library photo lives. Cloud photos get a badge in the grid. */
enum class PhotoSource { LOCAL, CLOUD }

/**
 * Sharpness verdict from the (future) smart-library classifier. Anything other
 * than [NONE] is surfaced as a badge on the grid tile.
 */
enum class QualityFlag { NONE, SOFT_FOCUS, BLURRY }

/**
 * A photo in the library. Local photos come straight from MediaStore; nothing
 * leaves the device unless a cloud provider is connected. Tags and quality
 * flags stay empty until the smart-library classifiers exist — the grid
 * already renders them when present.
 */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val source: PhotoSource,
    val dateTakenMillis: Long,
    val tags: List<String> = emptyList(),
    val isDuplicate: Boolean = false,
    val quality: QualityFlag = QualityFlag.NONE,
)
