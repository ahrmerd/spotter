package dev.ahmed.spotter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import dev.ahmed.spotter.engine.Categories
import dev.ahmed.spotter.engine.EngineErrors
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.hls.HlsProgress
import dev.ahmed.spotter.service.DownloadNotifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    vm: DownloadsViewModel,
    shareUrl: String? = null,
    clipboardUrl: String? = null,
    onShareConsumed: () -> Unit = {},
    onClipboardConsumed: () -> Unit = {},
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by vm.pendingUrl.collectAsStateWithLifecycle()
    val message by vm.userMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DownloadUi?>(null) }
    var removeTarget by remember { mutableStateOf<DownloadUi?>(null) }
    var clipboardPrompt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shareUrl) {
        if (!shareUrl.isNullOrBlank()) {
            addPrefill = shareUrl
            showAdd = true
            onShareConsumed()
        }
    }
    LaunchedEffect(clipboardUrl) {
        if (!clipboardUrl.isNullOrBlank()) {
            clipboardPrompt = clipboardUrl
            onClipboardConsumed()
        }
    }
    LaunchedEffect(pending) {
        val url = pending ?: return@LaunchedEffect
        addPrefill = url
        showAdd = true
        vm.consumePendingUrl()
    }
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    IconButton({ showSearch = !showSearch }) { Icon(Icons.Default.Search, "Search") }
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(settings.wifiOnly, { vm.setWifiOnly(it) })
                                    Text("Wi-Fi only")
                                }
                            },
                            onClick = {
                                vm.setWifiOnly(!settings.wifiOnly)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(settings.resumeOnBoot, { vm.setResumeOnBoot(it) })
                                    Text("Resume on boot")
                                }
                            },
                            onClick = {
                                vm.setResumeOnBoot(!settings.resumeOnBoot)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Pause all") },
                            onClick = {
                                vm.pauseAll()
                                menu = false
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton({
                addPrefill = ""
                showAdd = true
            }) { Icon(Icons.Default.Add, "Add URL") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (showSearch) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { vm.search.value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    label = { Text("Search") },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton({ vm.search.value = "" }) { Icon(Icons.Default.Close, "Clear") }
                        }
                    },
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Categories.FILTERS) { cat ->
                    FilterChip(
                        selected = filter == cat,
                        onClick = { vm.filter.value = cat },
                        label = { Text(cat) },
                    )
                }
            }
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No downloads", style = MaterialTheme.typography.bodyLarge)
                    Text("Tap + to add a URL", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.record.id }) { ui ->
                        DownloadRow(
                            ui = ui,
                            onPause = { vm.pause(ui.record.id) },
                            onResume = { vm.resume(ui.record.id) },
                            onCancel = { vm.cancel(ui.record.id) },
                            onRetry = { vm.retry(ui.record.id) },
                            onRemove = { removeTarget = ui },
                            onRename = { renameTarget = ui },
                            onOpen = { FileIntents.open(context, ui.record) },
                            onShare = { FileIntents.share(context, ui.record) },
                            onDeleteFile = { vm.deleteFile(ui.record.id) },
                            onCopyError = {
                                val text = vm.copyErrorText(ui.record)
                                val clip = context.getSystemService(ClipboardManager::class.java)
                                clip?.setPrimaryClip(ClipData.newPlainText("Spotter error", text))
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddUrlDialog(
            initial = addPrefill,
            onDismiss = { showAdd = false },
            onConfirm = { url, name ->
                vm.enqueue(url, name)
                showAdd = false
            },
        )
    }
    renameTarget?.let { target ->
        RenameDialog(
            initial = target.record.filename,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.rename(target.record.id, name)
                renameTarget = null
            },
        )
    }
    removeTarget?.let { target ->
        RemoveDialog(
            filename = target.record.filename.ifBlank { displayFilename(target.record.url) },
            canDeleteFile = target.record.state == DownloadState.DONE && target.record.outputPath.isNotEmpty(),
            onDismiss = { removeTarget = null },
            onRemove = { delete ->
                vm.remove(target.record.id, delete)
                removeTarget = null
            },
        )
    }
    clipboardPrompt?.let { url ->
        AlertDialog(
            onDismissRequest = { clipboardPrompt = null },
            title = { Text("Download from clipboard?") },
            text = { Text(url) },
            confirmButton = {
                TextButton({
                    addPrefill = url
                    showAdd = true
                    clipboardPrompt = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ clipboardPrompt = null }) { Text("Not now") } },
        )
    }
}

@Composable
private fun DownloadRow(
    ui: DownloadUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDeleteFile: () -> Unit,
    onCopyError: () -> Unit,
) {
    val rec = ui.record
    val progress = if (rec.size > 0) (rec.downloaded.toFloat() / rec.size.toFloat()).coerceIn(0f, 1f) else 0f
    val indeterminate = rec.state == DownloadState.RUNNING && rec.size <= 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            rec.filename.ifBlank { displayFilename(rec.url) },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        if (indeterminate) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            statusLine(ui),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when (rec.state) {
                DownloadState.RUNNING -> {
                    IconButton(onPause) { Icon(Icons.Default.Pause, "Pause") }
                    IconButton(onCancel) { Icon(Icons.Default.Stop, "Cancel") }
                }
                DownloadState.QUEUED -> {
                    IconButton(onPause) { Icon(Icons.Default.Pause, "Pause") }
                    IconButton(onCancel) { Icon(Icons.Default.Stop, "Cancel") }
                }
                DownloadState.PAUSED -> {
                    IconButton(onResume) { Icon(Icons.Default.PlayArrow, "Resume") }
                    IconButton(onCancel) { Icon(Icons.Default.Stop, "Cancel") }
                }
                DownloadState.ERROR, DownloadState.NEEDS_REFRESH -> {
                    IconButton(onRetry) { Icon(Icons.Default.Refresh, "Retry") }
                    IconButton(onCopyError) { Icon(Icons.Default.ContentCopy, "Copy error") }
                    IconButton(onRemove) { Icon(Icons.Default.Delete, "Remove") }
                }
                DownloadState.DONE -> {
                    TextButton(onOpen) { Text("Open") }
                    IconButton(onShare) { Icon(Icons.Default.Share, "Share") }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onRename) { Icon(Icons.Default.Edit, "Rename") }
            if (rec.state == DownloadState.DONE) {
                IconButton(onDeleteFile) { Icon(Icons.Default.Delete, "Delete file") }
            }
            if (rec.state != DownloadState.ERROR && rec.state != DownloadState.NEEDS_REFRESH) {
                IconButton(onRemove) { Icon(Icons.Default.Close, "Remove") }
            }
        }
    }
}

private fun statusLine(ui: DownloadUi): String {
    val rec = ui.record
    val bytes = when {
        HlsProgress.isSegmentMode(rec) && rec.size > 0 -> HlsProgress.line(rec.downloaded, rec.size)
        rec.size > 0 -> "${DownloadNotifier.formatBytes(rec.downloaded)} / ${DownloadNotifier.formatBytes(rec.size)}"
        else -> DownloadNotifier.formatBytes(rec.downloaded)
    }
    val speed = if (ui.speedBps > 0 && rec.state == DownloadState.RUNNING) {
        if (HlsProgress.isSegmentMode(rec)) " · ${ui.speedBps} seg/s"
        else " · ${DownloadNotifier.formatBytes(ui.speedBps)}/s"
    } else {
        ""
    }
    val eta = ui.etaSeconds?.let { " · ${formatEta(it)}" }.orEmpty()
    val state = when (rec.state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.RUNNING -> if (rec.size <= 0 && rec.downloaded > 0) "Downloading (size unknown)" else "Downloading"
        DownloadState.PAUSED -> if (!rec.ranged && rec.downloaded > 0) "Paused · resume restarts from 0" else "Paused"
        DownloadState.DONE -> "Done${rec.error?.let { " · ${EngineErrors.userMessage(it)}" } ?: ""}"
        DownloadState.ERROR -> "Error${rec.error?.let { ": ${EngineErrors.userMessage(it)}" } ?: ""}"
        DownloadState.NEEDS_REFRESH -> "Needs refresh${rec.error?.let { ": ${EngineErrors.userMessage(it)}" } ?: ""}"
    }
    return "$state · $bytes$speed$eta"
}

private fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}s left"
    if (seconds < 3600) return "${seconds / 60}m left"
    return "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
}

@Composable
private fun AddUrlDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var url by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add download") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Filename (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onConfirm(url, name) }, enabled = url.isNotBlank()) { Text("Download") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoveDialog(
    filename: String,
    canDeleteFile: Boolean,
    onDismiss: () -> Unit,
    onRemove: (Boolean) -> Unit,
) {
    var deleteFile by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove download") },
        text = {
            Column {
                Text("Remove “$filename” from the list?")
                if (canDeleteFile) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteFile, { deleteFile = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also delete the file")
                    }
                }
            }
        },
        confirmButton = { TextButton({ onRemove(deleteFile) }) { Text("Remove") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun displayFilename(url: String): String {
    val path = dev.ahmed.spotter.engine.Filenames.urlPathName(url)
    if (path.isNotBlank()) return path
    return url.substringAfter("://").substringBefore('/').ifBlank { url }
}
