package dev.ahmed.spotter.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmed.spotter.browser.DetectedItem
import dev.ahmed.spotter.browser.MediaKind
import dev.ahmed.spotter.browser.WebViewHolder
import dev.ahmed.spotter.engine.store.BookmarkEntity
import dev.ahmed.spotter.engine.store.TabEntity
import dev.ahmed.spotter.service.DownloadNotifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    holder: WebViewHolder,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val callbacks = remember(holder) { vm.callbacks(holder) }
    var addressField by remember { mutableStateOf("") }
    var showDetected by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf(mapOf<String, String>()) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The address bar must not keep focus (and the keyboard) after a page starts loading, or the keyboard
    // covers the "N media" button (audit B3).
    LaunchedEffect(ui.address) {
        if (addressField != ui.address) addressField = ui.address
        focusManager.clearFocus()
        keyboard?.hide()
    }
    LaunchedEffect(ui.message) {
        val msg = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearMessage()
    }
    ui.quality?.let { prompt ->
        AlertDialog(
            onDismissRequest = { vm.dismissQuality() },
            title = { Text("Quality") },
            text = {
                Column {
                    prompt.options.forEachIndexed { i, option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = i == prompt.selected, onClick = { vm.selectQuality(i) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = i == prompt.selected, onClick = { vm.selectQuality(i) })
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ vm.confirmQuality() }) { Text("Download") } },
            dismissButton = { TextButton({ vm.dismissQuality() }) { Text("Cancel") } },
        )
    }
    LaunchedEffect(ui.detected) {
        names = ui.detected.associate { it.url to (names[it.url] ?: it.suggestedName) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabStrip(
                tabs = ui.tabs,
                activeId = ui.activeTabId,
                onSelect = { vm.selectTab(it, holder) },
                onClose = { vm.closeTab(it, holder) },
                onNew = { vm.newTab() },
            )
            OutlinedTextField(
                value = addressField,
                onValueChange = { addressField = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                singleLine = true,
                placeholder = { Text("Search or URL") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    vm.submitAddress(addressField, holder)
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton({ vm.back(holder) }, enabled = ui.canBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                IconButton({ vm.forward(holder) }, enabled = ui.canForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                }
                IconButton({ vm.reload(holder) }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton({ vm.toggleDesktop(holder) }) {
                    Icon(
                        Icons.Default.Computer,
                        if (ui.desktop) "Desktop site on" else "Desktop site",
                        tint = if (ui.desktop) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton({ vm.toggleBookmark() }) {
                    Icon(
                        if (ui.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Bookmark",
                    )
                }
                IconButton({ showBookmarks = true }) { Icon(Icons.Default.Bookmark, "Bookmarks") }
                IconButton({ showHistory = true }) { Icon(Icons.Default.History, "History") }
            }
            if (ui.loading) {
                LinearProgressIndicator(
                    progress = { ui.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val active = ui.tabs.firstOrNull { it.id == ui.activeTabId }
                ui.tabs.forEach { tab ->
                    androidx.compose.runtime.key(tab.id) {
                        TabWebView(
                            tab = tab,
                            visible = tab.id == ui.activeTabId,
                            holder = holder,
                            callbacks = callbacks,
                            onReady = { vm.loadIfNeeded(tab, it) },
                        )
                    }
                }
                val wvUrl = active?.let { holder.get(it.id)?.url }
                val empty = active != null &&
                    active.url.isBlank() &&
                    addressField.isBlank() &&
                    (wvUrl.isNullOrBlank() || wvUrl == "about:blank")
                if (empty) {
                    NewTabPane(
                        bookmarks = ui.bookmarks,
                        onOpen = { vm.submitAddress(it, holder) },
                    )
                }
            }
        }
        if (ui.detected.isNotEmpty()) {
            TextButton(
                onClick = { showDetected = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Videocam, null)
                Spacer(Modifier.width(8.dp))
                Text("${ui.detected.size} media")
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showDetected) {
        DetectedMediaSheet(
            items = ui.detected,
            names = names,
            onName = { url, value -> names = names + (url to value) },
            onDownload = { item ->
                vm.download(item, names[item.url] ?: item.suggestedName)
                // Close the sheet: it used to stay open after Download (owner bug), which also allowed a duplicate
                // download by tapping again and left the HLS quality dialog stacked on top of it.
                showDetected = false
            },
            onDismiss = { showDetected = false },
        )
    }
    if (showBookmarks) {
        ListSheet(
            title = "Bookmarks",
            rows = ui.bookmarks.map { it.title to it.url },
            onOpen = {
                vm.submitAddress(it, holder)
                showBookmarks = false
            },
            onDismiss = { showBookmarks = false },
        )
    }
    if (showHistory) {
        ListSheet(
            title = "History",
            rows = ui.history.map { it.title to it.url },
            onOpen = {
                vm.submitAddress(it, holder)
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun TabStrip(
    tabs: List<TabEntity>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = tab.id == activeId,
                onClick = { onSelect(tab.id) },
                label = {
                    Text(
                        tab.title.ifBlank { "New tab" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(96.dp),
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        "Close tab",
                        modifier = Modifier.clickable { onClose(tab.id) },
                    )
                },
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onNew) { Icon(Icons.Default.Add, "New tab") }
    }
}

@Composable
private fun NewTabPane(bookmarks: List<BookmarkEntity>, onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
    ) {
        Text("New tab", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Type a URL or search in the bar. Bookmarks appear below.")
        Spacer(Modifier.height(16.dp))
        bookmarks.forEach { b ->
            Text(
                b.title.ifBlank { b.url },
                modifier = Modifier.fillMaxWidth().clickable { onOpen(b.url) }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TabWebView(
    tab: TabEntity,
    visible: Boolean,
    holder: WebViewHolder,
    callbacks: dev.ahmed.spotter.browser.WebViewCallbacks,
    onReady: (WebView) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            val frame = FrameLayout(ctx)
            val wv = holder.getOrCreate(tab.id, ctx, callbacks)
            (wv.parent as? ViewGroup)?.removeView(wv)
            frame.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            onReady(wv)
            frame
        },
        update = { frame ->
            val wv = holder.getOrCreate(tab.id, context, callbacks)
            if (wv.parent != frame) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                frame.removeAllViews()
                frame.addView(wv)
            }
            frame.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            onReady(wv)
        },
        modifier = if (visible) Modifier.fillMaxSize() else Modifier.height(0.dp).fillMaxWidth(),
        onRelease = { frame ->
            val wv = holder.get(tab.id)
            if (wv != null && wv.parent == frame) {
                frame.removeView(wv)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectedMediaSheet(
    items: List<DetectedItem>,
    names: Map<String, String>,
    onName: (String, String) -> Unit,
    onDownload: (DetectedItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Detected media", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            items(items.sortedBy { it.url.startsWith("blob:") }, key = { it.url }) { item ->
                DetectedRow(
                    item = item,
                    name = names[item.url] ?: item.suggestedName,
                    onName = { onName(item.url, it) },
                    onDownload = { onDownload(item) },
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetectedRow(
    item: DetectedItem,
    name: String,
    onName: (String) -> Unit,
    onDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val isBlob = item.url.startsWith("blob:")
        val host = runCatching { java.net.URI(item.url.removePrefix("blob:")).host }.getOrNull().orEmpty()
        Text(
            when {
                isBlob -> "In-page stream (cannot be downloaded directly)"
                item.kind == MediaKind.PLAYLIST -> "Stream playlist (HLS/DASH)"
                item.type.isNotBlank() && item.type != "media" && item.type != "stream" -> item.type
                else -> "Media file"
            } + if (host.isNotEmpty()) " · $host" else "",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            item.url,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        val size = item.size?.let { DownloadNotifier.formatBytes(it) }
        val bits = buildList {
            add(item.kind.name.lowercase())
            if (size != null) add(size)
            if (item.fromPlay) add("playing")
        }
        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("File name") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDownload, enabled = item.kind != MediaKind.PAGE_RESOLVER && !isBlob) {
                Text(if (isBlob) "Use the other entry" else "Download")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListSheet(
    title: String,
    rows: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            items(rows.size) { i ->
                val (label, url) = rows[i]
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 10.dp),
                ) {
                    Text(label.ifBlank { url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
