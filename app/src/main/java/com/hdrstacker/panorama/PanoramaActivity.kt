package com.hdrstacker.panorama

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hdrstacker.HdrStackerTheme

/**
 * Standalone panorama-stitching screen. Independent of the HDR feature; shares
 * only the app theme.
 */
class PanoramaActivity : ComponentActivity() {
    private val vm: PanoramaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HdrStackerTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                PanoramaScreen(
                    state = state,
                    onPick = { pickImages.launch(arrayOf("image/*")) },
                    onRemove = vm::removeFrame,
                    onClear = vm::clearFrames,
                    onStitch = vm::stitch,
                    onCancel = vm::cancel,
                    onOpenResult = ::openInGallery,
                )
            }
        }
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.addFrames(uris)
        }

    private fun openInGallery(result: PanoramaResult) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(result.uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanoramaScreen(
    state: PanoUiState,
    onPick: () -> Unit,
    onRemove: (Uri) -> Unit,
    onClear: () -> Unit,
    onStitch: () -> Unit,
    onCancel: () -> Unit,
    onOpenResult: (PanoramaResult) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Panorama Stitcher") }) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pick a series of overlapping photos (in order, overlapping by " +
                    "roughly 30–50%) and merge them into one panorama.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, enabled = !state.running) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Add photos")
                }
                if (state.frames.isNotEmpty()) {
                    OutlinedButton(onClick = onClear, enabled = !state.running) { Text("Clear") }
                }
            }

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.frames, key = { it.uri.toString() }) { frame ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Panorama, contentDescription = null) },
                        headlineContent = {
                            Text(frame.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(frame.uri) }, enabled = !state.running) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            state.error?.let {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            state.result?.let { result ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved ${result.displayName} to Pictures/Panoramas")
                        Button(onClick = { onOpenResult(result) }) { Text("Open in gallery") }
                    }
                }
            }

            if (state.running) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.stage, style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Button(
                    onClick = onStitch,
                    enabled = state.canStitch,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.frames.size < 2) "Add at least 2 photos"
                        else "Stitch panorama (${state.frames.size} photos)",
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
