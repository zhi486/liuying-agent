package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpPhoneExchangeTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/mcp-phone-v1/transport.json")).readText()).jsonObject
    private val wire = fixture.getValue("request").jsonObject
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
    private val files = mutableMapOf<String, ByteArray>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val configuration = McpPrivateConfiguration({ files[it]?.clone() }, { name, value -> files[name] = value.clone() }, { key })
    private val slot = McpPrivateSlot(identity, wire.getValue("serviceId").jsonPrimitive.content)
    private val saved = configuration.save(slot, configuration.status(slot).context, endpoint, McpBearer("synthetic-plugin-key"))
    private val current = JsonObject(wire + ("generation" to JsonPrimitive(saved.generation!!)))
    private fun request(value: JsonObject = current) = McpPhoneRequest.parse(value.toString())
    private fun scope() = McpPhoneScope(identity, request().requestId, request().taskId, slot.serviceId,
        request().revision, saved.generation!!, endpoint)
    private fun response() = McpHttpReply(200, fixture.getValue("reply").jsonObject.getValue("text").jsonPrimitive.content,
        "synthetic-session", false)
    private fun changed(key: String, value: JsonElement) = JsonObject(current + (key to value))
    private fun fresh() = request(changed("exchangeId", JsonPrimitive(UUID.randomUUID().toString())))

    @Test fun 宿主派发边界的拒绝传至传输并只返回固定错误而不产生插件请求() = runBlocking {
        var authorized = 0; var sent = 0
        val exchange = McpPhoneExchange(scope(), configuration, { authorized++ }) { _, _, _, dispatch ->
            dispatch { sent++ }; response()
        }
        val reply = exchange.execute(request(), dispatch = { throw McpTransportFailure("stale_configuration") })
        assertEquals(1, authorized); assertEquals(0, sent)
        assertEquals("stale_configuration", reply.getValue("error").jsonPrimitive.content)
        assertFalse("text" in reply)
    }

    @Test fun 共享跨语言样例保留绑定且只回传固定字段() {
        val parsed = request(wire)
        assertEquals(fixture.getValue("reply"), parsed.reply(response()))
        assertEquals("tools/list", Json.parseToJsonElement(parsed.body!!).jsonObject.getValue("method").jsonPrimitive.content)
        assertEquals("McpPhoneRequest(正文与会话已脱敏)", parsed.toString())
        assertFalse(parsed.toString().contains("synthetic-session"))
    }

    @Test fun 多余权限头异源错误类型及深嵌套在读取秘密之前拒绝() {
        val bad = listOf(changed("headers", buildJsonObject { put("Authorization", "PRIVATE") }),
            changed("identity", JsonPrimitive("other-device")), changed("method", JsonPrimitive("GET")),
            changed("body", JsonNull), changed("generation", JsonPrimitive("bad-id")),
            changed("sessionId", JsonPrimitive("bad\r\nheader")), changed("protocolVersion", JsonPrimitive("future")),
            changed("endpoint", JsonPrimitive("http://127.0.0.1/mcp")),
            changed("body", JsonPrimitive("{" + "x".repeat(16384))), changed("method", JsonPrimitive(1)),
            changed("body", JsonPrimitive("""{"jsonrpc":"2.0","id":1,"method":"sampling/createMessage"}""")))
        for (value in bad) assertThrows(McpTransportFailure::class.java) { request(value) }
        assertThrows(McpTransportFailure::class.java) { McpPhoneRequest.parse("[".repeat(2000) + "]".repeat(2000)) }
    }

    @Test fun 授权前后均检查且秘密只在原生发送端读取() = runBlocking {
        val events = mutableListOf<String>()
        val exchange = McpPhoneExchange(scope(), configuration, { events.add("authorize") }) { target, source, packet, dispatch ->
            dispatch {
                events.add("send"); assertEquals(endpoint.url, target.url); assertEquals("POST", packet.method)
                assertEquals("synthetic-plugin-key", source.read()!!.value)
            }
            response()
        }
        val result = exchange.execute(request())
        assertEquals(listOf("authorize", "send", "authorize"), events)
        assertEquals(200, result.getValue("status").jsonPrimitive.int)
        assertFalse(result.toString().contains("synthetic-plugin-key"))
    }

    @Test fun 换任务服务目录代次及端点不可借用当前授权() = runBlocking {
        var authorized = 0; var sent = 0
        val exchange = McpPhoneExchange(scope(), configuration, { authorized++ }) { _, _, _, dispatch -> dispatch { sent++ }; response() }
        for (field in listOf("requestId", "taskId", "serviceId", "revision", "generation")) {
            try { exchange.execute(request(changed(field, JsonPrimitive(UUID.randomUUID().toString())))); fail("应拒绝越权请求") }
            catch (_: McpTransportFailure) { }
        }
        try { exchange.execute(request(changed("endpoint", JsonPrimitive("https://other.example.com/mcp")))); fail("应拒绝异源请求") }
        catch (_: McpTransportFailure) { }
        assertEquals(0, authorized); assertEquals(0, sent)
        assertEquals(200, exchange.execute(request()).getValue("status").jsonPrimitive.int)
    }

    @Test fun 重复交付不补发而新交换标识可继续同一会话() = runBlocking {
        var sent = 0
        val exchange = McpPhoneExchange(scope(), configuration, {}) { _, _, _, dispatch -> dispatch { sent++ }; response() }
        exchange.execute(request())
        try { exchange.execute(request()); fail("相同交付不能再次发送") } catch (_: McpTransportFailure) { }
        exchange.execute(fresh()); assertEquals(2, sent)
    }

    @Test fun 撤权和响应期间撤权均不返回插件正文() = runBlocking {
        for (during in listOf(false, true)) {
            var enabled = during; var sent = 0
            val exchange = McpPhoneExchange(scope(), configuration, { if (!enabled) error("PRIVATE-AUTH-ERROR") }) { _, _, _, dispatch ->
                dispatch { sent++; enabled = false }; response()
            }
            val reply = exchange.execute(request())
            assertEquals(if (during) 1 else 0, sent)
            assertEquals("transport_failed", reply.getValue("error").jsonPrimitive.content)
            assertFalse(reply.toString().contains("PRIVATE")); assertFalse("text" in reply)
        }
    }

    @Test fun 私密配置撤销或身份变化不能读取旧凭据且失败不重发() = runBlocking {
        for (otherIdentity in listOf(false, true)) {
            var sent = 0
            val trusted = if (otherIdentity) scope().copy(identity = identity.copy(deviceId = "other-device")) else scope()
            val exchange = McpPhoneExchange(trusted, configuration, {}) { _, source, _, dispatch ->
                dispatch { source.read(); sent++ }; response()
            }
            if (!otherIdentity) configuration.revoke(slot, saved.context)
            val reply = exchange.execute(request())
            assertTrue(reply.getValue("error").jsonPrimitive.content in setOf("stale_configuration", "credential_unavailable"))
            assertEquals(0, sent)
            try { exchange.execute(request()); fail("凭据失效不自动重发") } catch (_: McpTransportFailure) { }
        }
    }

    @Test fun 并发交付拒绝而取消不生成假成功或恢复重发() = runBlocking {
        val started = CompletableDeferred<Unit>(); var sent = 0
        val exchange = McpPhoneExchange(scope(), configuration, {}) { _, _, _, dispatch ->
            dispatch { sent++; started.complete(Unit) }; awaitCancellation()
        }
        val work = launch { exchange.execute(request()) }
        started.await()
        try { exchange.execute(fresh()); fail("同通道只允许一个在途交换") } catch (_: McpTransportFailure) { }
        work.cancelAndJoin()
        try { exchange.execute(request()); fail("取消后不重发原交换") } catch (_: McpTransportFailure) { }
        assertEquals(1, sent)
    }

    @Test fun 未知异常坏响应及秘密异常正文只回固定分类() = runBlocking {
        for (kind in listOf("exception", "redirect", "sse", "oversize")) {
            val exchange = McpPhoneExchange(scope(), configuration, {}) { _, _, _, dispatch ->
                dispatch {}
                when (kind) {
                    "exception" -> error("PRIVATE-SECRET")
                    "redirect" -> McpHttpReply(302, "PRIVATE-BODY", null, false)
                    "sse" -> McpHttpReply(200, "PRIVATE-BODY", null, true)
                    else -> McpHttpReply(200, "中".repeat(44000), null, false)
                }
            }
            val reply = exchange.execute(request())
            assertEquals("transport_failed", reply.getValue("error").jsonPrimitive.content)
            assertFalse(reply.toString().contains("PRIVATE")); assertFalse("text" in reply)
        }
    }
}
