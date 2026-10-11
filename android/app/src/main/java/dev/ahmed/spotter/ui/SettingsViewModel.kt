package dev.ahmed.spotter.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ahmed.spotter.SpotterApp
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.sink.SafSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpotterApp
    val settings = app.settingsFlow
    val logText = MutableStateFlow("")
    val userMessage = MutableStateFlow<String?>(null)
    val folderLabel = MutableStateFlow("Download/Spotter (default)")

    init {
        viewModelScope.launch { refreshFolderLabel() }
        viewModelScope.launch {
            settings.collect { refreshFolderLabel() }
        }
    }

    fun setConcurrent(value: Int) = update { cur ->
        val n = value.coerceIn(1, 8)
        cur.copy(
            maxConcurrentDownloads = n,
            maxTotalConnections = (n * cur.connectionsPerFileCapped()).coerceIn(1, 64),
        )
    }

    fun setConnections(value: Int) = update { cur ->
        val n = value.coerceIn(1, EngineSettings.MAX_CONNECTIONS_PER_FILE)
        cur.copy(
            connectionsPerFile = n,
            maxTotalConnections = (cur.maxConcurrentCapped() * n).coerceIn(1, 64),
        )
    }

    fun setSpeedLimitBps(value: Long) = update { it.copy(speedLimitBps = value.coerceAtLeast(0)) }
    fun setWifiOnly(value: Boolean) = update { it.copy(wifiOnly = value) }
    fun setResumeOnBoot(value: Boolean) = update { it.copy(resumeOnBoot = value) }
    fun setDefaultHlsQuality(value: String) = update { it.copy(defaultHlsQuality = value) }
    fun setNotifications(value: Boolean) = update { it.copy(notificationsEnabled = value) }
    fun setTheme(value: String) = update { it.copy(theme = value) }
    fun markBatteryPromptDone() = update { it.copy(batteryPromptDone = true) }

    fun setSaveFolder(uri: Uri?) = viewModelScope.launch {
        val cur = app.store.loadSettings()
        val old = cur.saveFolderUri
        if (old.isNotEmpty() && old != uri?.toString()) {
            try {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // already gone
            }
        }
        if (uri != null) {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        app.applySettings(cur.copy(saveFolderUri = uri?.toString().orEmpty()))
    }

    fun clearSaveFolder() = setSaveFolder(null)

    fun reloadLogs() = viewModelScope.launch(Dispatchers.IO) {
        logText.value = app.fileLog.readAll().ifBlank { "(no log lines yet)" }
    }

    fun exportLogUri(): Uri? {
        val dest = File(app.cacheDir, "spotter-log.txt")
        app.fileLog.exportTo(dest)
        if (!dest.exists() || dest.length() == 0L) {
            dest.writeText("(no log lines yet)\n")
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", dest)
    }

    fun clearMessage() {
        userMessage.value = null
    }

    private fun update(block: (EngineSettings) -> EngineSettings) {
        viewModelScope.launch {
            try {
                val cur = app.store.loadSettings()
                app.applySettings(block(cur))
            } catch (e: Exception) {
                userMessage.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private suspend fun refreshFolderLabel() {
        val uri = app.settingsSnap.saveFolderUri
        folderLabel.value = if (uri.isBlank()) {
            "Download/Spotter (default)"
        } else {
            withContext(Dispatchers.IO) {
                runCatching { SafSink.displayName(app.contentResolver, uri) }.getOrDefault("").ifBlank { uri }
            }
        }
    }
}
