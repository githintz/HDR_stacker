package com.hdrstacker.studio.edit

import androidx.lifecycle.ViewModel
import com.hdrstacker.studio.PhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One snapshot of every adjustment slider. The future edit engine consumes a
 * value of this type to render the preview and the export; the UI only ever
 * reads and writes it.
 */
data class EditValues(
    val exposure: Float = 0f,        // EV, -5..+5
    val temperature: Float = 5500f,  // Kelvin, 2000..10000
    val tint: Float = 0f,            // -100..+100 (green..magenta)
    val contrast: Float = 0f,        // -100..+100
    val highlights: Float = 0f,      // -100..+100
    val shadows: Float = 0f,         // -100..+100
    val saturation: Float = 0f,      // -100..+100
)

data class EditUiState(
    val photo: PhotoItem? = null,
    val values: EditValues = EditValues(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/**
 * Non-destructive edit stack for one photo: slider drags update [EditUiState.values]
 * live, and each released drag commits a history snapshot that undo/redo walk
 * through. Rendering the values onto pixels is the (not yet built) edit engine's
 * job — this class stays as-is when that lands.
 */
class EditViewModel : ViewModel() {
    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    private val history = mutableListOf(EditValues())
    private var historyIndex = 0

    /** Opens a photo for editing, starting a fresh edit history. */
    fun open(photo: PhotoItem) {
        history.clear()
        history.add(EditValues())
        historyIndex = 0
        _state.value = EditUiState(photo = photo)
    }

    /** Live update while a slider is being dragged; not yet a history entry. */
    fun preview(values: EditValues) {
        _state.value = _state.value.copy(values = values)
    }

    /** Commits the current values as an undoable history step. */
    fun commit() {
        val current = _state.value.values
        if (current == history[historyIndex]) return
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        history.add(current)
        historyIndex++
        publish()
    }

    fun undo() {
        if (historyIndex > 0) {
            historyIndex--
            publish()
        }
    }

    fun redo() {
        if (historyIndex < history.lastIndex) {
            historyIndex++
            publish()
        }
    }

    /** Resets all sliders to defaults, as a single undoable step. */
    fun reset() {
        preview(EditValues())
        commit()
    }

    private fun publish() {
        _state.value = _state.value.copy(
            values = history[historyIndex],
            canUndo = historyIndex > 0,
            canRedo = historyIndex < history.lastIndex,
        )
    }
}
