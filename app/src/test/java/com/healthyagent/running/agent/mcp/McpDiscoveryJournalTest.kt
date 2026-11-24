package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpDiscoveryJournalTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val records = mutableMapOf<String, String>()
    private fun store() = McpDiscoveryJournal({ records[it] }, { name, text -> records[name] = text })
    private fun scope() = UUID.randomUUID().toString().let { id -> McpPhoneScope(identity, id, id,
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), McpEndpoint.parse("https://tools.example.com/mcp")) }
    private val running = McpDiscoveryView(1000, 11000, "running", null, null)
    private fun completed(scope: McpPhoneScope) = running.copy(status = "completed", resultRevision = scope.revision)
    private fun fails(code: String, run: () -> Unit) {
        try { run(); fail("应拒绝无效操作或存储") } catch (error: CloudTaskFailure) { assertEquals(code, error.code) }
    }

    @Test fun 先保存唯一绑定再确认状态且重建实例保留原操作() {
        val scope = scope(); val store = store()
        store.begin(scope)
        assertNull(store().get(identity, scope.requestId).view)
        store.observe(scope, running); store.observe(scope, completed(scope))
        val read = store().get(identity, scope.requestId)
        assertEquals(scope.requestId, read.scope.requestId); assertEquals(scope.requestId, read.scope.taskId)
        assertEquals(scope.endpoint.toString(), read.scope.endpoint.toString()); assertEquals(completed(scope), read.view)
        assertEquals(1, store().list(identity).size)
    }

    @Test fun 当前身份设备凭据代次和origin各自隔离且文件名不含身份原文() {
        val scope = scope(); val store = store(); store.begin(scope)
        for (other in listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"),
            identity.copy(deviceId = "other"), identity.copy(credentialRef = "other"))) {
            assertTrue(store.list(other).isEmpty()); fails("not_found") { store.get(other, scope.requestId) }
        }
        assertTrue(records.keys.single().matches(Regex("[0-9a-f]{64}\\.json")))
        assertFalse(records.values.single().contains("credential")); assertFalse(records.values.single().contains("owner"))
    }

    @Test fun 重复标识旧绑定和时窗改变不能覆盖记录() {
        val scope = scope(); val store = store(); store.begin(scope); store.observe(scope, running)
        fails("conflict") { store.begin(scope) }
        fails("conflict") { store.observe(scope.copy(generation = scope.revision), running) }
        fails("conflict") { store.observe(scope.copy(endpoint = McpEndpoint.parse("https://changed.example.com/mcp")), running) }
        fails("invalid_response") { store.observe(scope, running.copy(startedAtMillis = 2000, deadlineAtMillis = 12000)) }
        assertEquals(running, store.get(identity, scope.requestId).view)
    }

    @Test fun 终态不可降级或更换结果幂等确认不再次写文件() {
        var writes = 0
        val store = McpDiscoveryJournal({ records[it] }, { name, text -> writes++; records[name] = text })
        val scope = scope(); store.begin(scope); val completed = completed(scope); store.observe(scope, completed)
        store.observe(scope, completed); assertEquals(2, writes)
        fails("invalid_response") { store.observe(scope, running) }
        fails("invalid_response") { store.observe(scope, completed.copy(resultRevision = scope.generation)) }
        fails("invalid_response") { store.observe(scope, running.copy(status = "unconfirmed", reason = "interrupted")) }
        assertEquals(completed, store.get(identity, scope.requestId).view)
    }

    @Test fun 未确认云终态保留原失败分类而非伪造成功() {
        val scope = scope(); val store = store(); store.begin(scope)
        val unknown = running.copy(status = "unconfirmed", reason = "timed_out")
        store.observe(scope, unknown); assertEquals(unknown, store().get(identity, scope.requestId).view)
        fails("invalid_response") { store.observe(scope, completed(scope)) }
    }

    @Test fun 六十四个未知操作满时拒绝新建但不驱逐未知标识() {
        val store = store(); val scopes = (1..64).map { scope() }
        scopes.forEach(store::begin)
        fails("busy") { store.begin(scope()) }
        assertEquals(scopes.map { it.requestId }.toSet(), store().list(identity).map { it.scope.requestId }.toSet())
    }

    @Test fun 满容量仅清理最早已确认终态内部元数据() {
        val store = store(); val scopes = (1..64).map { scope() }; scopes.forEach(store::begin)
        store.observe(scopes[5], completed(scopes[5])); store.observe(scopes[8], completed(scopes[8]))
        val added = scope(); store.begin(added)
        assertEquals(64, store.list(identity).size); fails("not_found") { store.get(identity, scopes[5].requestId) }
        assertNotNull(store.get(identity, scopes[0].requestId)); assertNotNull(store.get(identity, scopes[8].requestId))
        assertEquals(added.requestId, store.list(identity).first().scope.requestId)
    }

    @Test fun 坏JSON未知字段重复标识和跨身份复制均失败关闭且不写覆盖() {
        val store = store(); val scope = scope(); store.begin(scope); val name = records.keys.single()
        val good = Json.parseToJsonElement(records.getValue(name)).jsonObject
        val operations = good.getValue("operations").jsonArray
        for (broken in listOf("not-json", JsonObject(good + ("secret" to JsonPrimitive("private"))).toString(),
            JsonObject(good + ("operations" to JsonArray(listOf(operations.single(), operations.single())))).toString(),
            JsonObject(good + ("identityContext" to JsonPrimitive("other"))).toString(), " ".repeat(524289))) {
            records[name] = broken
            fails("storage_unavailable") { store.list(identity) }; fails("storage_unavailable") { store.begin(scope()) }
            assertEquals(broken, records[name])
        }
    }

    @Test fun 丢失写入或坏读回不能当作已保存且不伪造状态() {
        val scope = scope()
        fails("storage_unavailable") { McpDiscoveryJournal({ null }, { _, _ -> }).begin(scope) }
        val store = store(); store.begin(scope)
        fails("storage_unavailable") { McpDiscoveryJournal({ records[it] }, { _, _ -> error("synthetic-private-disk-error") }).observe(scope, running) }
        assertNull(store.get(identity, scope.requestId).view)
    }

    @Test fun 损坏绑定或含秘密的视图不能被解释成恢复许可() {
        val store = store(); val scope = scope(); store.begin(scope); store.observe(scope, running)
        val name = records.keys.single(); val good = Json.parseToJsonElement(records.getValue(name)).jsonObject
        val original = good.getValue("operations").jsonArray.single().jsonObject
        for (patch in listOf(mapOf("binding" to JsonObject(original.getValue("binding").jsonObject + ("requestId" to JsonPrimitive("not-id")))),
            mapOf("discovery" to JsonObject(original.getValue("discovery").jsonObject + ("text" to JsonPrimitive("private")))))) {
            records[name] = JsonObject(good + ("operations" to JsonArray(listOf(JsonObject(original + patch))))).toString()
            fails("storage_unavailable") { store.get(identity, scope.requestId) }
        }
    }
}
