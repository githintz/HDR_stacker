package com.hdrstacker.panorama

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PanoFrame(val uri: Uri, val name: String)

data class PanoUiState(
    val frames: List<PanoFrame> = emptyList(),
    val running: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val result: PanoramaResult? = null,
    val error: String? = null,
) {
    val canStitch: Boolean get() = frames.size >= 2 && !running
}

class PanoramaViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PanoUiState())
    val state: StateFlow<PanoUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun addFrames(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val existing = _state.value.frames.map { it.uri }.toSet()
        val added = uris.filter { it !in existing }.map { uri ->
            PanoFrame(uri, displayName(resolver, uri))
        }
        _state.update { it.copy(frames = it.frames + added, result = null, error = null) }
    }

    fun removeFrame(uri: Uri) =
        _state.update { it.copy(frames = it.frames.filterNot { f -> f.uri == uri }) }

    fun clearFrames() =
        _state.update { it.copy(frames = emptyList(), result = null, error = null) }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, stage = "Cancelled") }
    }

    fun stitch() {
        if (!_state.value.canStitch) return
        val frames = _state.value.frames
        _state.update { it.copy(running = true, progress = 0f, stage = "Starting", error = null, result = null) }
        job = viewModelScope.launch {
            runCatching {
                PanoramaEngine.stitch(
                    context = getApplication(),
                    sources = frames.map { it.uri },
                    onProgress = { p, stage ->
                        _state.update { it.copy(progress = p, stage = stage) }
                    },
                )
            }.onSuccess { result ->
                _state.update { it.copy(running = false, result = result, progress = 1f, stage = "Done") }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(running = false, error = t.message ?: t.toString(), stage = "Failed") }
            }
        }
    }

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "image"
}
