package dev.ahmed.spotter.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmed.spotter.browser.AddressBar
import dev.ahmed.spotter.browser.DetectedItem
import dev.ahmed.spotter.browser.MediaKind
import dev.ahmed.spotter.browser.WebViewHolder
import dev.ahmed.spotter.engine.store.BookmarkEntity
import dev.ahmed.spotter.engine.store.TabEntity
import dev.ahmed.spotter.service.DownloadNotifier
import dev.ahmed.spotter.ui.components.SpotterTopBar
import dev.ahmed.spotter.ui.theme.SpotterTheme

/** At most this many WebViews stay alive; the rest are destroyed and reloaded from the stored URL when needed. */
private const val MAX_LIVE_WEBVIEWS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    holder: WebViewHolder,
    onExit: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val callbacks = remember(holder) { vm.callbacks(holder) }
    val context = LocalContext.current
    var addressField by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showDetected by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var linkMenu by remember { mutableStateOf<String?>(null) }
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var live by remember { mutableStateOf(listOf<Long>()) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun stopEditing() {
        editing = false
        vm.clearSuggestions()
        focusManager.clearFocus()
        keyboard?.hide()
    }

    // Most recently used WebViews stay alive; older ones are destroyed so 200 tabs do not mean 200 renderers.
    LaunchedEffect(ui.activeTabId, ui.tabs) {
        val ids = ui.tabs.map { it.id }.toSet()
        var next = (live.filter { it in ids && it != ui.activeTabId } + ui.activeTabId).filter { it != 0L }
        while (next.size > MAX_LIVE_WEBVIEWS) {
            holder.destroy(next.first())
            next = next.drop(1)
        }
        live = next
    }
    LaunchedEffect(ui.address) {
        if (!editing) addressField = ui.address
        else stopEditing()
    }
    LaunchedEffect(ui.message) {
        val msg = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearMessage()
    }
    LaunchedEffect(ui.detected) {
        names = ui.detected.associate { it.url to (names[it.url] ?: it.suggestedName) }
    }

    // Back: close the topmost thing, then go back in the page, then return to Spotter.
    BackHandler {
        when {
            showTabs -> showTabs = false
            editing -> stopEditing()
            ui.canBack -> vm.back(holder)
            else -> onExit()
        }
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
    linkMenu?.let { link ->
        AlertDialog(
            onDismissRequest = { linkMenu = null },
            title = { Text(AddressBar.hostOf(link), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        "Open in new tab",
                        Modifier.fillMaxWidth().clickable { vm.openInNewTab(link); linkMenu = null }.padding(vertical = 12.dp),
                    )
                    Text(
                        "Copy link",
                        Modifier.fillMaxWidth().clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("link", link))
                            linkMenu = null
                        }.padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton({ linkMenu = null }) { Text("Close") } },
        )
    }

    val colors = SpotterTheme.colors
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            SpotterTopBar {
                IconButton(onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Return to Spotter") }
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.addressPill)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (editing || !AddressBar.isSecure(ui.address)) Icons.Default.Search else Icons.Default.Lock,
                        null,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = if (editing) addressField else AddressBar.hostOf(ui.address),
                        onValueChange = { addressField = it; vm.typing(it) },
                        modifier = Modifier.weight(1f).onFocusChanged {
                            if (it.isFocused && !editing) {
                                editing = true
                                addressField = ui.address
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onTopBar),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.onTopBar),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                        keyboardActions = KeyboardActions(onGo = {
                            vm.submitAddress(addressField, holder)
                            stopEditing()
                        }),
                        decorationBox = { inner ->
                            if (!editing && ui.address.isBlank()) {
                                Text("Search or type URL", style = MaterialTheme.typography.bodyMedium, color = colors.topBarMuted)
                            }
                            inner()
                        },
                    )
                    if (editing && addressField.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close, "Clear",
                            modifier = Modifier.width(18.dp).clickable { addressField = ""; vm.clearSuggestions() },
                        )
                    }
                }
                TabsBadge(count = ui.tabs.size, onClick = { showTabs = true })
                Box {
                    IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("New tab") }, onClick = { menuOpen = false; vm.newTab() })
                        DropdownMenuItem(
                            text = { Text(if (ui.bookmarked) "Remove bookmark" else "Add bookmark") },
                            onClick = { menuOpen = false; vm.toggleBookmark() },
                        )
                        DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { menuOpen = false; showBookmarks = true })
                        DropdownMenuItem(text = { Text("History") }, onClick = { menuOpen = false; showHistory = true })
                        DropdownMenuItem(
                            text = { Text(if (ui.desktop) "Desktop site: on" else "Desktop site: off") },
                            onClick = { menuOpen = false; vm.toggleDesktop(holder) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Return to Spotter") }, onClick = { menuOpen = false; onExit() })
                    }
                }
            }
            if (ui.loading) {
                LinearProgressIndicator(progress = { ui.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val active = ui.tabs.firstOrNull { it.id == ui.activeTabId }
                ui.tabs.filter { it.id in live || it.id == ui.activeTabId }.forEach { tab ->
                    androidx.compose.runtime.key(tab.id) {
                        TabWebView(
                            tab = tab,
                            visible = tab.id == ui.activeTabId,
                            holder = holder,
                            callbacks = callbacks,
                            onReady = { vm.loadIfNeeded(tab, it) },
                            onLongLink = { linkMenu = it },
                        )
                    }
                }
                val wvUrl = active?.let { holder.get(it.id)?.url }
                val empty = active != null &&
                    active.url.isBlank() &&
                    ui.address.isBlank() &&
                    (wvUrl.isNullOrBlank() || wvUrl == "about:blank")
                if (empty) {
                    NewTabPane(bookmarks = ui.bookmarks, onOpen = { vm.submitAddress(it, holder) })
                }
                if (editing) {
                    SuggestionPanel(
                        typed = addressField,
                        suggestions = suggestions,
                        clipboardLink = remember(editing) { clipboardLink(context) },
                        onPick = { vm.submitAddress(it, holder); stopEditing() },
                        onFill = { addressField = it; vm.typing(it) },
                    )
                }
            }
            Surface(color = colors.bottomBar, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.navigationBarsPadding().height(48.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    IconButton({ vm.back(holder) }, enabled = ui.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton({ vm.forward(holder) }, enabled = ui.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                    IconButton({ vm.reload(holder) }) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton({ showDetected = true }, enabled = ui.detected.isNotEmpty()) {
                        BadgeIcon(count = ui.detected.size) { Icon(Icons.Default.Videocam, "Detected media") }
                    }
                    IconButton(onExit) { Icon(Icons.Default.Close, "Return to Spotter") }
                }
            }
        }
        if (showTabs) {
            TabsScreen(
                tabs = ui.tabs,
                activeId = ui.activeTabId,
                onSelect = { vm.selectTab(it, holder); showTabs = false },
                onClose = { vm.closeTab(it, holder) },
                onNew = { vm.newTab(); showTabs = false },
                onBack = { showTabs = false },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
    }

    if (showDetected) {
        DetectedMediaSheet(
            items = ui.detected,
            names = names,
            onName = { url, value -> names = names + (url to value) },
            onDownload = { item ->
                vm.download(item, names[item.url] ?: item.suggestedName)
                // Close the sheet: it used to stay open after Download (owner bug BUG-1).
                showDetected = false
            },
            onDismiss = { showDetected = false },
        )
    }
    if (showBookmarks) {
        ListSheet("Bookmarks", ui.bookmarks.map { it.title to it.url },
            onOpen = { vm.submitAddress(it, holder); showBookmarks = false }, onDismiss = { showBookmarks = false })
    }
    if (showHistory) {
        ListSheet("History", ui.history.map { it.title to it.url },
            onOpen = { vm.submitAddress(it, holder); showHistory = false }, onDismiss = { showHistory = false })
    }
}

private fun clipboardLink(context: Context): String? = try {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
    t?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.takeIf { ' ' !in it }
} catch (_: Exception) {
    null
}

@Composable
private fun TabsBadge(count: Int, onClick: () -> Unit) {
    IconButton(onClick) {
        Box(
            Modifier
                .width(24.dp).height(24.dp)
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .clip(RoundedCornerShape(6.dp))
                .background(SpotterTheme.colors.addressPill),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) ":D" else count.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BadgeIcon(count: Int, icon: @Composable () -> Unit) {
    Box {
        icon()
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp),
            ) {
                Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun SuggestionPanel(
    typed: String,
    suggestions: List<String>,
    clipboardLink: String?,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn {
            if (clipboardLink != null) {
                item {
                    SuggestionRow("Paste link: $clipboardLink", { onPick(clipboardLink) }, { onFill(clipboardLink) })
                }
            }
            if (typed.isNotBlank()) {
                item { SuggestionRow("Go to \"$typed\"", { onPick(typed) }, null) }
            }
            items(suggestions) { s -> SuggestionRow(s, { onPick(s) }, { onFill(s) }) }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit, onFill: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.width(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onFill != null) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Use text", Modifier.width(18.dp).clickable(onClick = onFill))
        }
    }
}

@Composable
private fun TabsScreen(
    tabs: List<TabEntity>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SpotterTopBar {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("${tabs.size} tabs", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onNew) { Icon(Icons.Default.Add, "New tab") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(tabs, key = { it.id }) { tab ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (tab.id == activeId) SpotterTheme.colors.rowSelected else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(tab.id) }
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tab.title.ifBlank { "New tab" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            AddressBar.hostOf(tab.url).ifBlank { "Blank page" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton({ onClose(tab.id) }) { Icon(Icons.Default.Close, "Close tab") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NewTabPane(bookmarks: List<BookmarkEntity>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
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
    onLongLink: (String) -> Unit,
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
            installLongPress(wv, onLongLink)
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
            installLongPress(wv, onLongLink)
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
            TextButton(onDownload, enabled = !isBlob) {
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

private fun installLongPress(wv: WebView, onLongLink: (String) -> Unit) {
    wv.setOnLongClickListener {
        val r = wv.hitTestResult
        val url = r.extra
        if ((r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && url?.startsWith("http") == true
        ) {
            onLongLink(url)
            true
        } else {
            false
        }
    }
}
