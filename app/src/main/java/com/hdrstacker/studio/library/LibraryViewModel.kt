package com.hdrstacker.studio.library

import androidx.lifecycle.ViewModel
import com.hdrstacker.studio.PhotoItem
import com.hdrstacker.studio.PhotoSource
import com.hdrstacker.studio.SampleLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Grid filter between everything, on-device photos, and cloud photos. */
enum class SourceFilter(val label: String) { ALL("All"), LOCAL("Local"), CLOUD("Cloud") }

data class LibraryUiState(
    val photos: List<PhotoItem> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val selectedIds: Set<Long> = emptySet(),
) {
    val visiblePhotos: List<PhotoItem>
        get() = when (filter) {
            SourceFilter.ALL -> photos
            SourceFilter.LOCAL -> photos.filter { it.source == PhotoSource.LOCAL }
            SourceFilter.CLOUD -> photos.filter { it.source == PhotoSource.CLOUD }
        }

    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedCount: Int get() = selectedIds.size
}

/**
 * Holds grid contents and multi-select state. Photos currently come from
 * [SampleLibrary]; the future media backend replaces [loadSamplePhotos] with a
 * MediaStore/cloud scan feeding the same [LibraryUiState].
 */
class LibraryViewModel : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    fun loadSamplePhotos() {
        _state.value = _state.value.copy(photos = SampleLibrary.photos(), selectedIds = emptySet())
    }

    fun clearLibrary() {
        _state.value = _state.value.copy(photos = emptyList(), selectedIds = emptySet())
    }

    fun setFilter(filter: SourceFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    fun toggleSelection(id: Long) {
        val selected = _state.value.selectedIds
        _state.value = _state.value.copy(
            selectedIds = if (id in selected) selected - id else selected + id,
        )
    }

    fun selectAllVisible() {
        _state.value = _state.value.copy(
            selectedIds = _state.value.visiblePhotos.map { it.id }.toSet(),
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }
}
