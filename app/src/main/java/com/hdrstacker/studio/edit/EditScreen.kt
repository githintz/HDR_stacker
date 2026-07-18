package com.hdrstacker.studio.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hdrstacker.studio.PhotoItem
import com.hdrstacker.studio.Thumbnails
import java.util.Locale

private const val PREVIEW_PX = 1280

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTopBar(
    state: EditUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                state.photo?.name ?: "Edit",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (state.photo != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                }
            }
        },
        actions = {
            if (state.photo != null) {
                IconButton(onClick = onExport) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Export")
                }
            }
        },
    )
}

@Composable
fun EditScreen(
    state: EditUiState,
    onPreview: (EditValues) -> Unit,
    onCommit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onGoToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = state.photo
    if (photo == null) {
        EmptyEdit(onGoToLibrary, modifier.fillMaxSize())
        return
    }
    Column(modifier.fillMaxSize()) {
        PreviewArea(
            photo = photo,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUndo, enabled = state.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = state.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
        }
        SliderPanel(
            values = state.values,
            onPreview = onPreview,
            onCommit = onCommit,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.15f),
        )
    }
}

@Composable
private fun PreviewArea(photo: PhotoItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(initialValue = null, photo.uri) {
        value = Thumbnails.load(context, photo.uri, PREVIEW_PX)
    }
    Box(
        modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            )
        } else {
            CircularProgressIndicator()
        }
        Text(
            "Adjustments don't render yet — live preview arrives with the edit engine",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SliderPanel(
    values: EditValues,
    onPreview: (EditValues) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SectionLabel("Light")
            EditSlider(
                label = "Exposure",
                value = values.exposure,
                range = -5f..5f,
                format = { fmt("%+.1f EV", it) },
                onChange = { onPreview(values.copy(exposure = it)) },
                onCommit = onCommit,
            )
            EditSlider(
                label = "Contrast",
                value = values.contrast,
                range = -100f..100f,
                format = { fmt("%+.0f", it) },
                onChange = { onPreview(values.copy(contrast = it)) },
                onCommit = onCommit,
            )
            EditSlider(
                label = "Highlights",
                value = values.highlights,
                range = -100f..100f,
                format = { fmt("%+.0f", it) },
                onChange = { onPreview(values.copy(highlights = it)) },
                onCommit = onCommit,
            )
            EditSlider(
                label = "Shadows",
                value = values.shadows,
                range = -100f..100f,
                format = { fmt("%+.0f", it) },
                onChange = { onPreview(values.copy(shadows = it)) },
                onCommit = onCommit,
            )
            Spacer(Modifier.height(8.dp))
            SectionLabel("Color")
            EditSlider(
                label = "Temperature",
                value = values.temperature,
                range = 2000f..10000f,
                format = { fmt("%.0f K", it) },
                onChange = { onPreview(values.copy(temperature = it)) },
                onCommit = onCommit,
            )
            EditSlider(
                label = "Tint",
                value = values.tint,
                range = -100f..100f,
                format = { fmt("%+.0f", it) },
                onChange = { onPreview(values.copy(tint = it)) },
                onCommit = onCommit,
            )
            EditSlider(
                label = "Saturation",
                value = values.saturation,
                range = -100f..100f,
                format = { fmt("%+.0f", it) },
                onChange = { onPreview(values.copy(saturation = it)) },
                onCommit = onCommit,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun EditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onCommit,
        )
    }
}

@Composable
private fun EmptyEdit(onGoToLibrary: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Tune,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("No photo open", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a photo from the Library to start editing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoToLibrary) { Text("Go to Library") }
    }
}

/**
 * Export options dialog. Format choice is visual-only for now; confirming
 * calls [onExport], which the shell answers with a "not yet implemented" note.
 */
@Composable
fun ExportDialog(
    photoName: String,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    var format by remember { mutableStateOf("JPEG") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column {
                Text(
                    photoName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JPEG", "TIFF 16-bit", "DNG").forEach { option ->
                        FilterChip(
                            selected = format == option,
                            onClick = { format = option },
                            label = { Text(option) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun fmt(pattern: String, value: Float): String =
    String.format(Locale.US, pattern, value)
