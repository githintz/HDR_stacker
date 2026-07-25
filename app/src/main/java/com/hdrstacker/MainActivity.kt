package com.hdrstacker

import android.content.Intent
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
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: HdrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Photos handed over from the library's multi-select; the ViewModel
        // survives config changes, so only seed it on first creation.
        if (savedInstanceState == null) {
            androidx.core.content.IntentCompat
                .getParcelableArrayListExtra(intent, EXTRA_SOURCE_URIS, android.net.Uri::class.java)
                ?.takeIf { it.isNotEmpty() }
                ?.let { vm.addFrames(it) }
        }
        enableEdgeToEdge()
        setContent {
            HdrStackerTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                HdrScreen(
                    state = state,
                    onPick = { pickFrames.launch(RAW_MIME_TYPES) },
                    onRemove = vm::removeFrame,
                    onClear = vm::clearFrames,
                    onMode = vm::setMode,
                    onHalfRes = vm::setHalfResolution,
                    onDiagnostic = vm::setDiagnostic,
                    onStack = vm::stack,
                    onCancel = vm::cancel,
                    onOpenResult = ::openInGallery,
                )
            }
        }
    }

    private val pickFrames =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.addFrames(uris)
        }

    private fun openInGallery(result: StackResult) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(result.uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        /** ArrayList<Uri> extra with photos pre-selected in the library grid. */
        const val EXTRA_SOURCE_URIS = "com.hdrstacker.SOURCE_URIS"

        // NEF is often reported as image/x-nikon-nef or the generic image/*; some
        // providers only expose octet-stream, so we cast a wide net and let LibRaw
        // reject anything that isn't actually a RAW.
        private val RAW_MIME_TYPES = arrayOf(
            "image/x-nikon-nef",
            "image/x-nef",
            "image/tiff",
            "application/octet-stream",
            "image/*",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HdrScreen(
    state: UiState,
    onPick: () -> Unit,
    onRemove: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
    onMode: (FusionMode) -> Unit,
    onHalfRes: (Boolean) -> Unit,
    onDiagnostic: (Boolean) -> Unit,
    onStack: () -> Unit,
    onCancel: () -> Unit,
    onOpenResult: (StackResult) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("HDR Stacker") }) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pick your Nikon NEF exposure brackets, then merge them into a " +
                    "single HDR image.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, enabled = !state.running) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Add NEF frames")
                }
                if (state.frames.isNotEmpty()) {
                    OutlinedButton(onClick = onClear, enabled = !state.running) {
                        Text("Clear")
                    }
                }
            }

            OptionsCard(state, onMode, onHalfRes, onDiagnostic)

            // Frame list
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.frames, key = { it.uri.toString() }) { frame ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Photo, contentDescription = null) },
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
                Text(
                    "Error: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.result?.let { result ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved ${result.displayName} to Pictures/HDRStacker")
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
                    onClick = onStack,
                    enabled = state.canStack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.frames.size < 2) "Add at least 2 frames"
                        else "Create HDR (${state.frames.size} frames)",
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(
    state: UiState,
    onMode: (FusionMode) -> Unit,
    onHalfRes: (Boolean) -> Unit,
    onDiagnostic: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Merge method", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == FusionMode.EXPOSURE_FUSION,
                    onClick = { onMode(FusionMode.EXPOSURE_FUSION) },
                    label = { Text("Exposure fusion") },
                )
                FilterChip(
                    selected = state.mode == FusionMode.DEBEVEC_HDR,
                    onClick = { onMode(FusionMode.DEBEVEC_HDR) },
                    label = { Text("True HDR + tonemap") },
                )
            }
            Text(
                when (state.mode) {
                    FusionMode.EXPOSURE_FUSION ->
                        "Fast, robust. Blends the best-exposed regions of each frame."
                    FusionMode.DEBEVEC_HDR ->
                        "Reconstructs scene radiance from shutter speeds, then tonemaps."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Half resolution", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Faster, uses far less memory",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.halfResolution, onCheckedChange = onHalfRes)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostic mode", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Saves each decoded and aligned frame to the gallery, and " +
                            "verifies frame memory between stages — stops with a " +
                            "message naming the exact stage if corruption is detected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.diagnostic, onCheckedChange = onDiagnostic)
            }
        }
    }
}
