package com.healthyagent.running.agent

import com.healthyagent.running.agent.local.AgentActionEntity
import com.healthyagent.running.agent.local.AgentMessageEntity
import com.healthyagent.running.agent.local.AgentTaskDao
import com.healthyagent.running.agent.local.AgentTaskEntity
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.bridge.BridgeDecodeResult
import com.healthyagent.running.bridge.BridgeSession
import com.healthyagent.running.coordinator.AppSnapshot
import com.healthyagent.running.coordinator.CommandOutcome
import com.healthyagent.running.core.model.RunConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskServiceTest {
    private val requestId = "11111111-1111-4111-8111-111111111111"
    private val taskId = "22222222-2222-4222-8222-222222222222"
    private fun body(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test fun `保存与服务重建恢复真实用户消息但没有助手回复`() = runTest {
        val dao = MemoryAgentDao()
        val service = AgentTaskService(dao, "owner", { 1000 }, { taskId })
        val saved = service.handle("agent.submit", body("""{"clientRequestId":"$requestId","prompt":"  今晚轻松跑  "}""")) as CommandOutcome.AcceptedWithData
        assertEquals("blocked", saved.data["task"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("cloud_not_connected", saved.data["task"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals("今晚轻松跑", saved.data["messages"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("user", saved.data["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(!saved.data.toString().contains("ownerId"))
        assertTrue(!saved.data.toString().contains("clientRequestId"))
        val restored = AgentTaskService(dao, "owner").handle("agent.detail", body("""{"taskId":"$taskId"}"""))
        assertEquals(saved, restored)
    }

    @Test fun `持久幂等键同文返回原任务同键异文拒绝且不复活取消任务`() = runTest {
        val dao = MemoryAgentDao()
        val service = AgentTaskService(dao, "owner", { 1000 }, { taskId })
        val command = body("""{"clientRequestId":"$requestId","prompt":"跑后复盘"}""")
        val first = service.handle("agent.submit", command)
        assertEquals(first, AgentTaskService(dao, "owner").handle("agent.submit", command))
        assertTrue(service.handle("agent.submit", body("""{"clientRequestId":"$requestId","prompt":"改过的任务"}""")) is CommandOutcome.Rejected)
        service.handle("agent.cancel", body("""{"taskId":"$taskId"}"""))
        val replay = service.handle("agent.submit", command) as CommandOutcome.AcceptedWithData
        assertEquals("cancelled", replay.data["task"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, dao.tasks.size)
        assertEquals(1, dao.messages.size)
    }

    @Test fun `重建Bridge后不同信封ID使用同幂等键仍返回同一本机任务`() = runTest {
        val dao = MemoryAgentDao()
        val sent = mutableListOf<String>()
        var handled = 0
        val hello = checkNotNull(javaClass.getResource("/v1/fixtures/valid/hello.json")).readText()
        val original = body(checkNotNull(javaClass.getResource("/v1/fixtures/valid/command.agent-submit.json")).readText())
        val ids = listOf("44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555")
        for (envelopeId in ids) {
            val service = AgentTaskService(dao, "owner", { 1000 }, { taskId })
            val bridge = BridgeSession(
                buildId = "test-build",
                snapshotProvider = { AppSnapshot.initial(RunConfig(), 1000) },
                commandHandler = { name, payload -> handled++; service.handle(name, payload) },
                send = sent::add,
            )
            bridge.onMessage(hello)
            bridge.onMessage(JsonObject(original + ("id" to JsonPrimitive(envelopeId))).toString())
            val result = BridgeCodec.decode(sent.last()) as BridgeDecodeResult.Accepted
            val data = result.envelope.payload.getValue("data").jsonObject
            assertEquals(envelopeId, data.getValue("commandId").jsonPrimitive.content)
            assertEquals(taskId, data.getValue("task").jsonObject.getValue("id").jsonPrimitive.content)
        }
        assertEquals(2, handled)
        assertEquals(1, dao.tasks.size)
        assertEquals(1, dao.messages.size)
        assertEquals(1, dao.actions.size)
    }

    @Test fun `重复取消不增加行动或改变时间且时钟回拨不倒退`() = runTest {
        val dao = MemoryAgentDao()
        dao.saveLocal("owner", requestId, "计划", taskId, 1000)
        val first = dao.cancel("owner", taskId, 500)!!
        val second = dao.cancel("owner", taskId, 9000)!!
        assertEquals(first, second)
        assertEquals(1000L, first.task.updatedAtMillis)
        assertEquals(listOf("saved_locally", "cancelled"), first.actions.map { it.kind })
        assertEquals("cancelled", first.task.status)
        assertEquals(null, first.task.reason)
    }

    @Test fun `查询取消和幂等按可信所有者隔离且分页不读消息`() = runTest {
        val dao = MemoryAgentDao()
        dao.saveLocal("one", requestId, "用户一", taskId, 1000)
        val anotherId = "33333333-3333-4333-8333-333333333333"
        dao.saveLocal("two", requestId, "用户二", anotherId, 2000)
        assertEquals(null, dao.detail("two", taskId))
        assertEquals(null, dao.cancel("two", taskId, 3000))
        val page = dao.page("two", 0, 20)
        assertEquals(1, page.totalItems)
        assertEquals(listOf(anotherId), page.items.map { it.id })
        assertEquals(emptyList<AgentTaskSummary>(), dao.page("two", 20, 20).items)
        assertEquals("blocked", dao.detail("one", taskId)!!.task.status)
    }

    @Test fun `不存在或越权任务返回相同错误且坏输入不写库`() = runTest {
        val dao = MemoryAgentDao()
        val service = AgentTaskService(dao, "owner")
        assertTrue(service.handle("agent.submit", body("""{"clientRequestId":"$requestId","prompt":" "}""")) is CommandOutcome.Rejected)
        assertTrue(service.handle("agent.list", body("""{"offset":0,"limit":51}""")) is CommandOutcome.Rejected)
        assertTrue(service.handle("agent.detail", body("""{"taskId":"$taskId"}""")) is CommandOutcome.Rejected)
        assertEquals(0, dao.tasks.size)
    }
}

// 只替代 Room SQL 边界；保存/查询/取消事务业务仍执行真实 DAO 默认方法。
private class MemoryAgentDao : AgentTaskDao {
    val tasks = mutableListOf<AgentTaskEntity>()
    val messages = mutableListOf<AgentMessageEntity>()
    val actions = mutableListOf<AgentActionEntity>()
    override suspend fun insertTask(task: AgentTaskEntity) { tasks += task }
    override suspend fun insertMessage(message: AgentMessageEntity) { messages += message }
    override suspend fun insertAction(action: AgentActionEntity) { actions += action }
    override suspend fun findRequest(ownerId: String, clientRequestId: String) = tasks.find { it.ownerId == ownerId && it.clientRequestId == clientRequestId }
    override suspend fun findTask(ownerId: String, taskId: String) = tasks.find { it.ownerId == ownerId && it.id == taskId }
    override suspend fun messages(taskId: String) = messages.filter { it.taskId == taskId }.sortedBy { it.sequence }
    override suspend fun actions(taskId: String) = actions.filter { it.taskId == taskId }.sortedBy { it.sequence }
    override suspend fun count(ownerId: String) = tasks.count { it.ownerId == ownerId }
    override suspend fun tasks(ownerId: String, offset: Int, limit: Int) = tasks.filter { it.ownerId == ownerId }.sortedWith(compareByDescending<AgentTaskEntity> { it.createdAtMillis }.thenByDescending { it.id }).drop(offset).take(limit)
    override suspend fun markCancelled(ownerId: String, taskId: String, now: Long): Int {
        val index = tasks.indexOfFirst { it.id == taskId && it.ownerId == ownerId && it.status == "blocked" }
        if (index < 0) return 0
        tasks[index] = tasks[index].copy(status = "cancelled", reason = null, updatedAtMillis = now)
        return 1
    }
}
