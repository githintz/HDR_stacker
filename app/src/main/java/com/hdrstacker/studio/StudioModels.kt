package com.hdrstacker.studio

import androidx.compose.ui.graphics.Color

/** Where a library photo lives. Cloud photos get a badge in the grid. */
enum class PhotoSource { LOCAL, CLOUD }

/**
 * Sharpness verdict from the (future) smart-library classifier. Anything other
 * than [NONE] is surfaced as a badge on the grid tile.
 */
enum class QualityFlag { NONE, SOFT_FOCUS, BLURRY }

/**
 * A photo in the library. Until the media/cloud backends exist, thumbnails are
 * placeholder gradients chosen by [paletteIndex]; a real implementation swaps
 * that for a content Uri + thumbnail loader without touching the grid UI.
 */
data class PhotoItem(
    val id: Long,
    val name: String,
    val source: PhotoSource,
    val paletteIndex: Int,
    val tags: List<String> = emptyList(),
    val isDuplicate: Boolean = false,
    val quality: QualityFlag = QualityFlag.NONE,
)

/** Photo-like gradients used as stand-in thumbnails until real decoding lands. */
object PhotoPalette {
    private val gradients: List<List<Color>> = listOf(
        listOf(Color(0xFF355C7D), Color(0xFFC06C84), Color(0xFFF67280)), // alpenglow
        listOf(Color(0xFF16222A), Color(0xFF3A6073)),                    // night lake
        listOf(Color(0xFF134E5E), Color(0xFF71B280)),                    // forest
        listOf(Color(0xFF2C3E50), Color(0xFFFD746C)),                    // dusk ridge
        listOf(Color(0xFF141E30), Color(0xFF243B55)),                    // blue hour
        listOf(Color(0xFFBA8B02), Color(0xFF181818)),                    // golden field
        listOf(Color(0xFF4B6CB7), Color(0xFF182848)),                    // sky gradient
        listOf(Color(0xFF5D4157), Color(0xFFA8CABA)),                    // misty morning
        listOf(Color(0xFF603813), Color(0xFFB29F94)),                    // canyon
        listOf(Color(0xFF1F1C2C), Color(0xFF928DAB)),                    // night sky
        listOf(Color(0xFF0F2027), Color(0xFF2C5364)),                    // deep water
        listOf(Color(0xFFB79891), Color(0xFF94716B)),                    // portrait tones
    )

    fun colors(index: Int): List<Color> = gradients[index.mod(gradients.size)]
}

/**
 * Demo library contents so the grid, badges, and selection flows can be seen
 * on-device before MediaStore/cloud scanning exists. Loaded on demand from the
 * library's empty state.
 */
object SampleLibrary {
    fun photos(): List<PhotoItem> {
        var id = 0L
        fun local(
            name: String,
            palette: Int,
            tags: List<String> = emptyList(),
            duplicate: Boolean = false,
            quality: QualityFlag = QualityFlag.NONE,
        ) = PhotoItem(++id, name, PhotoSource.LOCAL, palette, tags, duplicate, quality)

        fun cloud(
            name: String,
            palette: Int,
            tags: List<String> = emptyList(),
            duplicate: Boolean = false,
            quality: QualityFlag = QualityFlag.NONE,
        ) = PhotoItem(++id, name, PhotoSource.CLOUD, palette, tags, duplicate, quality)

        return listOf(
            local("DSC_0412.NEF", 0, tags = listOf("Alps 2025", "Bracketed")),
            local("DSC_0413.NEF", 0, tags = listOf("Alps 2025", "Bracketed")),
            local("DSC_0414.NEF", 0, tags = listOf("Alps 2025", "Bracketed")),
            local("DSC_0431.NEF", 3, tags = listOf("Alps 2025", "Sunset")),
            local("DSC_0432.NEF", 3, tags = listOf("Sunset"), duplicate = true),
            local("DSC_0433.NEF", 3, tags = listOf("Sunset"), duplicate = true),
            local("DSC_0518.NEF", 2, tags = listOf("Forest")),
            local("DSC_0519.NEF", 2, quality = QualityFlag.SOFT_FOCUS),
            local("DSC_0544.NEF", 8, tags = listOf("Canyon")),
            local("DSC_0545.NEF", 8, quality = QualityFlag.BLURRY),
            local("DSC_0601.NEF", 6, tags = listOf("Pano", "Left")),
            local("DSC_0602.NEF", 6, tags = listOf("Pano")),
            local("DSC_0603.NEF", 6, tags = listOf("Pano", "Right")),
            local("IMG_2201.HEIC", 11, tags = listOf("Portrait")),
            local("IMG_2202.HEIC", 11, quality = QualityFlag.SOFT_FOCUS),
            local("IMG_2214.JPG", 5, tags = listOf("Golden hour")),
            cloud("2024-11-02_142233.NEF", 4, tags = listOf("Blue hour")),
            cloud("2024-11-02_142240.NEF", 4, duplicate = true),
            cloud("2024-11-02_142251.NEF", 4, duplicate = true),
            cloud("night-lake-final.jpg", 1, tags = listOf("Edited")),
            cloud("milkyway_stack_v2.tif", 9, tags = listOf("Astro")),
            cloud("harbor_pano.jpg", 10, tags = listOf("Pano", "Edited")),
            cloud("misty_morning.NEF", 7),
            cloud("field_bracket_1.NEF", 5, tags = listOf("Bracketed")),
        )
    }
}
