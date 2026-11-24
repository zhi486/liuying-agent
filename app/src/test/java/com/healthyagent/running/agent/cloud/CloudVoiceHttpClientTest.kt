package com.healthyagent.running.agent.cloud

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CloudVoiceHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CloudVoiceHttpClient

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false)
            start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0) }
        val identity = CloudTaskIdentity("https://127.0.0.1:${server.port}", "owner", "device", "generation")
        client = CloudVoiceHttpClient({ identity }, CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) },
            CloudTaskHttpClient.clientBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .callTimeout(3, TimeUnit.SECONDS).build())
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun 单次HTTPS只上传PCM与设备授权不发送聊天任务() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"text\":\"今晚轻松跑\"}"))
        val pcm = ByteArray(3200) { 7 }
        assertEquals("今晚轻松跑", client.transcribe(pcm))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/agent/voice", request.path)
        assertEquals("baidu-asr-v1", request.getHeader("X-Voice-Consent"))
        assertEquals("Bearer lyd_" + "a".repeat(64), request.getHeader("Authorization"))
        assertEquals("audio/pcm;rate=16000", request.getHeader("Content-Type"))
        assertArrayEquals(pcm, request.body.readByteArray())
        assertEquals(1, server.requestCount)
    }

    @Test fun 重定向与无效响应均不重试也不泄露响应正文() = runBlocking {
        val replies = listOf(
            MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")),
            MockResponse().setHeader("Content-Type", "application/json").setBody("{\"text\":\"\"}"),
            MockResponse().setHeader("Content-Type", "application/json").setBody("{\"text\":\"秘密\",\"token\":\"秘密\"}"),
            MockResponse().setHeader("Content-Type", "application/json").setBody("x".repeat(16385)),
            MockResponse().setHeader("Content-Type", "text/html").setBody("秘密"),
        )
        for (reply in replies) {
            server.enqueue(reply)
            val failure = runCatching { client.transcribe(ByteArray(3200)) }.exceptionOrNull()
            assertEquals("语音服务暂不可用", failure?.message)
        }
        assertEquals(replies.size, server.requestCount)
    }

    @Test fun 用户取消会终止等待并且不重试() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { client.transcribe(ByteArray(3200)) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }
}
