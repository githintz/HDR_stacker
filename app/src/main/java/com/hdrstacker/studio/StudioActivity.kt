package com.hdrstacker.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hdrstacker.MainActivity
import com.hdrstacker.panorama.PanoramaActivity
import com.hdrstacker.studio.cloud.CloudScreen
import com.hdrstacker.studio.cloud.CloudTopBar
import com.hdrstacker.studio.cloud.CloudViewModel
import com.hdrstacker.studio.edit.EditScreen
import com.hdrstacker.studio.edit.EditTopBar
import com.hdrstacker.studio.edit.EditViewModel
import com.hdrstacker.studio.edit.ExportDialog
import com.hdrstacker.studio.library.LibraryScreen
import com.hdrstacker.studio.library.LibraryTopBar
import com.hdrstacker.studio.library.LibraryViewModel
import com.hdrstacker.studio.library.SelectionActionBar
import kotlinx.coroutines.launch

/**
 * Single-activity shell for the whole app: Library, Edit, and Cloud tabs on a
 * bottom navigation bar. Merging tools (HDR today, panorama today, focus
 * stacking later) remain standalone activities launched from the library's
 * selection bar, so the shell never needs to know how they work internally.
 */
class StudioActivity : ComponentActivity() {
    private val libraryVm: LibraryViewModel by viewModels()
    private val editVm: EditViewModel by viewModels()
    private val cloudVm: CloudViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                StudioShell(
                    libraryVm = libraryVm,
                    editVm = editVm,
                    cloudVm = cloudVm,
                    onLaunchHdr = { startActivity(Intent(this, MainActivity::class.java)) },
                    onLaunchPanorama = {
                        startActivity(Intent(this, PanoramaActivity::class.java))
                    },
                )
            }
        }
    }
}

private enum class StudioTab(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Outlined.PhotoLibrary),
    EDIT("Edit", Icons.Outlined.Tune),
    CLOUD("Cloud", Icons.Outlined.Cloud),
}

@Composable
private fun StudioShell(
    libraryVm: LibraryViewModel,
    editVm: EditViewModel,
    cloudVm: CloudViewModel,
    onLaunchHdr: () -> Unit,
    onLaunchPanorama: () -> Unit,
) {
    val libraryState by libraryVm.state.collectAsStateWithLifecycle()
    val editState by editVm.state.collectAsStateWithLifecycle()
    val cloudState by cloudVm.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(StudioTab.LIBRARY) }
    var exportDialogOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showStub: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // Back clears an active selection first, then returns to the library tab,
    // and only then falls through to the system (leaving the app).
    BackHandler(enabled = tab != StudioTab.LIBRARY || libraryState.selectionMode) {
        if (tab == StudioTab.LIBRARY) libraryVm.clearSelection() else tab = StudioTab.LIBRARY
    }

    Scaffold(
        topBar = {
            when (tab) {
                StudioTab.LIBRARY -> LibraryTopBar(
                    state = libraryState,
                    onSelectAll = libraryVm::selectAllVisible,
                    onClearSelection = libraryVm::clearSelection,
                    onLoadSamples = libraryVm::loadSamplePhotos,
                    onClearLibrary = libraryVm::clearLibrary,
                )
                StudioTab.EDIT -> EditTopBar(
                    state = editState,
                    onBack = { tab = StudioTab.LIBRARY },
                    onExport = { exportDialogOpen = true },
                )
                StudioTab.CLOUD -> CloudTopBar()
            }
        },
        bottomBar = {
            if (tab == StudioTab.LIBRARY && libraryState.selectionMode) {
                SelectionActionBar(
                    selectedCount = libraryState.selectedCount,
                    onMergeHdr = {
                        libraryVm.clearSelection()
                        onLaunchHdr()
                    },
                    onPanorama = {
                        libraryVm.clearSelection()
                        onLaunchPanorama()
                    },
                    onFocusStack = {
                        showStub(
                            "Focus stacking isn't implemented yet — these " +
                                "${libraryState.selectedCount} photos will feed it once it lands.",
                        )
                    },
                )
            } else {
                NavigationBar {
                    StudioTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            when (tab) {
                StudioTab.LIBRARY -> LibraryScreen(
                    state = libraryState,
                    onFilter = libraryVm::setFilter,
                    onLoadSamples = libraryVm::loadSamplePhotos,
                    onPhotoClick = { photo ->
                        if (libraryState.selectionMode) {
                            libraryVm.toggleSelection(photo.id)
                        } else {
                            editVm.open(photo)
                            tab = StudioTab.EDIT
                        }
                    },
                    onPhotoLongClick = { photo -> libraryVm.toggleSelection(photo.id) },
                )
                StudioTab.EDIT -> EditScreen(
                    state = editState,
                    onPreview = editVm::preview,
                    onCommit = editVm::commit,
                    onUndo = editVm::undo,
                    onRedo = editVm::redo,
                    onReset = editVm::reset,
                    onGoToLibrary = { tab = StudioTab.LIBRARY },
                )
                StudioTab.CLOUD -> CloudScreen(
                    state = cloudState,
                    onServerUrl = cloudVm::setServerUrl,
                    onUsername = cloudVm::setUsername,
                    onPassword = cloudVm::setPassword,
                    onConnect = cloudVm::connect,
                    onDisconnect = cloudVm::disconnect,
                    onOpenFolder = cloudVm::openFolder,
                    onNavigateTo = cloudVm::navigateTo,
                    onStub = showStub,
                )
            }
        }
    }

    if (exportDialogOpen) {
        ExportDialog(
            photoName = editState.photo?.name ?: "",
            onDismiss = { exportDialogOpen = false },
            onExport = {
                exportDialogOpen = false
                showStub("Export isn't wired up yet — it lands with the edit engine.")
            },
        )
    }
}
