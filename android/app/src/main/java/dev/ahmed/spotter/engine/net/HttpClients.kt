package dev.ahmed.spotter.engine.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    /**
     * Shared client. OkHttp negotiates HTTP/2 by default; do not pin HTTP/1.1
     * (some CDNs 403 HTTP/1.1 clients — desktop lesson 8.1).
     */
    fun create(
        connectTimeoutSeconds: Long = 20,
        readTimeoutSeconds: Long = 60,
        writeTimeoutSeconds: Long = 60,
    ): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .build()
}
