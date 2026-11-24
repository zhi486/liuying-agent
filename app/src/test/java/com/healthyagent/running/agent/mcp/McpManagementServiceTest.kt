package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.coordinator.CommandOutcome
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class McpManagementServiceTest {
    private var identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val id = UUID.randomUUID().toString()
    private val revision = UUID.randomUUID().toString()
    private val endpoint = "https://tools.example.com/mcp"
    private val files = mutableMapOf<String, ByteArray>()
    private val records = mutableMapOf<String, String>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val privateConfig = McpPrivateConfiguration({ files[it] }, { name, bytes -> files[name] = bytes }, { key })
    private val journal = McpDiscoveryJournal({ records[it] }, { name, text -> records[name] = text })
    private val noDiscovery = object : McpDiscoveryApi {
        override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView = error("不应发起发现")
        override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView = error("不应访问发现")
        override suspend fun takeDiscovery(scope: McpPhoneScope): McpPhoneRequest? = error("不应领取")
        override suspend fun replyDiscovery(scope: McpPhoneScope, packet: McpPhoneRequest, reply: JsonObject): Unit = error("不应回传")
    }
    private var getHook: suspend () -> Unit = {}
    private var catalogBroken = false
    private var changeHook: suspend (String, JsonObject) -> JsonObject = { _, _ -> row }
    private val calls = mutableListOf<String>()
    private var row = buildJsonObject {
        put("id", id); put("revision", revision); put("endpoint", endpoint); put("enabled", false)
        put("catalog", JsonNull); put("grants", JsonArray(emptyList()))
    }
    private val api = object : McpManagementApi {
        override suspend fun listServices(identity: CloudTaskIdentity, after: String?): JsonObject {
            calls += "list"
            return buildJsonObject {
                put("services", JsonArray(listOf(JsonObject(row.filterKeys { it !in setOf("catalog", "grants") }))))
                put("nextCursor", JsonNull); put("discoveryAvailable", false); put("phoneDiscoveryAvailable", true)
            }
        }
        override suspend fun getService(identity: CloudTaskIdentity, id: String, summaryOnly: Boolean): JsonObject {
            calls += "get"; getHook()
            if (catalogBroken && !summaryOnly) throw CloudTaskFailure("invalid_response")
            return if (summaryOnly) JsonObject(row.filterKeys { it !in setOf("catalog", "grants") }) else row
        }
        override suspend fun changeService(identity: CloudTaskIdentity, id: String?, action: String, body: JsonObject): JsonObject {
            calls += action
            assertFalse(body.toString().contains("identityToken")); assertFalse(body.toString().contains("secret"))
            return changeHook(action, body)
        }
    }
    private val discovery = McpDiscoveryService({ identity }, privateConfig, { noDiscovery }, journal)
    private val host = McpManagementService({ identity }, privateConfig, { api }, discovery)
    private fun body(extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("identityToken", CloudChatConsent.context(identity)); extra.forEach { (key, value) -> put(key, value) }
    }
    private fun reviewed() = body(buildJsonObject { put("id", id); put("revision", revision) })
    private fun accepted(value: CommandOutcome) = (value as CommandOutcome.AcceptedWithData).data
    private fun slot() = McpPrivateSlot(identity, id)
    private fun destructive(endpoint: String? = null) = JsonObject(reviewed() + buildJsonObject {
        put("context", privateConfig.status(slot()).context); put("confirmed", true)
        endpoint?.let { put("endpoint", it) }
    })

    @Test fun 发现超过八秒仍返回原操作的完成回执() = runBlocking {
        privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), null)
        var starts = 0
        val slow = object : McpDiscoveryApi by noDiscovery {
            override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView {
                starts++
                delay(8_100)
                return McpDiscoveryView(1_000, 26_000, "completed", revision, null)
            }
        }
        val discoveryService = McpDiscoveryService({ identity }, privateConfig, { slow }, journal)
        val management = McpManagementService({ identity }, privateConfig, { api }, discoveryService)
        val result = management.handle("mcp.discover", JsonObject(reviewed() + ("confirmed" to JsonPrimitive(true))))
        assertEquals(1, starts)
        assertTrue(result is CommandOutcome.AcceptedWithData)
        assertEquals("completed", discoveryService.operations().single().view?.status)
    }

    @Test fun 坏目录不阻塞明确移除但旧版本与旧本机代次仍在删除前拒绝() = runBlocking {
        catalogBroken = true
        val saved = privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), null)
        assertTrue(host.handle("mcp.detail", body(buildJsonObject { put("id", id) })) is CommandOutcome.Rejected)
        val input = destructive()
        row = JsonObject(row + ("revision" to JsonPrimitive(UUID.randomUUID().toString())))
        assertTrue(host.handle("mcp.remove", input) is CommandOutcome.Rejected)
        assertEquals(saved, privateConfig.status(slot()))
        row = JsonObject(row + ("revision" to JsonPrimitive(revision)))
        changeHook = { action, payload ->
            assertEquals("remove", action); assertEquals(JsonPrimitive(revision), payload["revision"])
            assertFalse(privateConfig.status(slot()).configured)
            buildJsonObject { put("removed", true) }
        }
        assertEquals(JsonPrimitive(true), accepted(host.handle("mcp.remove", input))["removed"])
        assertEquals(listOf("get", "get", "get", "remove"), calls)
    }

    @Test fun 地址修改先撤销本机密钥关闭旧窗口且不上传本机代次() = runBlocking {
        val saved = privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), McpBearer("synthetic-secret"))
        accepted(host.handle("mcp.configure", reviewed()))
        val opened = host.configuring.value!!
        val address = "https://changed.example.com/mcp"
        changeHook = { action, input ->
            assertEquals("endpoint", action)
            assertFalse(privateConfig.status(slot()).configured); assertNull(host.configuring.value)
            assertEquals(setOf("endpoint", "revision"), input.keys)
            row = JsonObject(row + mapOf("endpoint" to JsonPrimitive(address), "revision" to JsonPrimitive(UUID.randomUUID().toString())))
            row
        }
        val data = accepted(host.handle("mcp.endpoint", destructive(address)))
        assertEquals(JsonPrimitive(address), data.getValue("service").jsonObject["endpoint"])
        assertEquals(JsonPrimitive(false), data.getValue("private").jsonObject["configured"])
        assertNotEquals(saved.context, privateConfig.status(slot()).context)
        try { privateConfig.source(slot(), McpEndpoint.parse(endpoint), saved.generation!!).read(); fail("旧代次不能发请求") } catch (_: McpTransportFailure) { }
        try { host.saveConfiguration(opened, "synthetic-secret", false); fail("旧窗口不能恢复") } catch (_: IllegalStateException) { }
    }
    @Test fun 移除服务先撤销本机配置但保留发现记录与其他服务配置() = runBlocking {
        val saved = privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), null)
        val requestId = UUID.randomUUID().toString()
        journal.begin(McpPhoneScope(identity, requestId, requestId, id, revision, saved.generation!!, McpEndpoint.parse(endpoint)))
        val previousRecords = records.toMap()
        val other = McpPrivateSlot(identity, UUID.randomUUID().toString())
        val otherSaved = privateConfig.save(other, privateConfig.status(other).context, McpEndpoint.parse(endpoint), null)
        changeHook = { action, input ->
            assertEquals("remove", action); assertEquals(setOf("revision"), input.keys)
            assertFalse(privateConfig.status(slot()).configured)
            buildJsonObject { put("removed", true) }
        }
        val data = accepted(host.handle("mcp.remove", destructive()))
        assertEquals(JsonPrimitive(true), data["removed"])
        assertEquals(otherSaved, privateConfig.status(other))
        assertEquals(previousRecords, records); assertEquals(requestId, discovery.operations().single().scope.requestId)
        assertEquals(listOf("get", "remove"), calls)
    }
    @Test fun 地址修改或移除未确认非法地址旧身份均在网络前拒绝() = runBlocking {
        val base = destructive()
        for ((name, input) in listOf(
            "mcp.remove" to JsonObject(base + ("confirmed" to JsonPrimitive(false))),
            "mcp.remove" to JsonObject(base - "context"),
            "mcp.remove" to JsonObject(base + ("identityToken" to JsonPrimitive("a".repeat(64)))),
            "mcp.endpoint" to destructive("https://127.0.0.1/mcp"),
            "mcp.endpoint" to JsonObject(destructive("https://changed.example.com/mcp") + ("bearer" to JsonPrimitive("secret"))),
        )) assertTrue(host.handle(name, input) is CommandOutcome.Rejected)
        assertTrue(calls.isEmpty())
    }
    @Test fun 旧云版本旧私密代次或相同地址不能撤销新配置或提交变更() = runBlocking {
        val input = destructive()
        val saved = privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), null)
        assertTrue(host.handle("mcp.remove", input) is CommandOutcome.Rejected)
        assertEquals(saved, privateConfig.status(slot()))
        assertTrue(host.handle("mcp.endpoint", destructive(endpoint)) is CommandOutcome.Rejected)
        val current = destructive()
        row = JsonObject(row + ("revision" to JsonPrimitive(UUID.randomUUID().toString())))
        assertTrue(host.handle("mcp.remove", current) is CommandOutcome.Rejected)
        assertEquals(saved, privateConfig.status(slot())); assertEquals(listOf("get", "get", "get"), calls)
    }
    @Test fun 云变更失败或取消不恢复旧密钥也不补发或谎称移除成功() = runBlocking {
        for (cancelled in listOf(false, true)) {
            privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), null)
            changeHook = { _, _ -> if (cancelled) throw CancellationException() else throw CloudTaskFailure("network_timeout") }
            try {
                assertTrue(host.handle("mcp.remove", destructive()) is CommandOutcome.Rejected)
                assertFalse(cancelled)
            } catch (_: CancellationException) { assertTrue(cancelled) }
            assertFalse(privateConfig.status(slot()).configured)
        }
        assertEquals(listOf("get", "remove", "get", "remove"), calls)
        assertEquals(JsonPrimitive(true), accepted(host.handle("mcp.status", JsonObject(emptyMap())))["configured"])
    }

    @Test fun 本机状态不创建网络也不输出秘密() = runBlocking {
        val data = accepted(host.handle("mcp.status", JsonObject(emptyMap())))
        assertEquals(JsonPrimitive(true), data["configured"])
        assertEquals(JsonArray(emptyList()), data["operations"]); assertTrue(calls.isEmpty())
        assertFalse(data.toString().contains("credential"))
    }
    @Test fun 旧身份令牌或未知密钥字段在网络前拒绝() = runBlocking {
        val valid = reviewed()
        for (input in listOf(JsonObject(valid + ("identityToken" to JsonPrimitive("a".repeat(64)))),
            JsonObject(valid + ("bearer" to JsonPrimitive("secret"))))) {
            assertTrue(host.handle("mcp.configure", input) is CommandOutcome.Rejected)
        }
        assertTrue(calls.isEmpty()); assertNull(host.configuring.value)
    }
    @Test fun 列表合并本机元数据而不读取导出密钥() = runBlocking {
        privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), McpBearer("synthetic-secret"))
        val data = accepted(host.handle("mcp.list", body(buildJsonObject { put("after", JsonNull) })))
        assertEquals(JsonPrimitive(true), data["services"]!!.jsonArray.single().jsonObject["private"]!!.jsonObject["hasCredential"])
        assertEquals(listOf("list"), calls)
        assertFalse(data.toString().contains("synthetic-secret")); assertFalse(data.toString().contains("Bearer"))
    }
    @Test fun 原生窗口保存密钥且取消不改私密配置() = runBlocking {
        accepted(host.handle("mcp.configure", reviewed()))
        val request = host.configuring.value!!
        host.saveConfiguration(request, "synthetic-secret", false)
        val saved = privateConfig.status(slot())
        assertTrue(saved.configured); assertTrue(saved.hasCredential)
        host.closeConfiguration(request.token)
        assertNull(host.configuring.value)
        try { host.saveConfiguration(request, "changed", false); fail("已取消窗口不能保存") } catch (_: IllegalStateException) { }
        assertEquals(saved, privateConfig.status(slot()))
    }
    @Test fun 匿名连接必须明确选择且空输入不能清除原密钥() = runBlocking {
        accepted(host.handle("mcp.configure", reviewed()))
        val request = host.configuring.value!!
        try { host.saveConfiguration(request, "", false); fail("空输入不能默认匿名") } catch (_: IllegalStateException) { }
        assertFalse(privateConfig.status(slot()).configured)
        host.saveConfiguration(request, "", true)
        assertTrue(privateConfig.status(slot()).configured); assertFalse(privateConfig.status(slot()).hasCredential)
    }
    @Test fun 云端版本或本机代次变化后旧窗口不能保存() = runBlocking {
        accepted(host.handle("mcp.configure", reviewed()))
        val request = host.configuring.value!!
        row = JsonObject(row + ("revision" to JsonPrimitive(UUID.randomUUID().toString())))
        try { host.saveConfiguration(request, "secret", false); fail("旧云版本不能保存") } catch (_: IllegalStateException) { }
        assertTrue(files.isEmpty())
        row = JsonObject(row + ("revision" to JsonPrimitive(revision)))
        val current = privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), null)
        try { host.saveConfiguration(request, "secret", false); fail("旧本机代次不能保存") } catch (_: McpTransportFailure) { }
        assertEquals(current, privateConfig.status(slot()))
    }
    @Test fun 请求中途切换账号不打开窗口或写入权限() = runBlocking {
        getHook = { identity = identity.copy(ownerId = "other") }
        assertTrue(host.handle("mcp.configure", reviewed()) is CommandOutcome.Rejected)
        assertNull(host.configuring.value); assertTrue(files.isEmpty()); assertEquals(listOf("get"), calls)
    }
    @Test fun 审核过期或未确认授权不提交云端() = runBlocking {
        val base = reviewed()
        assertTrue(host.handle("mcp.approve", JsonObject(base + mapOf("selection" to JsonArray(emptyList()), "confirmed" to JsonPrimitive(true)))) is CommandOutcome.Rejected)
        row = JsonObject(row + ("revision" to JsonPrimitive(UUID.randomUUID().toString())))
        assertTrue(host.handle("mcp.disable", base) is CommandOutcome.Rejected)
        assertEquals(listOf("get"), calls)
    }
    @Test fun 撤销本机配置不发插件请求且不假装撤销云权限() = runBlocking {
        val saved = privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), McpBearer("synthetic-secret"))
        accepted(host.handle("mcp.revokePrivate", body(buildJsonObject { put("id", id); put("context", saved.context) })))
        assertFalse(privateConfig.status(slot()).configured); assertTrue(calls.isEmpty())
    }
    @Test fun 重复点击被同步锁阻止且取消后可重新只读() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        getHook = { started.complete(Unit); release.await() }
        val job = async { host.handle("mcp.detail", body(buildJsonObject { put("id", id) })) }
        started.await()
        assertTrue(host.handle("mcp.configure", reviewed()) is CommandOutcome.Rejected)
        job.cancelAndJoin(); getHook = {}
        accepted(host.handle("mcp.status", JsonObject(emptyMap())))
        assertEquals(listOf("get"), calls)
    }
    @Test fun 发现提交失败保留原标识且恢复不重新发起目录或读取密钥() = runBlocking {
        privateConfig.save(slot(), privateConfig.status(slot()).context, McpEndpoint.parse(endpoint), McpBearer("synthetic-secret"))
        var starts = 0; var queries = 0
        val failing = object : McpDiscoveryApi by noDiscovery {
            override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView {
                starts++; throw CloudTaskFailure("network_timeout")
            }
            override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView {
                queries++; return McpDiscoveryView(100, 10100, "unconfirmed", null, "interrupted")
            }
        }
        val recovering = McpDiscoveryService({ identity }, privateConfig, { failing }, journal)
        val management = McpManagementService({ identity }, privateConfig, { api }, recovering)
        assertTrue(management.handle("mcp.discover", JsonObject(reviewed() + ("confirmed" to JsonPrimitive(true)))) is CommandOutcome.Rejected)
        val operations = accepted(management.handle("mcp.status", JsonObject(emptyMap()))).getValue("operations").jsonArray
        val operation = operations.single().jsonObject
        assertEquals(JsonPrimitive("unknown"), operation["status"])
        privateConfig.revoke(slot(), privateConfig.status(slot()).context)
        val result = accepted(management.handle("mcp.recover", body(buildJsonObject { put("requestId", operation.getValue("requestId")) })))
        assertEquals(JsonPrimitive("unconfirmed"), result.getValue("operations").jsonArray.single().jsonObject["status"])
        assertEquals(1, starts); assertEquals(1, queries); assertEquals(listOf("get"), calls)
    }
}
