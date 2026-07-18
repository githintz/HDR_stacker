package com.hdrstacker.studio.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudTopBar() {
    TopAppBar(title = { Text("Cloud storage") })
}

@Composable
fun CloudScreen(
    state: CloudUiState,
    onServerUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onNavigateTo: (Int) -> Unit,
    onStub: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, onDisconnect)
        Text("Providers", style = MaterialTheme.typography.titleSmall)
        WebDavCard(state, onServerUrl, onUsername, onPassword, onConnect)
        GoogleDriveCard(onStub)
        if (state.status == CloudStatus.CONNECTED) {
            FolderBrowserCard(state, onOpenFolder, onNavigateTo, onStub)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(state: CloudUiState, onDisconnect: () -> Unit) {
    val (dotColor, title, detail) = when (state.status) {
        CloudStatus.DISCONNECTED -> Triple(
            MaterialTheme.colorScheme.outline,
            "Not connected",
            "Connect a provider below to browse and sync photos",
        )
        CloudStatus.CONNECTING -> Triple(
            MaterialTheme.colorScheme.tertiary,
            "Connecting…",
            state.serverUrl,
        )
        CloudStatus.CONNECTED -> Triple(
            Color(0xFF6DD58C),
            "Connected (demo)",
            state.serverUrl,
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.status == CloudStatus.CONNECTED) {
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun WebDavCard(
    state: CloudUiState,
    onServerUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val editable = state.status == CloudStatus.DISCONNECTED
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("WebDAV", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nextcloud, ownCloud, or any WebDAV server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.status == CloudStatus.CONNECTED) {
                Text(
                    "Connected as ${state.username.ifBlank { "(no username)" }}. " +
                        "This is a demo connection — no data has left the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = onServerUrl,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    label = { Text("Server URL") },
                    placeholder = { Text("https://cloud.example.com/remote.php/dav") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsername,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    label = { Text("Username") },
                    singleLine = true,
                )
                var showPassword by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    label = { Text("App password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                )
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.status == CloudStatus.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Contacting server… (simulated)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test & connect") }
                }
            }
        }
    }
}

@Composable
private fun GoogleDriveCard(onStub: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable { onStub("Google Drive support is planned after WebDAV.") }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Planned after WebDAV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Coming soon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FolderBrowserCard(
    state: CloudUiState,
    onOpenFolder: (String) -> Unit,
    onNavigateTo: (Int) -> Unit,
    onStub: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Files",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Demo data",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onNavigateTo(0) }) { Text("Root") }
                state.path.forEachIndexed { index, segment ->
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onNavigateTo(index + 1) }) { Text(segment) }
                }
            }
            HorizontalDivider()
            val entries = DemoCloudTree.list(state.path)
            if (entries.isEmpty()) {
                Text(
                    "Empty folder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            entries.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isFolder) {
                                onOpenFolder(entry.name)
                            } else {
                                onStub("Download & preview arrive with cloud sync.")
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (entry.isFolder) Icons.Outlined.Folder else Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry.isFolder) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = { onStub("Upload arrives with cloud sync.") },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Upload photo")
            }
        }
    }
}
