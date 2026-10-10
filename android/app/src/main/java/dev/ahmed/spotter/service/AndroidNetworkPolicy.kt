package dev.ahmed.spotter.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.ahmed.spotter.engine.net.NetworkPolicy

class AndroidNetworkPolicy(context: Context) : NetworkPolicy {
    private val app = context.applicationContext

    override fun canDownload(): Boolean {
        val caps = capabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override fun isOnline(): Boolean {
        val caps = capabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun capabilities(): NetworkCapabilities? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(net)
    }
}
