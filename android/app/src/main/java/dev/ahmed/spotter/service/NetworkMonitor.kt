package dev.ahmed.spotter.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.ahmed.spotter.SpotterApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object NetworkMonitor {
    @Volatile private var lastOnline: Boolean? = null
    @Volatile private var lastUnmetered: Boolean? = null
    private var debounce: Job? = null

    fun register(app: SpotterApp) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(app)
            override fun onLost(network: Network) = onChange(app)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange(app)
        })
    }

    private fun onChange(app: SpotterApp) {
        debounce?.cancel()
        debounce = app.appScope.launch(Dispatchers.IO) {
            delay(400)
            val settings = app.store.loadSettings()
            val policy = app.networkPolicy
            val online = policy.isOnline()
            val unmetered = policy.canDownload()
            if (online == lastOnline && unmetered == lastUnmetered) return@launch
            lastOnline = online
            lastUnmetered = unmetered
            val blocked = !online || (settings.wifiOnly && !unmetered)
            app.manager.notifyNetworkChanged()
            if (blocked) {
                ResumeScheduler.enqueueWhenConnected(app, settings.wifiOnly)
            } else {
                ResumeScheduler.cancelNetworkWait(app)
                DownloadService.start(app)
            }
        }
    }
}
