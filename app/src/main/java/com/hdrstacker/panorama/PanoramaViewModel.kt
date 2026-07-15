package com.hdrstacker.panorama

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PanoFrame(val uri: Uri, val name: String)

data class PanoUiState(
    val frames: List<PanoFrame> = emptyList(),
    val projection: NativeStitch.Projection = NativeStitch.Projection.SPHERICAL,
    val running: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    /** Set once stitching succeeds; the user then crops this before it's saved. */
    val stitched: Bitmap? = null,
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

    fun setProjection(projection: NativeStitch.Projection) =
        _state.update { it.copy(projection = projection) }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, stage = "Cancelled") }
    }

    fun stitch() {
        if (!_state.value.canStitch) return
        val s = _state.value
        _state.update { it.copy(running = true, progress = 0f, stage = "Starting", error = null, result = null) }
        job = viewModelScope.launch {
            runCatching {
                PanoramaEngine.stitch(
                    context = getApplication(),
                    sources = s.frames.map { it.uri },
                    projection = s.projection,
                    onProgress = { p, stage -> _state.update { it.copy(progress = p, stage = stage) } },
                )
            }.onSuccess { bitmap ->
                // Hand the result to the crop screen; saving happens on confirm.
                _state.update { it.copy(running = false, stitched = bitmap, progress = 1f, stage = "Ready to crop") }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(running = false, error = t.message ?: t.toString(), stage = "Failed") }
            }
        }
    }

    /**
     * Crop the stitched panorama to [crop] (in bitmap pixel coordinates; null =
     * keep the whole image) and save it to the gallery.
     */
    fun confirmCrop(crop: Rect?) {
        val bitmap = _state.value.stitched ?: return
        _state.update { it.copy(running = true, stage = "Saving") }
        job = viewModelScope.launch {
            runCatching {
                val toSave = if (crop != null && isUsable(crop, bitmap)) {
                    withContext(Dispatchers.Default) {
                        Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
                    }
                } else {
                    bitmap
                }
                PanoramaEngine.save(getApplication(), toSave).also {
                    if (toSave !== bitmap) toSave.recycle()
                }
            }.onSuccess { saved ->
                _state.value.stitched?.recycle()
                _state.update { it.copy(running = false, stitched = null, result = saved, stage = "Done") }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(running = false, error = t.message ?: t.toString(), stage = "Failed") }
            }
        }
    }

    /** Discard the stitched result without saving (back to the picker). */
    fun discardStitched() {
        _state.value.stitched?.recycle()
        _state.update { it.copy(stitched = null, stage = "") }
    }

    private fun isUsable(crop: Rect, bitmap: Bitmap): Boolean =
        crop.left >= 0 && crop.top >= 0 &&
            crop.right <= bitmap.width && crop.bottom <= bitmap.height &&
            crop.width() >= 8 && crop.height() >= 8

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "image"
}
