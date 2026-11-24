package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import javax.crypto.KeyGenerator
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpDiscoveryServiceTest {
    private var identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val serviceId = UUID.randomUUID().toString()
    private val revision = UUID.randomUUID().toString()
    private val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
    private val files = mutableMapOf<String, ByteArray>()
    private val records = mutableMapOf<String, String>()
    private val journal = McpDiscoveryJournal({ records[it] }, { name, text -> records[name] = text })
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val configuration = McpPrivateConfiguration({ files[it]?.clone() }, { name, data -> files[name] = data.clone() }, { key })
    private val slot = McpPrivateSlot(identity, serviceId)
    private val saved = configuration.save(slot, configuration.status(slot).context, endpoint, McpBearer("synthetic-plugin-key"))
    private val methods = listOf("initialize", "notifications/initialized", "tools/list", "DELETE")

    private inner class Api : McpDiscoveryApi {
        lateinit var scope: McpPhoneScope
        var starts = 0; var takes = 0; var replies = 0; var checks = 0
        var failAt: String? = null; var methodOverride: String? = null; var duplicate = false
        var onStart: () -> Unit = {}
        var onCheck: () -> Unit = {}
        var recovered: McpDiscoveryView? = null
        val wireReplies = mutableListOf<JsonObject>()
        private val exchangeId = UUID.randomUUID().toString()
        private fun view() = recovered ?: McpDiscoveryView(1000, 11000, if (replies == 4) "completed" else "running", if (replies == 4) revision else null, null)
        override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView {
            starts++; this.scope = scope; onStart()
            if (failAt == "start") throw CloudTaskFailure("network_timeout")
            return view()
        }
        override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView {
            checks++; assertEquals(this.scope.requestId, scope.requestId); onCheck()
            if (failAt == "check") throw CloudTaskFailure("network_timeout")
            if (failAt == "missing") throw CloudTaskFailure("not_found")
            return view()
        }
        override suspend fun takeDiscovery(scope: McpPhoneScope): McpPhoneRequest {
            takes++
            if (failAt == "take") throw CloudTaskFailure("network_unavailable")
            val method = methodOverride ?: methods[replies]
            return McpPhoneRequest.parse(buildJsonObject {
                put("version", "native_mcp_v1"); put("requestId", scope.requestId); put("taskId", scope.taskId)
                put("serviceId", scope.serviceId); put("revision", scope.revision); put("generation", scope.generation)
                put("endpoint", scope.endpoint.toString()); put("exchangeId", if (duplicate) exchangeId else UUID.randomUUID().toString())
                put("method", if (method == "DELETE") "DELETE" else "POST")
                put("body", if (method == "DELETE") JsonNull else JsonPrimitive(buildJsonObject {
                    put("jsonrpc", "2.0"); if (method != "notifications/initialized") put("id", replies)
                    put("method", method); put("params", buildJsonObject {})
                }.toString()))
                put("sessionId", JsonNull); put("protocolVersion", "2025-11-25")
            }.toString())
        }
        override suspend fun replyDiscovery(scope: McpPhoneScope, packet: McpPhoneRequest, reply: JsonObject) {
            replies++; wireReplies.add(reply)
            if (failAt == "reply") throw CloudTaskFailure("network_timeout")
        }
    }

    private fun service(api: Api, send: suspend (McpPhoneRequest) -> McpHttpReply) = McpDiscoveryService({ identity }, configuration,
        apiFactory = { assertEquals(identity, it); api }, journal = journal, exchangeFactory = { scope, authorize ->
            McpPhoneExchange(scope, configuration, authorize) { target, credentials, packet, dispatch ->
                dispatch { assertEquals(endpoint.url, target.url); assertEquals("synthetic-plugin-key", credentials.read()!!.value) }
                send(packet)
            }
        })
    private fun reply(packet: McpPhoneRequest): McpHttpReply {
        if (packet.method == "DELETE") return McpHttpReply(204, "", null, false)
        val message = Json.parseToJsonElement(packet.body!!).jsonObject
        return if ("id" !in message) McpHttpReply(202, "", null, false) else McpHttpReply(200,
            buildJsonObject { put("jsonrpc", "2.0"); put("id", message.getValue("id")); put("result", buildJsonObject {}) }.toString(), null, false)
    }

    @Test fun 可信本机配置建立发现绑定并顺序转发四次交换() = runBlocking {
        val api = Api(); val sent = mutableListOf<String>()
        val result = service(api) { sent.add(it.body?.let { body -> Json.parseToJsonElement(body).jsonObject.getValue("method").jsonPrimitive.content } ?: "DELETE"); reply(it) }
            .discover(serviceId, revision)
        assertEquals("completed", result.status); assertEquals(methods, sent)
        assertEquals(api.scope.requestId, api.scope.taskId); assertEquals(saved.generation, api.scope.generation)
        assertEquals(1, api.starts); assertEquals(4, api.takes); assertEquals(4, api.replies); assertEquals(12, api.checks)
        assertFalse(api.wireReplies.toString().contains("synthetic-plugin-key"))
    }

    @Test fun 本机撤销或身份换代后不领取插件请求() = runBlocking {
        for (changeIdentity in listOf(false, true)) {
            val api = Api(); var sent = 0
            api.onStart = { if (changeIdentity) identity = identity.copy(credentialRef = "other") else configuration.revoke(slot, saved.context) }
            try { service(api) { sent++; reply(it) }.discover(serviceId, revision); fail("应拒绝过期本机绑定") }
            catch (_: McpTransportFailure) { }
            assertEquals(0, sent); assertEquals(0, api.takes)
            if (!changeIdentity) configuration.save(slot, configuration.status(slot).context, endpoint, McpBearer("synthetic-plugin-key"))
        }
    }

    @Test fun 插件响应期间身份变化不回传结果() = runBlocking {
        val api = Api(); var sent = 0
        try { service(api) { sent++; identity = identity.copy(deviceId = "changed"); reply(it) }.discover(serviceId, revision); fail("应丢弃迟到结果") }
        catch (_: McpTransportFailure) { }
        assertEquals(1, sent); assertEquals(0, api.replies)
    }

    @Test fun 发现通路拒绝夹带工具调用且只发固定失败回执() = runBlocking {
        val api = Api().apply { methodOverride = "tools/call"; failAt = "reply" }; var sent = 0
        try { service(api) { sent++; reply(it) }.discover(serviceId, revision); fail("应结束失败操作") } catch (_: CloudTaskFailure) { }
        assertEquals(0, sent); assertEquals(1, api.replies)
        assertEquals("invalid_response", api.wireReplies.single().getValue("error").jsonPrimitive.content)
        assertFalse("text" in api.wireReplies.single())
    }

    @Test fun 开始领取回执断流都不重试也不生成新操作键() = runBlocking {
        for (stage in listOf("start", "take", "reply")) {
            val api = Api().apply { failAt = stage }; var sent = 0
            try { service(api) { sent++; reply(it) }.discover(serviceId, revision); fail("应保留未知而非重发") } catch (_: CloudTaskFailure) { }
            assertEquals(1, api.starts); assertEquals(if (stage == "start") 0 else 1, api.takes)
            assertEquals(if (stage == "reply") 1 else 0, sent); assertTrue(api.replies <= 1)
        }
    }

    @Test fun 重复交换不能通过新同步循环重发() = runBlocking {
        val api = Api().apply { duplicate = true }; var sent = 0
        try { service(api) { sent++; reply(it) }.discover(serviceId, revision); fail("应拒绝重复交换") } catch (_: McpTransportFailure) { }
        assertEquals(1, sent); assertEquals(1, api.replies); assertEquals(2, api.takes)
    }

    @Test fun 并发发现拒绝且取消终止原交换不回传() = runBlocking {
        val api = Api(); val entered = CompletableDeferred<Unit>()
        val host = service(api) { entered.complete(Unit); awaitCancellation() }
        val work = launch { host.discover(serviceId, revision) }
        entered.await()
        try { host.discover(serviceId, revision); fail("应拒绝并发") } catch (failure: CloudTaskFailure) { assertEquals("busy", failure.code) }
        work.cancelAndJoin(); assertEquals(1, api.starts); assertEquals(0, api.replies)
    }

    @Test fun 未保存私密配置时不创建云操作() = runBlocking {
        configuration.revoke(slot, saved.context)
        val api = Api()
        try { service(api) { error("不得发送") }.discover(serviceId, revision); fail("应先保存本机配置") } catch (_: McpTransportFailure) { }
        assertEquals(0, api.starts)
    }

    @Test fun 开始回执丢失后重建服务仅查询原标识且不需要插件凭据() = runBlocking {
        val api = Api().apply { failAt = "start" }
        api.onStart = { assertNull(journal.get(identity, api.scope.requestId).view) }
        try { service(api) { error("不得访问插件") }.discover(serviceId, revision); fail("应保留未知") } catch (_: CloudTaskFailure) { }
        val id = journal.list(identity).single().scope.requestId
        configuration.revoke(slot, saved.context)
        api.failAt = null; api.recovered = McpDiscoveryView(1000, 11000, "unconfirmed", null, "interrupted")
        val restarted = McpDiscoveryService({ identity }, configuration, apiFactory = { api },
            journal = McpDiscoveryJournal({ records[it] }, { name, text -> records[name] = text }),
            exchangeFactory = { _, _ -> error("恢复不得创建交换器") })
        assertEquals("interrupted", restarted.recover(id).reason)
        assertEquals(id, restarted.operations().single().scope.requestId)
        assertEquals(1, api.starts); assertEquals(1, api.checks); assertEquals(0, api.takes); assertEquals(0, api.replies)
    }

    @Test fun 恢复断流或云端未找到均保留原操作不推断未执行() = runBlocking {
        val api = Api().apply { failAt = "take" }
        val host = service(api) { error("不得发送") }
        try { host.discover(serviceId, revision) } catch (_: CloudTaskFailure) { }
        val before = host.operations().single(); assertEquals("running", before.view!!.status)
        for (stage in listOf("check", "missing")) {
            api.failAt = stage
            try { host.recover(before.scope.requestId); fail("应报告未知") } catch (failure: CloudTaskFailure) {
                assertEquals(if (stage == "check") "network_timeout" else "not_found", failure.code)
            }
            assertEquals(before.view, host.operations().single().view)
        }
        assertEquals(1, api.starts); assertEquals(1, api.takes); assertEquals(0, api.replies); assertEquals(2, api.checks)
    }

    @Test fun 恢复不接受其他身份且迟到身份变化不更新记录() = runBlocking {
        val api = Api().apply { failAt = "start" }; val host = service(api) { error("不得发送") }
        try { host.discover(serviceId, revision) } catch (_: CloudTaskFailure) { }
        val original = identity; val id = host.operations().single().scope.requestId
        identity = identity.copy(credentialRef = "other")
        assertTrue(host.operations().isEmpty())
        try { host.recover(id); fail("应隔离身份") } catch (failure: CloudTaskFailure) { assertEquals("not_found", failure.code) }
        assertEquals(0, api.checks)
        identity = original; api.failAt = null; api.onCheck = { identity = identity.copy(deviceId = "changed") }
        try { host.recover(id); fail("应丢弃迟到状态") } catch (_: McpTransportFailure) { }
        assertNull(journal.get(original, id).view); assertEquals(1, api.checks); assertEquals(1, api.starts)
    }

    @Test fun 操作落盘失败阻止创建API及所有网络请求() = runBlocking {
        var factories = 0
        val host = McpDiscoveryService({ identity }, configuration, apiFactory = { factories++; error("不得创建 API") },
            journal = McpDiscoveryJournal({ null }, { _, _ -> error("synthetic-private-storage-error") }))
        try { host.discover(serviceId, revision); fail("应先落盘") } catch (failure: CloudTaskFailure) { assertEquals("storage_unavailable", failure.code) }
        assertEquals(0, factories); assertTrue(records.isEmpty())
    }

    @Test fun 并发恢复拒绝且取消后标识仍能用于只读查询() = runBlocking {
        val api = Api().apply { failAt = "start" }; val host = service(api) { error("不得发送") }
        try { host.discover(serviceId, revision) } catch (_: CloudTaskFailure) { }
        val original = host.operations().single(); val entered = CompletableDeferred<Unit>()
        val blocking = object : McpDiscoveryApi by api {
            override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView { entered.complete(Unit); awaitCancellation() }
        }
        val restarted = McpDiscoveryService({ identity }, configuration, apiFactory = { blocking }, journal = journal)
        val recovery = launch { restarted.recover(original.scope.requestId) }; entered.await()
        try { restarted.recover(original.scope.requestId); fail("应拒绝并发") }
        catch (failure: CloudTaskFailure) { assertEquals("busy", failure.code) }
        recovery.cancelAndJoin(); assertNull(host.operations().single().view)
        assertEquals(1, api.starts); assertEquals(0, api.takes); assertEquals(0, api.replies)
    }
}
