package com.hdrstacker.studio.library

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hdrstacker.studio.PhotoItem
import com.hdrstacker.studio.PhotoSource
import com.hdrstacker.studio.QualityFlag
import com.hdrstacker.studio.Thumbnails

private const val GRID_THUMB_PX = 256

/** Library top bar; switches to a contextual bar while photos are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(
    state: LibraryUiState,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.selectionMode) {
        TopAppBar(
            title = { Text("${state.selectedCount} selected") },
            navigationIcon = {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                }
            },
            actions = {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
    } else {
        TopAppBar(
            title = { Text("Library") },
            actions = {
                IconButton(onClick = onRefresh, enabled = state.hasPermission && !state.scanning) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rescan library")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onFilter: (SourceFilter) -> Unit,
    onRequestAccess: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit,
    onPhotoLongClick: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SourceFilter.entries.forEachIndexed { index, filter ->
                SegmentedButton(
                    selected = state.filter == filter,
                    onClick = { onFilter(filter) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SourceFilter.entries.size,
                    ),
                ) { Text(filter.label) }
            }
        }

        val photos = state.visiblePhotos
        when {
            !state.hasPermission -> PermissionEmptyState(
                onRequestAccess = onRequestAccess,
                modifier = Modifier.weight(1f),
            )
            state.scanning && !state.scanned -> Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            photos.isEmpty() -> EmptyLibrary(
                filter = state.filter,
                modifier = Modifier.weight(1f),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 3.dp, bottom = 96.dp),
            ) {
                items(photos, key = { it.id }) { photo ->
                    PhotoTile(
                        photo = photo,
                        selectionMode = state.selectionMode,
                        selected = photo.id in state.selectedIds,
                        onClick = { onPhotoClick(photo) },
                        onLongClick = { onPhotoLongClick(photo) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    photo: PhotoItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, photo.uri) {
        value = Thumbnails.load(context, photo.uri, GRID_THUMB_PX)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }
        if (selectionMode) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp),
            )
        }
        if (photo.source == PhotoSource.CLOUD) {
            TileBadge(
                icon = Icons.Outlined.CloudQueue,
                contentDescription = "Stored in cloud",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (photo.isDuplicate) {
                TileBadge(Icons.Outlined.ContentCopy, contentDescription = "Possible duplicate")
            }
            if (photo.quality != QualityFlag.NONE) {
                TileBadge(Icons.Outlined.BlurOn, contentDescription = "Focus warning")
            }
        }
        photo.tags.firstOrNull()?.let { tag ->
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                    .widthIn(max = 72.dp),
            )
        }
    }
}

@Composable
private fun TileBadge(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .padding(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(13.dp),
            tint = Color.White,
        )
    }
}

@Composable
private fun PermissionEmptyState(
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Let Photo Studio see your photos", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your library is built from the photos on this device, sorted by " +
                "capture date. Everything stays stored locally — nothing is " +
                "uploaded unless you connect a cloud provider.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestAccess) { Text("Allow photo access") }
    }
}

@Composable
private fun EmptyLibrary(
    filter: SourceFilter,
    modifier: Modifier = Modifier,
) {
    val cloud = filter == SourceFilter.CLOUD
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (cloud) Icons.Outlined.CloudOff else Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (cloud) "No cloud photos" else "No photos found",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (cloud) {
                "Connect a provider in the Cloud tab to browse cloud photos " +
                    "here. Until then, everything is stored locally on this device."
            } else {
                "Photos you take or import on this device will appear here, " +
                    "newest first."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Replaces the bottom navigation bar while a selection is active. HDR and
 * panorama launch the real feature activities with the selected photos; focus
 * stacking is a stub until that stage lands.
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    onMergeHdr: () -> Unit,
    onPanorama: () -> Unit,
    onFocusStack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(top = 6.dp, bottom = 8.dp),
        ) {
            if (selectedCount < 2) {
                Text(
                    "Select at least 2 photos to combine them",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                ActionItem(
                    icon = Icons.Default.Landscape,
                    label = "Merge HDR",
                    enabled = selectedCount >= 2,
                    onClick = onMergeHdr,
                    modifier = Modifier.weight(1f),
                )
                ActionItem(
                    icon = Icons.Default.Panorama,
                    label = "Panorama",
                    enabled = selectedCount >= 2,
                    onClick = onPanorama,
                    modifier = Modifier.weight(1f),
                )
                ActionItem(
                    icon = Icons.Default.CenterFocusStrong,
                    label = "Focus stack",
                    enabled = selectedCount >= 2,
                    onClick = onFocusStack,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
    }
}
