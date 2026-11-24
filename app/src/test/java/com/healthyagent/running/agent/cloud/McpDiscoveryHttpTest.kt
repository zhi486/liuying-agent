package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.mcp.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpDiscoveryHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var identity: CloudTaskIdentity
    private lateinit var client: CloudTaskHttpClient
    private lateinit var scope: McpPhoneScope
    private var reads = 0
    private val bearer = CloudDeviceBearer("lyd_" + "a".repeat(64))
    private val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/mcp-phone-v1/discovery.json")).readText()).jsonObject
    private val row = fixture.getValue("response").jsonObject.getValue("discovery").jsonObject
    private val wire = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/mcp-phone-v1/transport.json")).readText()).jsonObject

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
        }
        identity = CloudTaskIdentity("https://127.0.0.1:${server.port}", "owner", "device", "credential")
        scope = McpPhoneScope(identity, value("requestId"), value("requestId"), value("serviceId"), value("revision"),
            value("generation"), McpEndpoint.parse(value("endpoint")))
        client = CloudTaskHttpClient(identity.origin, CloudCredentialSource { reads++; bearer }, CloudTaskHttpClient.clientBuilder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).callTimeout(3, TimeUnit.SECONDS).build())
    }
    @After fun tearDown() { server.shutdown() }
    private fun value(key: String) = row.getValue(key).jsonPrimitive.content
    private fun envelope(view: JsonObject = row) = buildJsonObject { put("discovery", view) }.toString()
    private fun enqueue(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body))

    private fun managementService() = buildJsonObject {
        put("id", scope.serviceId); put("revision", scope.revision); put("endpoint", scope.endpoint.toString()); put("enabled", false)
        put("catalog", JsonNull); put("grants", JsonArray(emptyList()))
    }
    @Test fun 清理元数据只用固定summary路由且不能用于完整目录或接受未知字段() = runBlocking {
        val summary = JsonObject(managementService().filterKeys { it !in setOf("catalog", "grants") })
        enqueue(buildJsonObject { put("service", summary) }.toString())
        assertEquals(summary, client.getService(identity, scope.serviceId, summaryOnly = true))
        assertEquals("/v1/agent/mcp/services/${scope.serviceId}/summary", server.takeRequest().path)
        for (invalid in listOf(
            managementService(),
            JsonObject(summary + ("id" to JsonPrimitive(scope.requestId))),
            JsonObject(summary + ("revision" to JsonPrimitive("broken"))),
            JsonObject(summary + ("endpoint" to JsonPrimitive("https://127.0.0.1/mcp"))),
            JsonObject(summary + ("enabled" to JsonPrimitive("true"))),
        )) {
            enqueue(buildJsonObject { put("service", invalid) }.toString())
            assertEquals("invalid_response", failure { client.getService(identity, scope.serviceId, true) }.code)
        }
        enqueue(buildJsonObject { put("service", summary) }.toString())
        assertEquals("invalid_response", failure { client.getService(identity, scope.serviceId) }.code)
        assertEquals(7, server.requestCount)
    }
    @Test fun 管理接口真实HTTPS使用固定路由和单次POST且不转发插件密钥() = runBlocking {
        val service = managementService()
        val envelope = buildJsonObject { put("service", service) }.toString()
        enqueue(buildJsonObject {
            put("services", JsonArray(listOf(JsonObject(service.filterKeys { it !in setOf("catalog", "grants") }))))
            put("nextCursor", JsonNull); put("discoveryAvailable", false); put("phoneDiscoveryAvailable", true)
        }.toString())
        client.listServices(identity, null)
        enqueue(envelope); client.getService(identity, scope.serviceId)
        enqueue(envelope, 201); client.changeService(identity, null, "register", buildJsonObject { put("endpoint", scope.endpoint.toString()) })
        enqueue(buildJsonObject { put("service", JsonObject(service + ("revision" to JsonPrimitive(java.util.UUID.randomUUID().toString())))) }.toString())
        client.changeService(identity, scope.serviceId, "disable", buildJsonObject { put("revision", scope.revision) })
        val expected = listOf("mcp/services" to "GET", "mcp/services/${scope.serviceId}" to "GET",
            "mcp/services" to "POST", "mcp/services/${scope.serviceId}/disable" to "POST")
        expected.forEach { (path, method) ->
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/agent/$path", request.path); assertEquals(method, request.method)
            assertEquals("Bearer ${bearer.value}", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertFalse(body.contains("identityToken")); assertFalse(body.contains("generation")); assertFalse(body.contains("synthetic-plugin"))
        }
        assertEquals(4, server.requestCount); assertEquals(4, reads)
    }
    @Test fun 管理响应拒绝跨服务未知字段和无效成功状态() = runBlocking {
        val service = managementService()
        for ((row, status) in listOf(
            JsonObject(service + ("id" to JsonPrimitive(scope.requestId))) to 200,
            JsonObject(service + ("bearer" to JsonPrimitive("private-upstream"))) to 200,
            service to 201,
        )) {
            enqueue(buildJsonObject { put("service", row) }.toString(), status)
            failure { client.getService(identity, scope.serviceId) }
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun 非法管理请求在读取凭据前拒绝且目录失败不自动重试() = runBlocking {
        try { client.listServices(identity, "../secret"); fail("应拒绝非法游标") } catch (_: IllegalArgumentException) { }
        try { client.changeService(identity, scope.serviceId, "unexpected", buildJsonObject {}); fail("应拒绝未知操作") } catch (_: CloudTaskFailure) { }
        assertEquals(0, reads)
        enqueue("""{"error":"unavailable"}""", 503)
        assertEquals("unavailable", failure { client.listServices(identity, null) }.code)
        assertEquals(1, server.requestCount)
    }
    @Test fun 保存权限真实HTTPS核对所选工具模式和新版本而不接受错误成功回执() = runBlocking {
        val selection = JsonArray(listOf(buildJsonObject { put("name", "notes.search"); put("mode", "confirm") }))
        val body = buildJsonObject { put("revision", scope.revision); put("confirmed", true); put("selection", selection) }
        val approved = JsonObject(managementService() + mapOf("revision" to JsonPrimitive(java.util.UUID.randomUUID().toString()),
            "enabled" to JsonPrimitive(true), "catalog" to buildJsonObject {
                put("fingerprint", "a".repeat(64)); put("tools", JsonArray(listOf(buildJsonObject {
                    put("name", "notes.search"); put("definition", buildJsonObject {
                        put("name", "notes.search"); put("inputSchema", buildJsonObject { put("type", "object") })
                    })
                })))
            }, "grants" to JsonArray(listOf(buildJsonObject {
                put("name", "notes.search"); put("mode", "confirm"); put("modelName", "mcp_${scope.serviceId.replace("-", "")}_0")
            }))))
        enqueue(buildJsonObject { put("service", approved) }.toString())
        assertEquals(JsonPrimitive(true), client.changeService(identity, scope.serviceId, "approve", body)["enabled"])
        val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/agent/mcp/services/${scope.serviceId}/approve", sent.path)
        assertEquals(body.toString(), sent.body.readUtf8())
        enqueue(buildJsonObject { put("service", managementService()) }.toString())
        assertEquals("invalid_response", failure { client.changeService(identity, scope.serviceId, "approve", body) }.code)
        assertEquals(2, server.requestCount)
    }
    private fun packet(target: McpPhoneScope = scope) = JsonObject(wire.getValue("request").jsonObject + mapOf(
        "requestId" to JsonPrimitive(target.requestId), "taskId" to JsonPrimitive(target.taskId),
        "serviceId" to JsonPrimitive(target.serviceId), "revision" to JsonPrimitive(target.revision),
        "generation" to JsonPrimitive(target.generation), "endpoint" to JsonPrimitive(target.endpoint.toString())))
    private suspend fun failure(block: suspend () -> Unit): CloudTaskFailure {
        try { block() } catch (error: CloudTaskFailure) { return error }; error("应拒绝无效发现协议")
    }

    @Test fun 地址变更及移除真实HTTPS只发送审核版本并严格核对成功回执() = runBlocking {
        val address = "https://changed.example.com/mcp"
        val body = buildJsonObject { put("revision", scope.revision); put("endpoint", address) }
        val changed = JsonObject(managementService() + mapOf("endpoint" to JsonPrimitive(address),
            "revision" to JsonPrimitive(java.util.UUID.randomUUID().toString())))
        enqueue(buildJsonObject { put("service", changed) }.toString())
        assertEquals(JsonPrimitive(address), client.changeService(identity, scope.serviceId, "endpoint", body)["endpoint"])
        val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/agent/mcp/services/${scope.serviceId}/endpoint", sent.path)
        assertEquals(body.toString(), sent.body.readUtf8()); assertEquals("POST", sent.method)
        enqueue("""{"removed":true}""")
        val remove = buildJsonObject { put("revision", scope.revision) }
        assertEquals(JsonPrimitive(true), client.changeService(identity, scope.serviceId, "remove", remove)["removed"])
        val removed = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/agent/mcp/services/${scope.serviceId}/remove", removed.path)
        assertEquals(remove.toString(), removed.body.readUtf8()); assertEquals("POST", removed.method)
        for (invalid in listOf("""{"removed":false}""", """{"removed":"true"}""", """{"removed":true,"secret":"synthetic"}""", """{"service":{}}""")) {
            enqueue(invalid)
            assertEquals("invalid_response", failure { client.changeService(identity, scope.serviceId, "remove", remove) }.code)
        }
        for (invalid in listOf(managementService(), JsonObject(changed + ("revision" to JsonPrimitive(scope.revision))))) {
            enqueue(buildJsonObject { put("service", invalid) }.toString())
            assertEquals("invalid_response", failure { client.changeService(identity, scope.serviceId, "endpoint", body) }.code)
        }
        assertEquals(8, server.requestCount)
    }
    @Test fun 非法新端点和未知移除字段不读凭据且失败的移除不重试() = runBlocking {
        try { client.changeService(identity, scope.serviceId, "endpoint", buildJsonObject { put("revision", scope.revision); put("endpoint", "https://127.0.0.1/mcp") }); fail("应拒绝私网") } catch (_: McpTransportFailure) { }
        try { client.changeService(identity, scope.serviceId, "remove", buildJsonObject { put("revision", scope.revision); put("context", "a".repeat(64)) }); fail("不得上传本机代次") } catch (_: IllegalArgumentException) { }
        assertEquals(0, reads)
        enqueue("""{"error":"unavailable"}""", 503)
        assertEquals("unavailable", failure { client.changeService(identity, scope.serviceId, "remove", buildJsonObject { put("revision", scope.revision) }) }.code)
        assertEquals(1, server.requestCount)
    }

    @Test fun 固定发现夹具严格校验绑定字段和所有状态() {
        assertFalse(McpDiscoveryView.parse(envelope(), scope).terminal)
        assertFalse(McpDiscoveryView.parse(envelope(JsonObject(row + mapOf("deadlineAtMillis" to JsonPrimitive(1792300010000L)))), scope).terminal)
        assertTrue(McpDiscoveryView.parse(envelope(JsonObject(row + mapOf("status" to JsonPrimitive("completed"),
            "resultRevision" to JsonPrimitive(scope.revision)))), scope).terminal)
        for (patch in listOf(mapOf("endpoint" to JsonPrimitive("https://changed.example.com/mcp")),
            mapOf("generation" to JsonPrimitive(scope.revision)), mapOf("secret" to JsonPrimitive("private")),
            mapOf("deadlineAtMillis" to JsonPrimitive(1792300010001L)), mapOf("startedAtMillis" to JsonPrimitive("1792300000000")),
            mapOf("status" to JsonPrimitive("completed")), mapOf("status" to JsonPrimitive("unconfirmed")),
            mapOf("resultRevision" to JsonPrimitive(scope.revision)), mapOf("reason" to JsonPrimitive("private")))) {
            try { McpDiscoveryView.parse(envelope(JsonObject(row + patch)), scope); fail("应拒绝不匹配视图") }
            catch (error: CloudTaskFailure) { assertEquals("invalid_response", error.code) }
        }
        val unknown = JsonObject(row + mapOf("status" to JsonPrimitive("unconfirmed"), "reason" to JsonPrimitive("interrupted")))
        assertEquals("interrupted", McpDiscoveryView.parse(envelope(unknown), scope).reason)
    }

    @Test fun 四条真实本地HTTPS路由不上传身份端点和插件密钥() = runBlocking {
        enqueue(envelope(), 202); client.startDiscovery(scope)
        enqueue(envelope()); client.discovery(scope)
        enqueue(buildJsonObject { put("exchange", packet()) }.toString()); val request = client.takeDiscovery(scope)!!
        val reply = request.reply(error = "transport_failed")
        enqueue("""{"accepted":true}"""); client.replyDiscovery(scope, request, reply)
        val suffixes = listOf("" to "POST", "/${scope.requestId}" to "GET", "/${scope.requestId}/take" to "POST", "/${scope.requestId}/reply" to "POST")
        suffixes.forEachIndexed { index, (suffix, method) ->
            val actual = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/agent/mcp/discoveries$suffix", actual.path); assertEquals(method, actual.method)
            assertEquals("Bearer ${bearer.value}", actual.getHeader("Authorization"))
            val body = actual.body.readUtf8()
            assertEquals(when (index) { 0 -> fixture.getValue("input").toString(); 3 -> reply.toString(); else -> "" }, body)
            assertFalse(body.contains("tools.example.com")); assertFalse(body.contains("owner")); assertFalse(body.contains("credential"))
        }
        assertEquals(4, reads); assertEquals(4, server.requestCount)
    }

    @Test fun 交付拒绝跨配置代次或端点且不接受未知信封字段() = runBlocking {
        for (body in listOf(buildJsonObject { put("exchange", packet(scope.copy(generation = scope.revision))) },
            buildJsonObject { put("exchange", packet(scope.copy(endpoint = McpEndpoint.parse("https://changed.example.com/mcp")))) },
            buildJsonObject { put("exchange", JsonNull); put("secret", "private") })) {
            enqueue(body.toString()); failure { client.takeDiscovery(scope) }
        }
        enqueue("""{"exchange":null}"""); assertNull(client.takeDiscovery(scope))
        assertEquals(4, server.requestCount)
    }

    @Test fun 非规范回执在读取云凭据前拒绝() = runBlocking {
        val request = McpPhoneRequest.parse(packet().toString())
        val valid = request.reply(response = McpHttpReply(200, "{}", null, false))
        for (patch in listOf(mapOf("status" to JsonPrimitive("200")), mapOf("status" to JsonPrimitive(500)),
            mapOf("text" to JsonPrimitive("中".repeat(44000))), mapOf("sessionId" to JsonPrimitive("bad\nheader")),
            mapOf("Authorization" to JsonPrimitive("private")), mapOf("text" to JsonPrimitive(""))))
            assertEquals("invalid_request", failure { client.replyDiscovery(scope, request, JsonObject(valid + patch)) }.code)
        failure { client.replyDiscovery(scope, request, request.reply(error = "private-upstream-message")) }
        assertEquals(0, reads); assertEquals(0, server.requestCount)
    }

    @Test fun 发现重定向和忙碌响应不隐式重发POST() = runBlocking {
        for (code in listOf(302, 408, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "/unexpected")
                .setHeader("Content-Type", "application/json").setBody("""{"error":"unavailable"}"""))
            failure { client.startDiscovery(scope) }
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun origin改变和非发现task绑定不读取凭据() = runBlocking {
        failure { client.startDiscovery(scope.copy(identity = identity.copy(origin = "https://other.example.com"))) }
        failure { client.startDiscovery(scope.copy(taskId = scope.revision)) }
        assertEquals(0, reads); assertEquals(0, server.requestCount)
    }

    @Test fun 回执未知或响应超限不改写为成功() = runBlocking {
        val request = McpPhoneRequest.parse(packet().toString())
        enqueue("""{"accepted":false}""")
        failure { client.replyDiscovery(scope, request, request.reply(error = "transport_failed")) }
        enqueue(envelope() + " ".repeat(65536)); failure { client.discovery(scope) }
        assertEquals(2, server.requestCount)
    }

    @Test fun 同步服务通过真实本地HTTPS驱动四次合成插件交换且凭据不回传云() = runBlocking {
        val files = mutableMapOf<String, ByteArray>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val config = McpPrivateConfiguration({ files[it]?.clone() }, { name, data -> files[name] = data.clone() }, { key })
        val slot = McpPrivateSlot(identity, scope.serviceId)
        config.save(slot, config.status(slot).context, scope.endpoint, McpBearer("synthetic-plugin-key"))
        val completed = AtomicInteger(); var discovered = row
        val methods = listOf("initialize", "notifications/initialized", "tools/list", "DELETE")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                val body = request.body.readUtf8()
                assertFalse(body.contains("synthetic-plugin-key"))
                if (path.endsWith("/discoveries")) {
                    val input = Json.parseToJsonElement(body).jsonObject
                    discovered = JsonObject(row + input)
                    return MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json").setBody(envelope(discovered))
                }
                if (path.endsWith("/reply")) { completed.incrementAndGet(); return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"accepted":true}""") }
                if (path.endsWith("/take")) {
                    val index = completed.get(); val method = methods[index]
                    val next = JsonObject(wire.getValue("request").jsonObject + mapOf(
                        "requestId" to discovered.getValue("requestId"), "taskId" to discovered.getValue("requestId"),
                        "generation" to discovered.getValue("generation"), "exchangeId" to JsonPrimitive(java.util.UUID.randomUUID().toString()),
                        "method" to JsonPrimitive(if (method == "DELETE") "DELETE" else "POST"), "body" to
                            if (method == "DELETE") JsonNull else JsonPrimitive(buildJsonObject { put("jsonrpc", "2.0");
                                if (index != 1) put("id", index); put("method", method); put("params", buildJsonObject {}) }.toString())))
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject { put("exchange", next) }.toString())
                }
                val view = if (completed.get() < 4) discovered else JsonObject(discovered + mapOf("status" to JsonPrimitive("completed"), "resultRevision" to JsonPrimitive(scope.revision)))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(envelope(view))
            }
        }
        val sent = mutableListOf<String>()
        val records = mutableMapOf<String, String>()
        val service = McpDiscoveryService({ identity }, config, apiFactory = { client },
            journal = McpDiscoveryJournal({ records[it] }, { name, text -> records[name] = text }), exchangeFactory = { binding, authorize ->
            McpPhoneExchange(binding, config, authorize) { _, source, request, dispatch ->
                val message = request.body?.let { Json.parseToJsonElement(it).jsonObject }
                dispatch {
                    assertEquals("synthetic-plugin-key", source.read()!!.value)
                    sent.add(message?.getValue("method")?.jsonPrimitive?.content ?: "DELETE")
                }
                val status = if (request.method == "DELETE") 204 else if (message?.containsKey("id") == false) 202 else 200
                McpHttpReply(status, if (status == 200) buildJsonObject { put("jsonrpc", "2.0"); put("id", message!!.getValue("id")); put("result", buildJsonObject {}) }.toString() else "", null, false)
            }
        })
        assertEquals("completed", service.discover(scope.serviceId, scope.revision).status)
        assertEquals(methods, sent); assertEquals(4, completed.get()); assertEquals(21, server.requestCount)
        val id = service.operations().single().scope.requestId
        config.revoke(slot, config.status(slot).context)
        val restarted = McpDiscoveryService({ identity }, config, apiFactory = { client },
            journal = McpDiscoveryJournal({ records[it] }, { name, text -> records[name] = text }),
            exchangeFactory = { _, _ -> error("恢复不能重新访问插件") })
        assertEquals("completed", restarted.recover(id).status)
        assertEquals(22, server.requestCount); assertEquals(methods, sent)
    }
}
