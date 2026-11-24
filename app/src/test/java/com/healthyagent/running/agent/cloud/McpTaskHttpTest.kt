package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.mcp.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpTaskHttpTest {
    private val f = McpTaskFixtures
    private lateinit var server: MockWebServer
    private lateinit var client: CloudTaskHttpClient
    private lateinit var context: McpTaskContext
    private lateinit var tlsClient: okhttp3.OkHttpClient
    private var reads = 0
    private val bearer = CloudDeviceBearer("lyd_" + "a".repeat(64))
    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
        }
        val identity = f.identity.copy(origin = "https://127.0.0.1:${server.port}")
        context = f.context(f.intent.copy(identity = identity))
        tlsClient = CloudTaskHttpClient.clientBuilder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).callTimeout(3, TimeUnit.SECONDS).build()
        client = CloudTaskHttpClient(identity.origin, CloudCredentialSource { reads++; bearer }, tlsClient)
    }
    @After fun close() { server.shutdown() }
    private fun enqueue(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body))
    private fun confirmation(row: JsonObject = f.invocation(context)) = buildJsonObject { put("confirmation", row) }.toString()
    private fun decision(status: String = "approved") = buildJsonObject { put("decision", buildJsonObject {
        put("invocationId", f.id(6)); put("status", status)
    }) }.toString()
    private suspend fun failure(block: suspend () -> Unit): CloudTaskFailure {
        try { block() } catch (error: CloudTaskFailure) { return error }; error("应拒绝无效任务通道")
    }

    @Test fun 合成TLS夹具能建立受信本地连接() {
        enqueue("""{"accepted":true}""")
        val request = okhttp3.Request.Builder().url("${context.intent.identity.origin}/fixture")
            .tag(java.util.concurrent.atomic.AtomicBoolean::class.java, java.util.concurrent.atomic.AtomicBoolean(false)).build()
        tlsClient.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    @Test fun 正式任务五条本地HTTPS路由复用原设备凭据且POST不重放() = runBlocking {
        enqueue(confirmation()); val invocation = client.taskConfirmation(context)!!
        enqueue(f.delivery(context)); val delivery = client.takeTaskExchange(context)!!
        val reply = delivery.packet.reply(error = "transport_failed")
        enqueue("""{"accepted":true}"""); client.replyTaskExchange(context, delivery, reply)
        enqueue(decision()); client.decideTaskInvocation(context, invocation, "approve")
        enqueue("""{"accepted":true}"""); client.closeTaskMcp(context)
        val paths = listOf("" to "GET", "/take" to "POST", "/reply" to "POST", "/decision" to "POST", "/close" to "POST")
        paths.forEachIndexed { index, (suffix, method) ->
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/agent/tasks/${f.receipt.cloudTaskId}/mcp$suffix", request.path)
            assertEquals(method, request.method); assertEquals("Bearer ${bearer.value}", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            val body = request.body.readUtf8()
            assertEquals(when (index) { 2 -> reply.toString(); 3 -> invocation.decision(context, "approve").toString(); else -> "" }, body)
            assertFalse(body.contains(f.endpoint)); assertFalse(body.contains("今晚")); assertFalse(body.contains("credential"))
        }
        assertEquals(5, reads); assertEquals(5, server.requestCount)
    }

    @Test fun 提交从落盘任务读取原配置且不上传目录端点及插件密钥() = runBlocking {
        enqueue(buildJsonObject { put("task", buildJsonObject {
            put("id", f.receipt.cloudTaskId); put("status", "running"); put("reason", JsonNull)
            put("acceptedAtMillis", 1000); put("deadlineAtMillis", 31000); put("updatedAtMillis", 1000)
            put("resultState", "none"); put("result", JsonNull)
        }) }.toString(), 202)
        client.submit(context.intent)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        assertEquals("/v1/agent/tasks", request.path)
        assertEquals(f.configuration, Json.parseToJsonElement(body).jsonObject["mcpTools"])
        assertFalse(body.contains(f.endpoint)); assertFalse(body.contains("notes.search"))
        assertEquals(1, server.requestCount)
    }

    @Test fun 非法回执批准与跨任务交付在读取云凭据前拒绝() = runBlocking {
        val delivery = McpTaskWire.delivery(f.delivery(context), context)!!
        val reply = delivery.packet.reply(response = McpHttpReply(200, "{}", null, false))
        val malformed = listOf(JsonObject(reply + ("status" to JsonPrimitive("200"))),
            JsonObject(reply + ("Authorization" to JsonPrimitive("synthetic-plugin-secret"))),
            JsonObject(reply + ("text" to JsonPrimitive("中".repeat(44000)))),
            JsonObject(reply + ("sessionId" to JsonPrimitive("bad\nheader"))),
            delivery.packet.reply(error = "secret-upstream-error"))
        malformed.forEach { assertEquals("invalid_request", failure { client.replyTaskExchange(context, delivery, it) }.code) }
        assertEquals("invalid_request", failure { client.decideTaskInvocation(context, delivery.invocation, "true") }.code)
        val other = McpTaskContext.capture(context.intent.copy(clientRequestId = f.id(30)),
            f.receipt.copy(clientRequestId = f.id(30), cloudTaskId = f.id(31)), listOf(f.service() to f.local))
        assertEquals("invalid_request", failure { client.replyTaskExchange(other, delivery, reply) }.code)
        assertEquals("invalid_request", failure { client.decideTaskInvocation(other, delivery.invocation, "approve") }.code)
        assertEquals(0, reads); assertEquals(0, server.requestCount)
    }

    @Test fun 未选服务换代工具参数篡改与额外字段均不成为可执行交付() = runBlocking {
        val original = f.invocation(context)
        for ((key, value) in listOf("serviceId" to JsonPrimitive(f.id(31)), "generation" to JsonPrimitive(f.id(32)),
            "mode" to JsonPrimitive("read"), "argumentsDigest" to JsonPrimitive("0".repeat(64)), "approved" to JsonPrimitive(true))) {
            enqueue(f.delivery(context, JsonObject(original + (key to value))))
            assertEquals("invalid_response", failure { client.takeTaskExchange(context) }.code)
        }
        enqueue(f.delivery(context, exchange = f.packet(context, """{"query":"另一个参数"}""")))
        assertEquals("invalid_response", failure { client.takeTaskExchange(context) }.code)
        assertEquals(6, server.requestCount)
    }

    @Test fun 云端空槽严格返回空且不把不匹配确认回执变成成功() = runBlocking {
        enqueue("""{"confirmation":null}"""); assertNull(client.taskConfirmation(context))
        enqueue("""{"delivery":null}"""); assertNull(client.takeTaskExchange(context))
        val invocation = McpTaskInvocation.parse(f.invocation(context), context)
        enqueue(decision("denied")); assertEquals("invalid_response", failure { client.decideTaskInvocation(context, invocation, "approve") }.code)
        enqueue("""{"accepted":false}"""); assertEquals("invalid_response", failure { client.closeTaskMcp(context) }.code)
        enqueue("""{"accepted":true,"secret":"synthetic"}"""); failure { client.closeTaskMcp(context) }
        assertEquals(5, server.requestCount)
    }

    @Test fun HTTP错误重定向及失联不自动重发领取或批准() = runBlocking {
        for (status in listOf(302, 408, 503, 409)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "/unexpected")
                .setHeader("Content-Type", "application/json").setBody(if (status == 409) """{"error":"inactive"}""" else """{"error":"unavailable"}"""))
            failure { client.takeTaskExchange(context) }
        }
        val invocation = McpTaskInvocation.parse(f.invocation(context), context)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        failure { client.decideTaskInvocation(context, invocation, "approve") }
        assertEquals(5, server.requestCount)
    }

    @Test fun 超限信封坏UTF8和错误成功状态均不能进入工具传输() = runBlocking {
        enqueue(confirmation() + " ".repeat(65536)); failure { client.taskConfirmation(context) }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
        failure { client.takeTaskExchange(context) }
        enqueue("""{"delivery":null}""", 202); failure { client.takeTaskExchange(context) }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>合成错误</html>"))
        failure { client.taskConfirmation(context) }
        assertEquals(4, server.requestCount)
    }

    @Test fun 取消在途领取会取消原Call而不是启动第二次请求() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { client.takeTaskExchange(context) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertEquals(1, server.requestCount); assertEquals(1, reads)
        val other = f.context()
        assertEquals("credential_unavailable", failure { client.taskConfirmation(other) }.code)
        assertEquals(1, reads)
    }
}
