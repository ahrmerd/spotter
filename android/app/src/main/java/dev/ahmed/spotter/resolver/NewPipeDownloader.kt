package dev.ahmed.spotter.resolver

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/** NewPipeExtractor's HTTP layer on top of the app's shared OkHttp client (HTTP/2, no cookies from the browser). */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            val text = resp.body?.string().orEmpty()
            return Response(resp.code, resp.message, resp.headers.toMultimap(), text, resp.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
