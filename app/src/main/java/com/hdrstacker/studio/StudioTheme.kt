package com.hdrstacker.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The studio shell is always dark: neutral, low-chroma grays so photos are the
 * brightest thing on screen, with a restrained blue accent for interactive
 * elements. Deliberately not dynamic color — wallpaper-derived tints would
 * shift how images read.
 */
private val StudioDarkColors = darkColorScheme(
    primary = Color(0xFF9ECBFF),
    onPrimary = Color(0xFF0B3350),
    primaryContainer = Color(0xFF1E4A68),
    onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = Color(0xFFB8C8D8),
    onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF394856),
    onSecondaryContainer = Color(0xFFD4E4F4),
    tertiary = Color(0xFFE8C08E),
    onTertiary = Color(0xFF432C06),
    tertiaryContainer = Color(0xFF5D421B),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF0F1215),
    onBackground = Color(0xFFE2E4E8),
    surface = Color(0xFF14171B),
    onSurface = Color(0xFFE2E4E8),
    surfaceVariant = Color(0xFF262B31),
    onSurfaceVariant = Color(0xFFAEB6C0),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF171A1F),
    surfaceContainer = Color(0xFF1B1F24),
    surfaceContainerHigh = Color(0xFF23272D),
    surfaceContainerHighest = Color(0xFF2B3037),
    outline = Color(0xFF858D98),
    outlineVariant = Color(0xFF3C434C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioDarkColors, content = content)
}
