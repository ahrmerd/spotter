package dev.ahmed.spotter.engine.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class Http2ProtocolTest {
    @Test
    fun sharedClientNegotiatesHttp2OverHttps() {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(held.certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverCerts.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            response.use {
                assertEquals("ok", it.body?.string())
                println("M1 negotiated protocol=${it.protocol}")
                assertEquals(Protocol.HTTP_2, it.protocol)
            }
        }
    }

    @Test
    fun factoryDoesNotPinHttp11() {
        val client = HttpClients.create()
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), client.protocols)
    }
}
