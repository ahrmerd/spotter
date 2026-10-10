package dev.ahmed.spotter

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.webkit.CookieManager
import android.webkit.WebView
import dev.ahmed.spotter.browser.WebViewHolder
import dev.ahmed.spotter.ui.BrowserScreen
import dev.ahmed.spotter.ui.BrowserViewModel
import dev.ahmed.spotter.ui.DownloadsScreen
import dev.ahmed.spotter.ui.DownloadsViewModel
import dev.ahmed.spotter.ui.ShareUrls

class MainActivity : ComponentActivity() {
    private val downloadsVm: DownloadsViewModel by viewModels()
    private val browserVm: BrowserViewModel by viewModels()
    private val browserHolder = WebViewHolder()
    private var shareUrl by mutableStateOf<String?>(null)
    private var clipboardUrl by mutableStateOf<String?>(null)
    private var lastClipboardOffered: String? = null

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* FGS still runs if denied; the shade simply hides the notification. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotifyPermission()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        handleSend(intent)
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Download, null) },
                            label = { Text("Downloads") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Public, null) },
                            label = { Text("Browser") },
                        )
                    }
                }) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        if (tab == 0) {
                            DownloadsScreen(
                                vm = downloadsVm,
                                shareUrl = shareUrl,
                                clipboardUrl = clipboardUrl,
                                onShareConsumed = { shareUrl = null },
                                onClipboardConsumed = { clipboardUrl = null },
                            )
                        } else {
                            BrowserScreen(vm = browserVm, holder = browserHolder)
                        }
                    }
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
}
