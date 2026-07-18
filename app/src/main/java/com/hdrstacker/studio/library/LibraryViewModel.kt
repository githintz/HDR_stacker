package com.hdrstacker.studio.library

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hdrstacker.studio.PhotoItem
import com.hdrstacker.studio.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grid filter between everything, on-device photos, and cloud photos. */
enum class SourceFilter(val label: String) { ALL("All"), LOCAL("Local"), CLOUD("Cloud") }

data class LibraryUiState(
    val photos: List<PhotoItem> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val selectedIds: Set<Long> = emptySet(),
    val hasPermission: Boolean = false,
    val scanning: Boolean = false,
    val scanned: Boolean = false,
) {
    val visiblePhotos: List<PhotoItem>
        get() = when (filter) {
            SourceFilter.ALL -> photos
            SourceFilter.LOCAL -> photos.filter { it.source == PhotoSource.LOCAL }
            SourceFilter.CLOUD -> photos.filter { it.source == PhotoSource.CLOUD }
        }

    val selectedPhotos: List<PhotoItem>
        get() = photos.filter { it.id in selectedIds }

    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedCount: Int get() = selectedIds.size
}

/**
 * Library contents and multi-select state. Photos come from the device's
 * MediaStore, newest capture first, and stay on-device: nothing is uploaded
 * unless a cloud provider is connected (cloud-sourced photos would arrive
 * with [PhotoSource.CLOUD] once sync exists).
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /**
     * Called by the activity whenever the photo-access permission state is
     * known (startup, resume, permission dialog result). Granting triggers a
     * rescan so the grid stays current with the device gallery.
     */
    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) refresh()
    }

    fun refresh() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) { scanDevicePhotos(getApplication()) }
            _state.update { current ->
                current.copy(
                    photos = photos,
                    scanning = false,
                    scanned = true,
                    // Drop selections pointing at photos that no longer exist.
                    selectedIds = current.selectedIds intersect photos.map { it.id }.toSet(),
                )
            }
        }
    }

    fun setFilter(filter: SourceFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun toggleSelection(id: Long) {
        _state.update {
            it.copy(
                selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id,
            )
        }
    }

    fun selectAllVisible() {
        _state.update { it.copy(selectedIds = it.visiblePhotos.map { p -> p.id }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    private fun scanDevicePhotos(context: Context): List<PhotoItem> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val photos = mutableListOf<PhotoItem>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val taken = cursor.getLong(takenCol)
                // DATE_TAKEN (ms) is missing on some files; fall back to
                // DATE_ADDED (seconds) so sorting stays sane.
                val added = cursor.getLong(addedCol) * 1000
                photos += PhotoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameCol) ?: "IMG_$id",
                    source = PhotoSource.LOCAL,
                    dateTakenMillis = if (taken > 0) taken else added,
                )
            }
        }
        return photos.sortedByDescending { it.dateTakenMillis }
    }
}
