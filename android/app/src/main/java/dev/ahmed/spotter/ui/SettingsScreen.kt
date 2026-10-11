package dev.ahmed.spotter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.service.BatteryPrompt
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(
    0L to "Unlimited",
    64L * 1024 to "64 KB",
    256L * 1024 to "256 KB",
    512L * 1024 to "512 KB",
    1024L * 1024 to "1 MB",
    2L * 1024 * 1024 to "2 MB",
    5L * 1024 * 1024 to "5 MB",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    batteryIgnoring: Boolean,
    onPickFolder: () -> Unit,
    onBattery: () -> Unit,
    onExportLogs: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val folder by vm.folderLabel.collectAsStateWithLifecycle()
    val logs by vm.logText.collectAsStateWithLifecycle()
    val message by vm.userMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showLogs by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearMessage()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Section("Downloads")
            SliderRow(
                title = "Concurrent downloads",
                valueText = settings.maxConcurrentCapped().toString(),
                value = settings.maxConcurrentCapped().toFloat(),
                range = 1f..8f,
                steps = 6,
                onChange = { vm.setConcurrent(it.roundToInt()) },
            )
            SliderRow(
                title = "Connections per file",
                valueText = settings.connectionsPerFileCapped().toString(),
                value = settings.connectionsPerFileCapped().toFloat(),
                range = 1f..16f,
                steps = 14,
                onChange = { vm.setConnections(it.roundToInt()) },
            )
            Text("Speed limit (per second)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            ChipRow(
                options = SPEED_OPTIONS.map { it.second },
                selected = SPEED_OPTIONS.firstOrNull { it.first == settings.speedLimitBps }?.second ?: "Unlimited",
                onPick = { label ->
                    SPEED_OPTIONS.firstOrNull { it.second == label }?.let { vm.setSpeedLimitBps(it.first) }
                },
            )
            SwitchRow("Wi-Fi only", settings.wifiOnly) { vm.setWifiOnly(it) }
            SwitchRow("Resume on boot", settings.resumeOnBoot) { vm.setResumeOnBoot(it) }

            Section("HLS")
            Text("Default quality", style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = EngineSettings.HLS_QUALITY_OPTIONS.map { qualityLabel(it) },
                selected = qualityLabel(settings.defaultHlsQuality),
                onPick = { label ->
                    val key = EngineSettings.HLS_QUALITY_OPTIONS.firstOrNull { qualityLabel(it) == label }
                        ?: EngineSettings.QUALITY_BEST
                    vm.setDefaultHlsQuality(key)
                },
            )

            Section("Appearance")
            ChipRow(
                options = listOf("System", "Light", "Dark"),
                selected = themeLabel(settings.theme),
                onPick = { label ->
                    vm.setTheme(
                        when (label) {
                            "Light" -> EngineSettings.THEME_LIGHT
                            "Dark" -> EngineSettings.THEME_DARK
                            else -> EngineSettings.THEME_SYSTEM
                        },
                    )
                },
            )

            Section("Notifications")
            SwitchRow("Notifications", settings.notificationsEnabled) {
                vm.setNotifications(it)
                if (it) onRequestNotifications()
            }
            Text(
                "The download-in-progress notification stays while files are running (Android requires it).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Save folder")
            Text(folder, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onPickFolder) { Text("Choose folder") }
                if (settings.saveFolderUri.isNotEmpty()) {
                    TextButton({ vm.clearSaveFolder() }) { Text("Use default") }
                }
            }
            Text(
                "Picks a folder through the system picker (SAF). Existing downloads keep their current location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Battery")
            Text(
                if (batteryIgnoring) "Unrestricted battery use is allowed."
                else "Android may pause downloads to save battery.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onBattery) {
                Text(if (batteryIgnoring) "Battery settings" else "Allow unrestricted battery")
            }
            Text(
                BatteryPrompt.VENDOR_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Logs")
            Row {
                TextButton({
                    vm.reloadLogs()
                    showLogs = true
                }) { Text("View logs") }
                TextButton(onExportLogs) { Text("Export logs") }
            }
            Text(
                "Cookie values and keys are redacted. Rotating file, last few hundred KB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Logs (times are UTC)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(logs, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ showLogs = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column {
            options.chunked(4).forEach { row ->
                Row {
                    row.forEach { label ->
                        FilterChip(
                            selected = label == selected,
                            onClick = { onPick(label) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun qualityLabel(key: String): String = when (key) {
    "1080" -> "1080p"
    "720" -> "720p"
    "480" -> "480p"
    "360" -> "360p"
    else -> "Best"
}

private fun themeLabel(key: String): String = when (key) {
    EngineSettings.THEME_LIGHT -> "Light"
    EngineSettings.THEME_DARK -> "Dark"
    else -> "System"
}
