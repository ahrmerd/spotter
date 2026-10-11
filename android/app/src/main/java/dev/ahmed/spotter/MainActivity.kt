package dev.ahmed.spotter

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmed.spotter.browser.WebViewHolder
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.service.BatteryPrompt
import dev.ahmed.spotter.ui.BrowserScreen
import dev.ahmed.spotter.ui.BrowserViewModel
import dev.ahmed.spotter.ui.DownloadsScreen
import dev.ahmed.spotter.ui.DownloadsViewModel
import dev.ahmed.spotter.ui.SettingsScreen
import dev.ahmed.spotter.ui.SettingsViewModel
import dev.ahmed.spotter.ui.ShareUrls
import dev.ahmed.spotter.ui.theme.SpotterTheme

enum class Screen { Downloads, Browser, Settings }

class MainActivity : ComponentActivity() {
    private val downloadsVm: DownloadsViewModel by viewModels()
    private val browserVm: BrowserViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()
    private val browserHolder = WebViewHolder()
    private var shareUrl by mutableStateOf<String?>(null)
    private var clipboardUrl by mutableStateOf<String?>(null)
    private var lastClipboardOffered: String? = null
    private var batteryIgnoring by mutableStateOf(false)

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* FGS still runs if denied; the shade simply hides the notification. */ }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) settingsVm.setSaveFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the bars; the coloured top bar sits under the status bar, so its icons stay light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        batteryIgnoring = BatteryPrompt.isIgnoring(this)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        handleSend(intent)
        setContent {
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            SpotterTheme(settings.theme) {
                var screen by rememberSaveable { mutableStateOf(Screen.Downloads) }
                var showBattery by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(settings.batteryPromptDone, settings.notificationsEnabled) {
                    batteryIgnoring = BatteryPrompt.isIgnoring(this@MainActivity)
                    if (!settings.batteryPromptDone && !batteryIgnoring) {
                        showBattery = true
                    }
                    if (settings.notificationsEnabled) requestNotifyPermission()
                }
                dev.ahmed.spotter.ui.ResolveDialogHost((application as SpotterApp).resolve)
                // Downloads is the home screen; the browser and Settings are full screens reached from it (no bottom bar).
                androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Downloads }
                when (screen) {
                    Screen.Downloads -> DownloadsScreen(
                        vm = downloadsVm,
                        shareUrl = shareUrl,
                        clipboardUrl = clipboardUrl,
                        onShareConsumed = { shareUrl = null },
                        onClipboardConsumed = { clipboardUrl = null },
                        onOpenBrowser = { screen = Screen.Browser },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    // The browser handles its own Back (page history, tabs screen, then back to Downloads).
                    Screen.Browser -> BrowserScreen(
                        vm = browserVm,
                        holder = browserHolder,
                        onExit = { screen = Screen.Downloads },
                    )
                    Screen.Settings -> SettingsScreen(
                        vm = settingsVm,
                        batteryIgnoring = batteryIgnoring,
                        onBack = { screen = Screen.Downloads },
                        onPickFolder = { folderPicker.launch(null) },
                        onBattery = { showBatteryPrompt() },
                        onExportLogs = { exportLogs() },
                        onRequestNotifications = { requestNotifyPermission() },
                    )
                }
                if (showBattery) {
                    AlertDialog(
                        onDismissRequest = {
                            showBattery = false
                            settingsVm.markBatteryPromptDone()
                        },
                        title = { Text(BatteryPrompt.TITLE) },
                        text = {
                            Text(BatteryPrompt.BODY + "\n\n" + BatteryPrompt.VENDOR_NOTE)
                        },
                        confirmButton = {
                            TextButton({
                                showBattery = false
                                settingsVm.markBatteryPromptDone()
                                showBatteryPrompt()
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            TextButton({
                                showBattery = false
                                settingsVm.markBatteryPromptDone()
                            }) { Text("Not now") }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSend(intent)
    }

    override fun onResume() {
        super.onResume()
        batteryIgnoring = BatteryPrompt.isIgnoring(this)
        maybeOfferClipboard()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        browserHolder.destroyAll()
        super.onDestroy()
    }

    private fun handleSend(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = ShareUrls.extractHttpUrl(text) ?: return
        shareUrl = url
        downloadsVm.offerUrl(url)
    }

    private fun maybeOfferClipboard() {
        val clip = getSystemService(ClipboardManager::class.java) ?: return
        val text = try {
            clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: SecurityException) {
            null
        } ?: return
        val url = ShareUrls.extractHttpUrl(text) ?: return
        if (url == lastClipboardOffered) return
        lastClipboardOffered = url
        clipboardUrl = url
    }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showBatteryPrompt() {
        val intent = if (BatteryPrompt.isIgnoring(this)) {
            BatteryPrompt.settingsIntent()
        } else {
            BatteryPrompt.requestIgnoreIntent(this)
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(BatteryPrompt.settingsIntent())
        }
        batteryIgnoring = BatteryPrompt.isIgnoring(this)
    }

    private fun exportLogs() {
        val uri = settingsVm.exportLogUri() ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Spotter logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export logs"))
    }
}
