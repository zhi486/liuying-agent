package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.voice.PcmStreamer
import com.healthyagent.running.agent.voice.VoiceConnectionFailure
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CloudVoiceStreamClientTest {
    private lateinit var server: MockWebServer
    private lateinit var sockets: OkHttpClient
    private lateinit var identity: CloudTaskIdentity
    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false)
            start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0) }
        sockets = CloudTaskHttpClient.clientBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        identity = CloudTaskIdentity("https://127.0.0.1:${server.port}", "owner", "device", "generation")
    }
    @After fun tearDown() { sockets.dispatcher.cancelAll(); server.shutdown() }
    private fun client(recorder: PcmStreamer) = CloudVoiceStreamClient({ identity },
        CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) }, recorder, sockets)

    @Test fun 等待就绪后录音且中间修订只预览最终文字才返回() = runBlocking {
        val audio = CompletableDeferred<ByteString>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"ready"}""") }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                audio.complete(bytes)
                webSocket.send("""{"type":"preview","text":"青松"}""")
                webSocket.send("""{"type":"preview","text":"轻松跑"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == """{"type":"finish"}""") {
                    webSocket.send("""{"type":"completed","text":"轻松跑"}"""); webSocket.close(1000, "结束")
                }
            }
        }))
        var ready = false; var finishing = false; val previews = mutableListOf<String>()
        val value = withTimeout(5000) {
            client(PcmStreamer { _, chunk -> assertTrue(ready); chunk(ByteArray(3200) { 7 }) })
                .recognize(AtomicBoolean(false), { ready = true }, { previews.add(it) }, { finishing = true })
        }
        assertEquals("轻松跑", value); assertTrue(finishing)
        assertEquals(listOf("青松", "轻松跑"), previews)
        assertEquals(3200, audio.await().size)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/agent/voice/stream", request.path)
        assertEquals("fun-asr-v1", request.getHeader("X-Voice-Consent"))
        assertEquals("Bearer lyd_" + "a".repeat(64), request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun 连接失败不启动麦克风且没有备用供应商或重试() = runBlocking {
        for (reply in listOf(MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")))) {
            server.enqueue(reply)
            var recorded = false
            val failure = runCatching {
                withTimeout(5000) { client(PcmStreamer { _, _ -> recorded = true })
                    .recognize(AtomicBoolean(false), {}, {}, {}) }
            }.exceptionOrNull()
            assertNotNull(failure); assertFalse(recorded)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun 录音中取消释放录音适配器且不重发() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"ready"}""") }
        }))
        val started = CompletableDeferred<Unit>(); var released = false
        val pending = async {
            client(PcmStreamer { _, _ ->
                try { started.complete(Unit); awaitCancellation() } finally { released = true }
            }).recognize(AtomicBoolean(false), {}, {}, {})
        }
        withTimeout(5000) { started.await() }; pending.cancelAndJoin()
        assertTrue(released); assertEquals(1, server.requestCount)
    }

    @Test fun 握手拒绝保留状态码但不暴露响应正文且不录音重试() = runBlocking {
        val codes = listOf(401, 403, 404, 429, 503)
        for (code in codes) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("私密响应不应进入提示"))
            var recorded = false
            val failure = runCatching {
                withTimeout(5000) { client(PcmStreamer { _, _ -> recorded = true })
                    .recognize(AtomicBoolean(false), {}, {}, {}) }
            }.exceptionOrNull()
            assertTrue(failure is VoiceConnectionFailure)
            failure as VoiceConnectionFailure
            assertEquals(code, failure.httpStatus)
            assertTrue(failure.userMessage.contains(code.toString()))
            assertFalse(failure.userMessage.contains("私密响应"))
            assertNull(failure.cause)
            assertFalse(recorded)
        }
        assertEquals(codes.size, server.requestCount)
    }

    @Test fun 录音中服务器断流会释放麦克风并拒绝半成品() = runBlocking {
        val provider = CompletableDeferred<WebSocket>(); var released = false
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                provider.complete(webSocket); webSocket.send("""{"type":"ready"}""")
            }
        }))
        val failure = runCatching {
            withTimeout(5000) {
                client(PcmStreamer { _, _ ->
                    try { provider.await().close(1011, "断流"); awaitCancellation() } finally { released = true }
                }).recognize(AtomicBoolean(false), {}, {}, {})
            }
        }.exceptionOrNull()
        assertNotNull(failure); assertFalse(failure is TimeoutCancellationException)
        assertTrue(released); assertEquals(1, server.requestCount)
    }
}
