package dev.ahmed.spotter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmed.spotter.SpotterApp
import dev.ahmed.spotter.engine.Filenames
import dev.ahmed.spotter.resolver.OptionKind
import dev.ahmed.spotter.resolver.ResolveException
import dev.ahmed.spotter.resolver.ResolvedMedia
import dev.ahmed.spotter.resolver.ResolvedUrls
import dev.ahmed.spotter.service.DownloadNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ResolveUi {
    data class Loading(val url: String) : ResolveUi
    data class Ready(val media: ResolvedMedia, val selected: String, val nameHint: String?) : ResolveUi
    data class Failed(val message: String) : ResolveUi
}

/**
 * Looks a page up with the right [dev.ahmed.spotter.resolver.SiteResolver], lets the user pick a quality and queues the
 * download. Shared by the Add-URL box, the share target and the browser, so every entry point behaves the same.
 */
class ResolveCoordinator(private val app: SpotterApp) {
    private val _state = MutableStateFlow<ResolveUi?>(null)
    val state: StateFlow<ResolveUi?> = _state
    private var job: Job? = null

    fun handles(url: String): Boolean = app.resolvers.any { it.canHandle(url) }

    fun start(url: String, nameHint: String? = null) {
        val resolver = app.resolvers.firstOrNull { it.canHandle(url) }
        if (resolver == null) {
            _state.value = ResolveUi.Failed("This site is not supported yet.")
            return
        }
        job?.cancel()
        _state.value = ResolveUi.Loading(url)
        job = app.appScope.launch {
            try {
                val media = resolver.resolve(url)
                val first = media.options.firstOrNull { it.kind != OptionKind.AUDIO_ONLY } ?: media.options.first()
                _state.value = ResolveUi.Ready(media, first.id, nameHint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResolveException) {
                _state.value = ResolveUi.Failed(e.message ?: "Could not read this page")
            } catch (e: Exception) {
                _state.value = ResolveUi.Failed("Could not read this page (${e.javaClass.simpleName})")
            }
        }
    }

    fun select(id: String) {
        val cur = _state.value as? ResolveUi.Ready ?: return
        _state.value = cur.copy(selected = id)
    }

    fun confirm() {
        val cur = _state.value as? ResolveUi.Ready ?: return
        val option = cur.media.options.firstOrNull { it.id == cur.selected } ?: return
        _state.value = null
        val plan = ResolvedUrls.planFor(cur.media, option)
        val ext = if (option.kind == OptionKind.AUDIO_ONLY) ".m4a" else ".mp4"
        val title = (cur.nameHint?.takeIf { it.isNotBlank() } ?: cur.media.title)
        val name = Filenames.sanitize(title).let { if (Filenames.extensionOf(it).isEmpty()) it + ext else it }
        app.appScope.launch { app.manager.enqueue(ResolvedUrls.encode(plan), emptyMap(), name) }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = null
    }
}

@Composable
fun ResolveDialogHost(coordinator: ResolveCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    when (val s = state) {
        null -> Unit
        is ResolveUi.Loading -> AlertDialog(
            onDismissRequest = { coordinator.dismiss() },
            title = { Text("Looking up the video") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.size(16.dp))
                    Text("This can take a few seconds.")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ coordinator.dismiss() }) { Text("Cancel") } },
        )
        is ResolveUi.Failed -> AlertDialog(
            onDismissRequest = { coordinator.dismiss() },
            title = { Text("Can't download this") },
            text = { Text(s.message) },
            confirmButton = { TextButton({ coordinator.dismiss() }) { Text("OK") } },
        )
        is ResolveUi.Ready -> AlertDialog(
            onDismissRequest = { coordinator.dismiss() },
            title = {
                Column {
                    Text(s.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(s.media.author, formatDuration(s.media.durationSeconds)).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    s.media.options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option.id == s.selected, onClick = { coordinator.select(option.id) })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.id == s.selected, onClick = { coordinator.select(option.id) })
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(option.label)
                                if (option.approxBytes > 0) {
                                    Text(
                                        DownloadNotifier.formatBytes(option.approxBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            },
            confirmButton = { TextButton({ coordinator.confirm() }) { Text("Download") } },
            dismissButton = { TextButton({ coordinator.dismiss() }) { Text("Cancel") } },
        )
    }
}

private fun formatDuration(seconds: Long): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
