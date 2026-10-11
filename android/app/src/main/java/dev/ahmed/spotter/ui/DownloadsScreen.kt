package dev.ahmed.spotter.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmed.spotter.engine.Categories
import dev.ahmed.spotter.engine.EngineErrors
import dev.ahmed.spotter.engine.hls.HlsProgress
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.service.DownloadNotifier
import dev.ahmed.spotter.ui.components.SpotterTopBar
import dev.ahmed.spotter.ui.theme.SpotterTheme
import kotlinx.coroutines.launch

/** The four status tabs under the top bar (as in 1DM): everything, running, finished, needs attention. */
enum class StatusTab(val label: String) {
    ALL("ALL"), DOWNLOADING("DOWNLOADING"), FINISHED("FINISHED"), ERRORS("ERRORS");

    fun matches(state: DownloadState): Boolean = when (this) {
        ALL -> true
        DOWNLOADING -> state == DownloadState.RUNNING || state == DownloadState.QUEUED || state == DownloadState.PAUSED
        FINISHED -> state == DownloadState.DONE
        ERRORS -> state == DownloadState.ERROR || state == DownloadState.NEEDS_REFRESH
    }
}

enum class SortOrder(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), NAME("Name"), SIZE("Size"), STATUS("Status");

    fun sort(list: List<DownloadUi>): List<DownloadUi> = when (this) {
        NEWEST -> list.sortedByDescending { it.record.id }
        OLDEST -> list.sortedBy { it.record.id }
        NAME -> list.sortedBy { it.record.filename.lowercase() }
        SIZE -> list.sortedByDescending { it.record.size }
        STATUS -> list.sortedBy { it.record.state.ordinal }
    }
}

@Composable
fun DownloadsScreen(
    vm: DownloadsViewModel,
    shareUrl: String? = null,
    clipboardUrl: String? = null,
    onShareConsumed: () -> Unit = {},
    onClipboardConsumed: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val pending by vm.pendingUrl.collectAsStateWithLifecycle()
    val message by vm.userMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)

    var tab by rememberSaveable { mutableStateOf(StatusTab.ALL) }
    var sort by rememberSaveable { mutableStateOf(SortOrder.NEWEST) }
    var showAdd by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DownloadUi?>(null) }
    var removeTargets by remember { mutableStateOf<List<DownloadUi>>(emptyList()) }
    var clipboardPrompt by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()

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
    // Drop selected ids that no longer exist (removed, cleared...).
    LaunchedEffect(all) {
        val live = all.map { it.record.id }.toSet()
        if (selected.any { it !in live }) selected = selected.filter { it in live }.toSet()
    }

    val counts = StatusTab.entries.associateWith { t -> all.count { t.matches(it.record.state) } }
    val shown = remember(items, tab, sort) { sort.sort(items.filter { tab.matches(it.record.state) }) }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !selecting,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text("Spotter", style = MaterialTheme.typography.titleLarge)
                    Text("Download manager and video spotter", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Browser") },
                    icon = { Icon(Icons.Default.Public, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenBrowser() },
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Default.Settings, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                )
                NavigationDrawerItem(
                    label = { Text("Clear finished") },
                    icon = { Icon(Icons.Default.Delete, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; vm.clearFinished() },
                )
                NavigationDrawerItem(
                    label = { Text("About") },
                    icon = { Icon(Icons.Default.Apps, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; showAbout = true },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    when {
                        selecting -> SelectionBar(
                            count = selected.size,
                            onClose = { selected = emptySet() },
                            onSelectAll = { selected = shown.map { it.record.id }.toSet() },
                            onPause = { selected.forEach(vm::pause); selected = emptySet() },
                            onResume = { selected.forEach(vm::resume); selected = emptySet() },
                            onRemove = { removeTargets = all.filter { it.record.id in selected } },
                        )
                        searching -> SearchBar(
                            value = search,
                            onValue = { vm.search.value = it },
                            onClose = { searching = false; vm.search.value = "" },
                        )
                        else -> SpotterTopBar {
                            IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                            Text(
                                "Spotter",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                            IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") }
                            Box {
                                IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort and filter") }
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    Text(
                                        "Sort by",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    SortOrder.entries.forEach { o ->
                                        DropdownMenuItem(
                                            text = { Text(o.label) },
                                            leadingIcon = { RadioButton(sort == o, null) },
                                            onClick = { sort = o; sortMenu = false },
                                        )
                                    }
                                    HorizontalDivider()
                                    Text(
                                        "Type",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    Categories.FILTERS.forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(c) },
                                            leadingIcon = { RadioButton(filter == c, null) },
                                            onClick = { vm.filter.value = c; sortMenu = false },
                                        )
                                    }
                                }
                            }
                            IconButton(onOpenBrowser) { Icon(Icons.Default.Public, "Open browser") }
                            Box {
                                IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(moreMenu, { moreMenu = false }) {
                                    DropdownMenuItem(text = { Text("Pause all") }, onClick = { vm.pauseAll(); moreMenu = false })
                                    DropdownMenuItem(text = { Text("Resume all") }, onClick = { vm.resumeAll(); moreMenu = false })
                                    DropdownMenuItem(text = { Text("Clear finished") }, onClick = { vm.clearFinished(); moreMenu = false })
                                    DropdownMenuItem(text = { Text("Settings") }, onClick = { moreMenu = false; onOpenSettings() })
                                }
                            }
                        }
                    }
                    StatusTabs(tab, counts) { tab = it }
                }
            },
            floatingActionButton = {
                if (!selecting) {
                    FloatingActionButton(
                        onClick = { addPrefill = ""; showAdd = true },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Icon(Icons.Default.Add, "Add download") }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                if (shown.isEmpty()) {
                    EmptyState(tab, hasAny = all.isNotEmpty(), onOpenBrowser = onOpenBrowser)
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(shown, key = { it.record.id }) { ui ->
                            val id = ui.record.id
                            DownloadRow(
                                ui = ui,
                                selected = id in selected,
                                selecting = selecting,
                                queueReason = queueReason(context),
                                onClick = {
                                    when {
                                        selecting -> selected = if (id in selected) selected - id else selected + id
                                        ui.record.state == DownloadState.DONE -> FileIntents.open(context, ui.record)
                                    }
                                },
                                onLongClick = { selected = selected + id },
                                onPrimary = {
                                    when (ui.record.state) {
                                        DownloadState.RUNNING, DownloadState.QUEUED -> vm.pause(id)
                                        DownloadState.PAUSED -> vm.resume(id)
                                        DownloadState.ERROR, DownloadState.NEEDS_REFRESH -> vm.retry(id)
                                        DownloadState.DONE -> FileIntents.open(context, ui.record)
                                    }
                                },
                                onCancel = { vm.cancel(id) },
                                onRemove = { removeTargets = listOf(ui) },
                                onRename = { renameTarget = ui },
                                onShare = { FileIntents.share(context, ui.record) },
                                onDeleteFile = { vm.deleteFile(id) },
                                onCopyLink = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText("Link", ui.record.url))
                                },
                                onCopyError = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText("Spotter error", vm.copyErrorText(ui.record)))
                                },
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
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
    if (removeTargets.isNotEmpty()) {
        RemoveDialog(
            names = removeTargets.map { it.record.filename.ifBlank { displayFilename(it.record.url) } },
            canDeleteFile = removeTargets.any { it.record.state == DownloadState.DONE && it.record.outputPath.isNotEmpty() },
            onDismiss = { removeTargets = emptyList() },
            onRemove = { delete ->
                removeTargets.forEach { vm.remove(it.record.id, delete) }
                removeTargets = emptyList()
                selected = emptySet()
            },
        )
    }
    clipboardPrompt?.let { url ->
        AlertDialog(
            onDismissRequest = { clipboardPrompt = null },
            title = { Text("Download from clipboard?") },
            text = { Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis) },
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
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun StatusTabs(selected: StatusTab, counts: Map<StatusTab, Int>, onSelect: (StatusTab) -> Unit) {
    val colors = SpotterTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.topBar),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatusTab.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${t.label} (${counts[t] ?: 0})",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = if (active) colors.onTopBar else colors.topBarMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(3.dp)
                        .background(if (active) colors.onTopBar else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun SearchBar(value: String, onValue: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SpotterTopBar {
        IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SpotterTheme.colors.onTopBar),
            cursorBrush = SolidColor(SpotterTheme.colors.onTopBar),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Search downloads", color = SpotterTheme.colors.topBarMuted)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) IconButton({ onValue("") }) { Icon(Icons.Default.Close, "Clear") }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    SpotterTopBar {
        IconButton(onClose) { Icon(Icons.Default.Close, "Cancel selection") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }
        IconButton(onPause) { Icon(Icons.Default.Pause, "Pause selected") }
        IconButton(onResume) { Icon(Icons.Default.PlayArrow, "Resume selected") }
        IconButton(onRemove) { Icon(Icons.Default.Delete, "Remove selected") }
    }
}

@Composable
private fun EmptyState(tab: StatusTab, hasAny: Boolean, onOpenBrowser: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Download, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(
            if (hasAny) "Nothing in ${tab.label.lowercase()}" else "No downloads yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasAny) "Try another tab or clear the search." else "Tap + to paste a link, or open the browser and play a video.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasAny) {
            Spacer(Modifier.height(16.dp))
            Button(onOpenBrowser) {
                Icon(Icons.Default.Public, null)
                Spacer(Modifier.width(8.dp))
                Text("Open browser")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    ui: DownloadUi,
    selected: Boolean,
    selecting: Boolean,
    queueReason: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDeleteFile: () -> Unit,
    onCopyLink: () -> Unit,
    onCopyError: () -> Unit,
) {
    val rec = ui.record
    val colors = SpotterTheme.colors
    val attention = rec.state == DownloadState.ERROR || rec.state == DownloadState.NEEDS_REFRESH
    val container = when {
        selected -> colors.rowSelected
        attention -> colors.rowError
        rec.state == DownloadState.DONE -> colors.rowDone
        else -> MaterialTheme.colorScheme.surface
    }
    val content = if (attention && !selected) colors.onRowError else MaterialTheme.colorScheme.onSurface
    val muted = if (attention && !selected) colors.onRowError.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val active = rec.state == DownloadState.RUNNING || rec.state == DownloadState.QUEUED || rec.state == DownloadState.PAUSED
    val progress = if (rec.size > 0) (rec.downloaded.toFloat() / rec.size.toFloat()).coerceIn(0f, 1f) else 0f
    var menu by remember { mutableStateOf(false) }

    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        ) {
            if (selecting) {
                Checkbox(selected, null, Modifier.padding(end = 8.dp))
            }
            LeadingIcon(ui, onPrimary, attention)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(titleText(rec, muted), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (active) {
                    Spacer(Modifier.height(6.dp))
                    if (rec.state == DownloadState.RUNNING && rec.size <= 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(metaLine(ui), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText(ui, queueReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attention) content else muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Box {
                    IconButton({ menu = true }, Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Actions") }
                    DropdownMenu(menu, { menu = false }) {
                        val close: () -> Unit = { menu = false }
                        when (rec.state) {
                            DownloadState.RUNNING, DownloadState.QUEUED -> {
                                DropdownMenuItem(text = { Text("Pause") }, onClick = { close(); onPrimary() })
                                DropdownMenuItem(text = { Text("Cancel") }, onClick = { close(); onCancel() })
                            }
                            DownloadState.PAUSED -> {
                                DropdownMenuItem(text = { Text("Resume") }, onClick = { close(); onPrimary() })
                                DropdownMenuItem(text = { Text("Cancel") }, onClick = { close(); onCancel() })
                            }
                            DownloadState.ERROR, DownloadState.NEEDS_REFRESH -> {
                                DropdownMenuItem(text = { Text("Retry") }, onClick = { close(); onPrimary() })
                                DropdownMenuItem(text = { Text("Copy error") }, onClick = { close(); onCopyError() })
                            }
                            DownloadState.DONE -> {
                                DropdownMenuItem(text = { Text("Open") }, onClick = { close(); onPrimary() })
                                DropdownMenuItem(text = { Text("Share") }, onClick = { close(); onShare() })
                                DropdownMenuItem(text = { Text("Delete file") }, onClick = { close(); onDeleteFile() })
                            }
                        }
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); onRename() })
                        DropdownMenuItem(text = { Text("Copy link") }, onClick = { close(); onCopyLink() })
                        DropdownMenuItem(text = { Text("Remove from list") }, onClick = { close(); onRemove() })
                    }
                }
                val right = rightText(ui)
                if (right.isNotEmpty()) {
                    Text(right, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}

/** The round icon on the left: a file-type icon that also acts as the main button (pause, resume, retry, open). */
@Composable
private fun LeadingIcon(ui: DownloadUi, onPrimary: () -> Unit, attention: Boolean) {
    val rec = ui.record
    val (icon, desc) = when (rec.state) {
        DownloadState.RUNNING, DownloadState.QUEUED -> Icons.Default.Pause to "Pause"
        DownloadState.PAUSED -> Icons.Default.PlayArrow to "Resume"
        DownloadState.ERROR, DownloadState.NEEDS_REFRESH -> Icons.Default.Refresh to "Retry"
        DownloadState.DONE -> categoryIcon(ui.category) to "Open"
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (attention) Color.Black.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onPrimary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (attention) LocalContentColor.current else MaterialTheme.colorScheme.primary)
    }
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    Categories.VIDEO -> Icons.Default.Movie
    Categories.AUDIO -> Icons.Default.Audiotrack
    Categories.IMAGE -> Icons.Default.Image
    Categories.DOCUMENT -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

@Composable
private fun titleText(rec: DownloadRecord, muted: Color): AnnotatedString {
    val colors = SpotterTheme.colors
    val name = rec.filename.ifBlank { displayFilename(rec.url) }
    val (label, color) = when {
        rec.ranged -> "Yes" to colors.success
        rec.size <= 0 && rec.downloaded == 0L -> "N/A" to muted
        else -> "No" to colors.warning
    }
    return buildAnnotatedString {
        append(name)
        withStyle(SpanStyle(fontSize = 11.sp, color = muted, fontWeight = FontWeight.Normal)) { append("  (Resume: ") }
        withStyle(SpanStyle(fontSize = 11.sp, color = color)) { append(label) }
        withStyle(SpanStyle(fontSize = 11.sp, color = muted, fontWeight = FontWeight.Normal)) { append(")") }
    }
}

private fun metaLine(ui: DownloadUi): String {
    val rec = ui.record
    val pct = if (rec.size > 0) "${(rec.downloaded * 100 / rec.size).coerceIn(0, 100)}%" else "--"
    val sizes = when {
        HlsProgress.isSegmentMode(rec) && rec.size > 0 -> HlsProgress.line(rec.downloaded, rec.size)
        rec.size > 0 -> "${DownloadNotifier.formatBytes(rec.downloaded)} / ${DownloadNotifier.formatBytes(rec.size)}"
        else -> DownloadNotifier.formatBytes(rec.downloaded)
    }
    val speed = if (ui.speedBps > 0 && rec.state == DownloadState.RUNNING) {
        if (HlsProgress.isSegmentMode(rec)) " · ${ui.speedBps} seg/s" else " · ${DownloadNotifier.formatBytes(ui.speedBps)}/s"
    } else {
        ""
    }
    return "$pct | $sizes$speed"
}

private fun statusText(ui: DownloadUi, queueReason: String?): String {
    val rec = ui.record
    return when (rec.state) {
        DownloadState.QUEUED -> queueReason ?: "Queued"
        DownloadState.RUNNING -> if (rec.size <= 0 && rec.downloaded > 0) "Downloading (size unknown)" else "Downloading"
        DownloadState.PAUSED -> if (!rec.ranged && rec.downloaded > 0) "Paused · resume restarts from 0" else "Paused"
        DownloadState.DONE -> rec.error?.let { "Completed · ${EngineErrors.userMessage(it)}" } ?: "Completed"
        DownloadState.ERROR -> rec.error?.let { EngineErrors.userMessage(it) } ?: "Error"
        DownloadState.NEEDS_REFRESH -> "Link expired. Open the page again to get a new link." +
            (rec.error?.let { " (${EngineErrors.userMessage(it)})" } ?: "")
    }
}

private fun rightText(ui: DownloadUi): String = when (ui.record.state) {
    DownloadState.RUNNING -> ui.etaSeconds?.let { formatEta(it) }.orEmpty()
    DownloadState.DONE -> relativeAge(ui.record.createdAt)
    else -> ""
}

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun relativeAge(createdAt: Long): String {
    if (createdAt <= 0) return ""
    val s = (System.currentTimeMillis() - createdAt) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/** Why a queued item is not running yet (M5 audit F4): no network, or Wi-Fi only without Wi-Fi. */
private fun queueReason(context: android.content.Context): String? {
    val app = context.applicationContext as? dev.ahmed.spotter.SpotterApp ?: return null
    val policy = app.networkPolicy
    return when {
        !policy.isOnline() -> "Waiting for network"
        app.settingsSnap.wifiOnly && !policy.canDownload() -> "Waiting for Wi-Fi"
        else -> null
    }
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
                OutlinedTextField(url, { url = it }, label = { Text("Link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("File name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
    names: List<String>,
    canDeleteFile: Boolean,
    onDismiss: () -> Unit,
    onRemove: (Boolean) -> Unit,
) {
    var deleteFile by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (names.size == 1) "Remove download" else "Remove ${names.size} downloads") },
        text = {
            Column {
                Text(if (names.size == 1) "Remove “${names.first()}” from the list?" else "Remove ${names.size} items from the list?")
                if (canDeleteFile) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteFile = !deleteFile }) {
                        Checkbox(deleteFile, { deleteFile = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also delete the files")
                    }
                }
            }
        },
        confirmButton = { TextButton({ onRemove(deleteFile) }) { Text("Remove") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Spotter") },
        text = {
            Column {
                Text("A download manager with a built-in browser that spots videos and files on the page.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "YouTube support uses NewPipeExtractor (GPL-3.0), so Spotter is distributed under GPL-3.0.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}

internal fun displayFilename(url: String): String {
    val path = dev.ahmed.spotter.engine.Filenames.urlPathName(url)
    if (path.isNotBlank()) return path
    return url.substringAfter("://").substringBefore('/').ifBlank { url }
}
