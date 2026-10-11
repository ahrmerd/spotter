package dev.ahmed.spotter.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryPrompt {
    const val TITLE = "Keep downloads alive"
    const val BODY =
        "Spotter downloads in the background. Android may pause the app to save battery, " +
            "which stops in-progress files. Allow unrestricted battery use so downloads can finish " +
            "with the screen off."
    const val VENDOR_NOTE =
        "Some phones (Xiaomi, Huawei, Honor, Oppo, Vivo, Samsung) also have a vendor " +
            "auto-start or battery-saver list. Add Spotter there if downloads stop overnight."

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
