package com.hdrstacker.studio.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CloudStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class CloudUiState(
    val status: CloudStatus = CloudStatus.DISCONNECTED,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val error: String? = null,
    /** Breadcrumb into the demo folder tree while connected. */
    val path: List<String> = emptyList(),
)

/**
 * Pluggable connection backend. [DemoCloudConnector] fakes a handshake; the
 * real WebDAV client (and later Google Drive) implements this same interface,
 * so swapping it in does not touch the view model or the screen.
 */
fun interface CloudConnector {
    suspend fun connect(serverUrl: String, username: String, password: String): Result<Unit>
}

/** Stand-in that "connects" after a short delay without any network I/O. */
object DemoCloudConnector : CloudConnector {
    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<Unit> {
        delay(1500)
        return Result.success(Unit)
    }
}

class CloudViewModel : ViewModel() {
    private val connector: CloudConnector = DemoCloudConnector

    private val _state = MutableStateFlow(CloudUiState())
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    fun setServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value, error = null)
    }

    fun setUsername(value: String) {
        _state.value = _state.value.copy(username = value, error = null)
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun connect() {
        val current = _state.value
        if (current.status == CloudStatus.CONNECTING) return
        if (current.serverUrl.isBlank()) {
            _state.value = current.copy(error = "Enter your server URL first")
            return
        }
        _state.value = current.copy(status = CloudStatus.CONNECTING, error = null)
        viewModelScope.launch {
            val result = connector.connect(current.serverUrl, current.username, current.password)
            _state.value = _state.value.copy(
                status = if (result.isSuccess) CloudStatus.CONNECTED else CloudStatus.DISCONNECTED,
                error = result.exceptionOrNull()?.message,
                path = emptyList(),
            )
        }
    }

    fun disconnect() {
        _state.value = _state.value.copy(status = CloudStatus.DISCONNECTED, path = emptyList())
    }

    fun openFolder(name: String) {
        _state.value = _state.value.copy(path = _state.value.path + name)
    }

    /** Jumps to a breadcrumb position; depth 0 is the server root. */
    fun navigateTo(depth: Int) {
        _state.value = _state.value.copy(path = _state.value.path.take(depth))
    }
}

/** Fake folder listing shown in the browser until a real client exists. */
object DemoCloudTree {
    data class Entry(val name: String, val isFolder: Boolean, val detail: String)

    private val tree: Map<String, List<Entry>> = mapOf(
        "" to listOf(
            Entry("Photos", true, "3 folders"),
            Entry("Camera Uploads", true, "128 files"),
            Entry("Exports", true, "12 files"),
        ),
        "Photos" to listOf(
            Entry("Alps 2025", true, "42 files"),
            Entry("Portraits", true, "18 files"),
            Entry("Test Brackets", true, "9 files"),
        ),
        "Photos/Alps 2025" to listOf(
            Entry("DSC_0412.NEF", false, "24.1 MB"),
            Entry("DSC_0413.NEF", false, "24.3 MB"),
            Entry("DSC_0414.NEF", false, "24.0 MB"),
            Entry("ridge_pano.jpg", false, "8.2 MB"),
        ),
        "Photos/Portraits" to listOf(
            Entry("IMG_2201.HEIC", false, "3.1 MB"),
            Entry("IMG_2202.HEIC", false, "3.4 MB"),
        ),
        "Photos/Test Brackets" to listOf(
            Entry("bracket_-2ev.NEF", false, "23.8 MB"),
            Entry("bracket_0ev.NEF", false, "24.2 MB"),
            Entry("bracket_+2ev.NEF", false, "24.5 MB"),
        ),
        "Camera Uploads" to listOf(
            Entry("2024-11-02_142233.NEF", false, "24.4 MB"),
            Entry("2024-11-02_142240.NEF", false, "24.4 MB"),
            Entry("2024-11-02_142251.NEF", false, "24.3 MB"),
        ),
        "Exports" to listOf(
            Entry("night-lake-final.jpg", false, "9.8 MB"),
            Entry("milkyway_stack_v2.tif", false, "112 MB"),
            Entry("harbor_pano.jpg", false, "14.6 MB"),
        ),
    )

    fun list(path: List<String>): List<Entry> =
        tree[path.joinToString("/")] ?: emptyList()
}
