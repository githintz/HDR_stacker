package com.hdrstacker.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    private val requestPhotoAccess =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            libraryVm.onPermissionResult(hasPhotoPermission())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                StudioShell(
                    libraryVm = libraryVm,
                    editVm = editVm,
                    cloudVm = cloudVm,
                    onRequestPhotoAccess = { requestPhotoAccess.launch(photoPermissions()) },
                    onLaunchHdr = { uris ->
                        launchTool(MainActivity::class.java, MainActivity.EXTRA_SOURCE_URIS, uris)
                    },
                    onLaunchPanorama = { uris ->
                        launchTool(
                            PanoramaActivity::class.java,
                            PanoramaActivity.EXTRA_SOURCE_URIS,
                            uris,
                        )
                    },
                )

                // AddressSanitizer report catcher (asan build type). If the
                // native decoder tripped ASan on the previous run, the report
                // was written to filesDir by wrap.sh's log_path; surface it so
                // it can be copied out without adb. No-op in normal builds.
                var asanReport by remember { mutableStateOf(readAsanReports()) }
                asanReport?.let { report ->
                    AsanReportDialog(
                        report = report,
                        onDismiss = { asanReport = null },
                        onDelete = {
                            deleteAsanReports()
                            asanReport = null
                        },
                    )
                }
            }
        }
    }

    private fun readAsanReports(): String? {
        val files = filesDir.listFiles { f -> f.isFile && f.name.startsWith("asan_report") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (files.isEmpty()) return null
        val text = files.joinToString("\n\n========\n\n") { f ->
            "== ${f.name} ==\n" + runCatching { f.readText() }.getOrElse { "(unreadable: $it)" }
        }
        // Keep the dialog and clipboard payload bounded; reports are ~10-100 KB.
        return text.take(400_000)
    }

    private fun deleteAsanReports() {
        filesDir.listFiles { f -> f.isFile && f.name.startsWith("asan_report") }
            ?.forEach { it.delete() }
    }

    override fun onResume() {
        super.onResume()
        // Rescan on every return to the shell so results just saved by the HDR
        // or panorama tools show up in the grid immediately.
        libraryVm.onPermissionResult(hasPhotoPermission())
    }

    private fun launchTool(activity: Class<*>, extraName: String, uris: List<Uri>) {
        startActivity(
            Intent(this, activity).putParcelableArrayListExtra(extraName, ArrayList(uris)),
        )
    }

    private fun hasPhotoPermission(): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        return when {
            // API 34+: full access, or the user granted a photo subset.
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT == 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun photoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT == 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
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
    onRequestPhotoAccess: () -> Unit,
    onLaunchHdr: (List<Uri>) -> Unit,
    onLaunchPanorama: (List<Uri>) -> Unit,
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
                    onRefresh = libraryVm::refresh,
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
                        val uris = libraryState.selectedPhotos.map { it.uri }
                        libraryVm.clearSelection()
                        onLaunchHdr(uris)
                    },
                    onPanorama = {
                        val uris = libraryState.selectedPhotos.map { it.uri }
                        libraryVm.clearSelection()
                        onLaunchPanorama(uris)
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
                    onRequestAccess = onRequestPhotoAccess,
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

/**
 * Shows a captured AddressSanitizer report with a copy-to-clipboard action, so
 * the crash details can be shared for analysis without needing adb access.
 */
@Composable
private fun AsanReportDialog(
    report: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Native crash report captured") },
        text = {
            Column {
                Text(
                    "AddressSanitizer caught a native memory error during the " +
                        "last run. Copy the report and share it for analysis.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                Text("Copy report")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
