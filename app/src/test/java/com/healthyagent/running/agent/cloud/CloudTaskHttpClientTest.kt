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
    private lateinit var streamingClient: CloudTaskHttpClient
    private var credentialReads = 0
    private val bearer = CloudDeviceBearer("lyd_" + "a".repeat(64))
    private val credentials = CloudCredentialSource { credentialReads++; bearer }

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
        }
        val origin = "https://127.0.0.1:${server.port}"
        identity = CloudTaskIdentity(origin, "owner", "device", "generation")
        val calls = CloudTaskHttpClient.clientBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).callTimeout(3, TimeUnit.SECONDS).build()
        client = CloudTaskHttpClient(origin, credentials, calls)
        streamingClient = CloudTaskHttpClient(origin, credentials, calls, true)
    }
    @After fun tearDown() { server.shutdown() }
    private fun enqueue(view: CloudTaskResponse = response(), code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(envelope(view)))
    }
    private suspend fun failure(block: suspend () -> Unit): CloudTaskFailure {
        try { block() } catch (failure: CloudTaskFailure) { return failure }
        error("应该失败")
    }

    @Test fun 清理只向原请求键发送空POST并验证匹配回执且重复调用不创建任务() = runBlocking {
        val requestId = "11111111-1111-4111-8111-111111111111"
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"requestId":"$requestId","purged":true}"""))
            client.purge(identity, requestId)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1/agent/requests/$requestId/purge", request.path)
            assertEquals("Bearer ${bearer.value}", request.getHeader("Authorization"))
            assertEquals("", request.body.readUtf8())
        }
        assertEquals(2, server.requestCount)
        assertEquals(2, credentialReads)
    }

    @Test fun 清理回执严验原键真正布尔值字段集合状态码与正文上限() = runBlocking {
        val requestId = "11111111-1111-4111-8111-111111111111"
        val invalid = listOf(
            """{"requestId":"$testCloudId","purged":true}""",
            """{"requestId":"$requestId","purged":false}""",
            """{"requestId":"$requestId","purged":"true"}""",
            """{"requestId":"$requestId","purged":1}""",
            """{"requestId":"$requestId"}""",
            """{"purged":true}""",
            """{"requestId":"$requestId","purged":true,"answer":"合成泄漏正文"}""",
            "[]", "null", "损坏的合成JSON", "x".repeat(4097),
        )
        for (body in invalid) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val before = server.requestCount
            assertEquals("invalid_response", failure { client.purge(identity, requestId) }.code)
            assertEquals(before + 1, server.requestCount)
        }
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json")
            .setBody("""{"requestId":"$requestId","purged":true}"""))
        assertEquals("invalid_response", failure { client.purge(identity, requestId) }.code)
        assertEquals(invalid.size + 1, server.requestCount)
    }

    @Test fun 清理失败重定向断流及错误正文不会重试或暴露正文() = runBlocking {
        val requestId = "11111111-1111-4111-8111-111111111111"
        val canary = "synthetic-private-purge-canary"
        for (code in listOf(301, 302, 307, 308, 401, 408, 421, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                .setHeader("Location", server.url("/leak")).setHeader("Retry-After", "0")
                .setBody("""{"error":"$canary"}"""))
            val before = server.requestCount
            val error = failure { client.purge(identity, requestId) }
            assertEquals(before + 1, server.requestCount)
            assertFalse(error.toString().contains(canary)); assertNull(error.cause)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/agent/requests/$requestId/purge", request.path)
            assertEquals("", request.body.readUtf8())
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"requestId":"$requestId","purged":true}""").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val before = server.requestCount
        failure { client.purge(identity, requestId) }
        assertEquals(before + 1, server.requestCount)
    }

    @Test fun 清理拒绝非法原键及异源身份且此前不读取凭据不发网() = runBlocking {
        assertTrue(runCatching { client.purge(identity, "../tasks") }.isFailure)
        assertEquals("credential_unavailable", failure {
            client.purge(identity.copy(origin = "https://other.example"), testCloudId)
        }.code)
        assertEquals(0, credentialReads)
        assertEquals(0, server.requestCount)
    }

    @Test fun 只有显式启用流式的提交带传输头且SSE通过原私密凭据读取() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val local = MockWebServer()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        local.useHttps(tls.sslSocketFactory(), false)
        local.start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
        try {
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            val endpoint = "https://127.0.0.1:${local.port}"
            val source = identity.copy(origin = endpoint)
            val streaming = CloudTaskHttpClient(endpoint, credentials, CloudTaskHttpClient.clientBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build(), true)
            val intent = CloudTaskIntent.confirm(source, "合成输入", true, 1000)
            local.enqueue(MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json")
                .setBody(envelope(response("queued", "none"))))
            streaming.submit(intent)
            val post = local.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("task_events_v1", post.getHeader("X-Agent-Stream"))
            assertEquals(intent.submission().toString(), post.body.readUtf8())
            val end = """{"version":"task_events_v1","taskId":"$testCloudId","sequence":1,"round":0,"kind":"task_end","status":"blocked"}"""
            local.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("id: 1\nevent: task_end\ndata: $end\n\n"))
            val events = mutableListOf<CloudTaskEvent>()
            streaming.events(source, testCloudId, 0) { events += it }
            assertEquals("blocked", events.single().status)
            val get = local.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/agent/tasks/$testCloudId/events/0", get.path)
            assertEquals("GET", get.method); assertEquals("text/event-stream", get.getHeader("Accept"))
            assertEquals("Bearer ${bearer.value}", get.getHeader("Authorization"))
            assertEquals(2, local.requestCount)
        } finally { local.shutdown() }
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

    @Test fun 流式错误与断流只返回静态失败且不自动重连() = runBlocking {
        val privateText = "synthetic-private-stream-canary"
        listOf(
            MockResponse().setResponseCode(307).setHeader("Location", server.url("/leak")).setBody(privateText),
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("""{"error":"unavailable"}"""),
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))),
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: 1\nevent: text_delta\ndata: $privateText"),
        ).forEach { mock ->
            server.enqueue(mock)
            val before = server.requestCount
            val failure = failure { streamingClient.events(identity, testCloudId, 0) { error("不应消费错误正文") } }
            assertFalse(failure.toString().contains(privateText))
            assertNull(failure.cause)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun 流式同步读取在响应头前及正文等待中均可取消且不控制任务() = runBlocking {
        for (mock in listOf(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": heartbeat\n\n")
                .setBodyDelay(2, TimeUnit.SECONDS))) {
            server.enqueue(mock)
            val operation = async(Dispatchers.Default) { streamingClient.events(identity, testCloudId, 0) {} }
            val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            assertEquals("GET", request!!.method)
            assertEquals("/v1/agent/tasks/$testCloudId/events/0", request.path)
            withTimeout(1500) { operation.cancelAndJoin() }
            assertTrue(operation.isCancelled)
        }
        assertEquals(2, server.requestCount)
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

    @Test fun 原生记忆往返复用私密HTTPS且正文只含协议代际和固定工具回执() = runBlocking {
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/memory-tools-v1/transport.json")).readText()).jsonObject
        val request = fixture.getValue("request").jsonObject.getValue("memory").jsonObject
        val id = request.getValue("taskId").jsonPrimitive.content
        val generation = request.getValue("generation").jsonPrimitive.content
        val conversationId = request.getValue("requestId").jsonPrimitive.content
        val intent = CloudTaskIntent.confirm(identity, "我喜欢夜跑", true, 1000).copy(conversationId = conversationId,
            conversationJson = """{"version":"conversation_v1","id":"$conversationId","approved":true,"messages":[]}""")
        enqueue(response("queued", "none"), 202)
        client.submitMemory(intent, null, generation)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(fixture.getValue("request").toString()))
        val tool = client.memory(identity, id)!!
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"accepted":true}"""))
        client.replyMemory(identity, tool, fixture.getValue("reply").jsonObject.getValue("observation").jsonObject)
        val submit = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(CloudMemoryTool.submission(intent, null, generation), Json.parseToJsonElement(submit.body.readUtf8()))
        val poll = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", poll.method); assertEquals("/v1/agent/tasks/$id/memory", poll.path)
        val post = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", post.method); assertEquals(poll.path, post.path)
        assertEquals(fixture.getValue("reply"), Json.parseToJsonElement(post.body.readUtf8()))
        assertEquals("Bearer ${bearer.value}", post.getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test fun 事实提交只显式声明范围且不上传本机授权身份() = runBlocking {
        val id = "11111111-1111-4111-8111-111111111111"
        val intent = CloudTaskIntent.confirm(identity, "我的专业是软件工程", true, 1000).copy(conversationId = id,
            conversationJson = """{"version":"conversation_v1","id":"$id","approved":true,"messages":[]}""")
        enqueue(response("queued", "none"), 202)
        client.submitMemory(intent, null, id, true)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val wire = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(CloudMemoryTool.submission(intent, null, id, true), wire)
        assertEquals("Bearer ${bearer.value}", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun 记忆回执POST断流重定向及错误不会隐式重试或泄漏正文() = runBlocking {
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/memory-tools-v1/transport.json")).readText()).jsonObject
        val id = fixture.getValue("request").jsonObject.getValue("memory").jsonObject.getValue("taskId").jsonPrimitive.content
        val tool = CloudMemoryTool.parse(fixture.getValue("request").toString(), id)!!
        for (code in listOf(307, 503, 200)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                .setHeader("Location", server.url("/leak")).setBody(if (code == 200) """{"accepted":false}""" else """{"error":"unavailable"}"""))
            val before = server.requestCount
            failure { client.replyMemory(identity, tool, fixture.getValue("reply").jsonObject.getValue("observation").jsonObject) }
            assertEquals(before + 1, server.requestCount)
        }
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
