package dev.ahmed.spotter.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ahmed.spotter.SpotterApp
import dev.ahmed.spotter.engine.Categories
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.service.BootReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadUi(
    val record: DownloadRecord,
    val speedBps: Long,
    val etaSeconds: Long?,
    val category: String,
)

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpotterApp
    private val manager get() = app.manager

    val search = MutableStateFlow("")
    val filter = MutableStateFlow(Categories.ALL)
    val settings = MutableStateFlow(EngineSettings())
    val pendingUrl = MutableStateFlow<String?>(null)
    val userMessage = MutableStateFlow<String?>(null)

    private val lastBytes = HashMap<Long, Pair<Long, Long>>()

    val items: StateFlow<List<DownloadUi>> = combine(
        app.store.observeAll(),
        search,
        filter,
    ) { records, q, cat ->
        val now = SystemClock.elapsedRealtime()
        val live = records.map { it.id }.toHashSet()
        lastBytes.keys.retainAll(live)
        records.map { rec ->
            val speed = sampleSpeed(rec, now)
            val remain = (rec.size - rec.downloaded).coerceAtLeast(0)
            val eta = if (speed > 0 && rec.size > 0 && rec.downloaded < rec.size) remain / speed else null
            DownloadUi(rec, speed, eta, Categories.of(rec.mimeType, rec.filename))
        }.filter { ui ->
            val matchesCat = cat == Categories.ALL || ui.category == cat
            val matchesQ = q.isBlank() ||
                ui.record.filename.contains(q, ignoreCase = true) ||
                ui.record.url.contains(q, ignoreCase = true)
            matchesCat && matchesQ
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            settings.value = app.store.loadSettings()
        }
    }

    fun enqueue(url: String, filename: String? = null) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        launch { manager.enqueue(trimmed, filename = filename?.ifBlank { null }) }
    }

    fun pause(id: Long) = launch { manager.pause(id) }
    fun resume(id: Long) = launch { manager.resume(id) }
    fun cancel(id: Long) = launch { manager.cancel(id) }
    fun retry(id: Long) = launch { manager.retry(id) }
    fun remove(id: Long, deleteFile: Boolean) = launch { manager.remove(id, deleteFile) }
    fun deleteFile(id: Long) = launch { manager.deleteDownloadedFile(id) }
    fun rename(id: Long, name: String) = launch { manager.rename(id, name) }
    fun pauseAll() = launch { manager.pauseAll() }

    fun setWifiOnly(value: Boolean) = launch {
        val cur = app.store.loadSettings()
        manager.setSettings(cur.copy(wifiOnly = value))
        settings.value = app.store.loadSettings()
    }

    fun setResumeOnBoot(value: Boolean) = launch {
        val cur = app.store.loadSettings()
        manager.setSettings(cur.copy(resumeOnBoot = value))
        BootReceiver.setEnabled(app, value)
        settings.value = app.store.loadSettings()
    }

    fun offerUrl(raw: String?) {
        val url = ShareUrls.extractHttpUrl(raw) ?: return
        pendingUrl.value = url
    }

    fun consumePendingUrl(): String? {
        val url = pendingUrl.value
        pendingUrl.value = null
        return url
    }

    fun clearMessage() {
        userMessage.value = null
    }

    private fun sampleSpeed(rec: DownloadRecord, now: Long): Long {
        val prev = lastBytes[rec.id]
        lastBytes[rec.id] = rec.downloaded to now
        if (prev == null) return 0
        val dt = now - prev.second
        if (dt < 200) return 0
        val db = rec.downloaded - prev.first
        if (db < 0) return 0
        return db * 1000 / dt
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                userMessage.value = e.message ?: e.javaClass.simpleName
            }
        }
    }
}
