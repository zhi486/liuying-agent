package com.healthyagent.running.agent.cloud

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.healthyagent.running.agent.memory.MemoryRef
import kotlinx.serialization.json.*

class CloudTaskHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var identity: CloudTaskIdentity
    private lateinit var client: CloudTaskHttpClient
    private var credentialReads = 0
    private val bearer = CloudDeviceBearer("lyd_" + "a".repeat(64))
    private val credentials = CloudCredentialSource { credentialReads++; bearer }

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val origin = server.url("/").toString().removeSuffix("/")
        identity = CloudTaskIdentity(origin, "owner", "device", "generation")
        client = CloudTaskHttpClient(origin, credentials, CloudTaskHttpClient.clientBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).callTimeout(3, TimeUnit.SECONDS).build())
    }
    @After fun tearDown() { server.shutdown() }
    private fun enqueue(view: CloudTaskResponse = response(), code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(envelope(view)))
    }
    private suspend fun failure(block: suspend () -> Unit): CloudTaskFailure {
        try { block() } catch (failure: CloudTaskFailure) { return failure }
        error("应该失败")
    }

    @Test fun 真实HTTPS只传单次选中记忆且不把身份写入正文() = runBlocking {
        val id = "11111111-1111-4111-8111-111111111111"
        val intent = CloudTaskIntent.confirmSelected(identity, "今晚怎么跑", listOf(MemoryRef(id, 2)), 1000)
        val context = Json.parseToJsonElement("""{"version":"selected_memories_v1","approved":true,"items":[{"id":"$id","version":2,"content":"喜欢晚跑"}]}""").jsonObject
        enqueue(response("queued", "none"), 202)
        client.submitSelected(intent, context)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(intent.submission(context), body)
        assertEquals(JsonPrimitive(false), body.getValue("consent").jsonObject["runSummaries"])
        assertFalse(body.containsKey("ownerId"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `真实本地HTTPS六路由与授权头符合契约`() = runBlocking {
        val intent = CloudTaskIntent.confirm(identity, "仅传这段文本", true, 1000)
        enqueue(response("queued", "none"), 202); client.submit(intent)
        enqueue(); client.find(identity, intent.clientRequestId)
        enqueue(); client.get(identity, testCloudId)
        enqueue(response("cancelled", "none")); client.control(identity, testCloudId, "cancel")
        enqueue(response(state = "withdrawn")); client.control(identity, testCloudId, "withdraw")
        enqueue(response(state = "acknowledged")); client.control(identity, testCloudId, "acknowledge")
        val expected = listOf("POST" to "/tasks", "GET" to "/requests/${intent.clientRequestId}", "GET" to "/tasks/$testCloudId",
            "POST" to "/tasks/$testCloudId/cancel", "POST" to "/tasks/$testCloudId/withdraw", "POST" to "/tasks/$testCloudId/acknowledge")
        expected.forEachIndexed { index, pair ->
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(pair.first, request.method)
            assertEquals("/v1/agent${pair.second}", request.path)
            assertEquals("Bearer ${bearer.value}", request.getHeader("Authorization"))
            assertEquals(if (index == 0) intent.submission().toString() else "", request.body.readUtf8())
        }
        assertEquals(6, server.requestCount)
        assertFalse(bearer.toString().contains(bearer.value))
    }

    @Test fun `重定向认证408及503均不得隐式发第二次POST`() = runBlocking {
        listOf(301, 302, 307, 308, 401, 408, 421, 503).forEach { code ->
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                .setHeader("Location", server.url("/leak")).setHeader("Retry-After", "0").setHeader("WWW-Authenticate", "Basic realm=test")
                .setBody(if (code == 401) "{\"error\":\"unauthenticated\"}" else "{\"error\":\"unavailable\"}"))
            failure { client.submit(CloudTaskIntent.confirm(identity, "重试测试", true, 1000)) }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun `错误正文非法UTF8及超限仅返回静态错误`() = runBlocking {
        val canary = "synthetic-private-canary"
        listOf(MockResponse().setResponseCode(500).setBody(canary),
            MockResponse().setResponseCode(503).setBody("{\"error\":\"$canary\"}"),
            MockResponse().setBody("x".repeat(65537)),
            MockResponse().setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))),
            MockResponse().setBody(envelope(response())).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        ).forEach { mock ->
            server.enqueue(mock.setHeader("Content-Type", "application/json"))
            val error = failure { client.get(identity, testCloudId) }
            assertFalse(error.toString().contains(canary))
            assertNull(error.cause)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun `异源身份在读密钥和发网之前拒绝`() = runBlocking {
        assertEquals("credential_unavailable", failure { client.find(identity.copy(origin = "https://other.example"), testCloudId) }.code)
        assertEquals(0, credentialReads)
        assertEquals(0, server.requestCount)
        assertThrows(IllegalArgumentException::class.java) { CloudTaskHttpClient("http://localhost", credentials) }
        assertThrows(IllegalArgumentException::class.java) { CloudTaskHttpClient("https://localhost:443", credentials) }
        Unit
    }

    @Test fun `真实TLS不信任测试证书时拒绝连接`() = runBlocking {
        val untrusted = CloudTaskHttpClient(identity.origin, credentials)
        assertEquals("tls_failed", failure { untrusted.get(identity, testCloudId) }.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun `取消协程会终止底层等待而不生成额外请求`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = async(Dispatchers.Default) { client.get(identity, testCloudId) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun `鉴权与格式错误保留独立分类且不重试`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"error":"unauthenticated"}"""))
        assertEquals("unauthenticated", failure { client.get(identity, testCloudId) }.code)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
            .setBody("非预期网页"))
        assertEquals("invalid_response", failure { client.get(identity, testCloudId) }.code)
        assertEquals(2, server.requestCount)
    }

    @Test fun `读取超时分类明确且只请求一次`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals("network_timeout", failure { client.get(identity, testCloudId) }.code)
        assertEquals(1, server.requestCount)
    }

    @Test fun `收到响应头后正文超时不误报格式错误`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(envelope(response())).setBodyDelay(5, TimeUnit.SECONDS))
        assertEquals("network_timeout", failure { client.get(identity, testCloudId) }.code)
        assertEquals(1, server.requestCount)
    }
}
