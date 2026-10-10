package dev.ahmed.spotter.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.ahmed.spotter.SpotterApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as? SpotterApp
        if (app == null) {
            pending.finish()
            return
        }
        app.appScope.launch(Dispatchers.IO) {
            try {
                val settings = app.store.loadSettings()
                if (settings.resumeOnBoot) {
                    DownloadService.start(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            app.packageManager.setComponentEnabledSetting(
                ComponentName(app, BootReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
