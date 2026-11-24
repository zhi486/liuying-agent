package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.*
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** 只用合成任务及暂停的调度器，不接真实网络、模型或用户记录。 */
@RunWith(AndroidJUnit4::class)
class MemoryToolHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var service: CloudAgentService
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private var currentIdentity: CloudTaskIdentity? = identity
    private var stored: String? = null
    private val consent = MemoryToolConsent({ stored }, { stored = it })
    private var clock = 1000L
    private val api = object : CloudTaskApi {
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse = error("本测试禁止网络派发")
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse = error("本测试禁止网络查询")
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse = error("本测试禁止网络查询")
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse = error("本测试禁止网络控制")
    }
    private fun createService() = CloudAgentService(database, { currentIdentity }, { api }, scope,
        now = { clock }, memoryConsent = consent)
    private fun data(outcome: CommandOutcome) = (outcome as CommandOutcome.AcceptedWithData).data
    private suspend fun set(enabled: Boolean) = service.handle("cloud.memoryConsent", buildJsonObject {
        put("identityToken", consent.context(identity)); put("enabled", enabled)
    })
    private suspend fun prepare(): String {
        val conversation = data(service.handle("cloud.newConversation", buildJsonObject {})).getValue("conversationId").jsonPrimitive.content
        return data(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", "我喜欢夜跑"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversation)
        })).getValue("token").jsonPrimitive.content
    }
    private suspend fun confirm(token: String) = service.handle("cloud.confirm", buildJsonObject { put("token", token); put("approved", true) })
    private suspend fun task(bind: Boolean = true, attempted: Boolean = true, prompt: String = "我喜欢夜跑"): CloudTaskIntent {
        val conversation = database.conversationDao().create(identity, clock)
        val intent = CloudTaskIntent.confirm(identity, prompt, true, clock).copy(
            conversationId = conversation.id,
            conversationJson = """{"version":"conversation_v1","id":"${conversation.id}","approved":true,"messages":[]}""",
        )
        database.withTransaction {
            database.cloudIntentDao().save(intent)
            if (bind) database.memoryToolBindingDao().insert(MemoryToolBinding.capture(intent, consent.current(identity)!!))
            if (attempted) database.cloudIntentDao().claimSubmission(identity, intent.clientRequestId, clock)
        }
        return intent
    }
    private suspend fun execute(intent: CloudTaskIntent, id: String = "call_1") = service.executeMemoryTool(
        identity, intent.clientRequestId, id, "memory.upsert", buildJsonObject {
            put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
        })

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        service = createService()
    }
    @After fun tearDown() { scope.cancel(); database.close() }

    @Test fun 事实范围由宿主快照提供且旧任务升级撤权与篡改均拒绝() = runBlocking {
        set(true)
        val evidence = "我的主要编程语言是Kotlin"
        val old = task(prompt = evidence)
        val args = buildJsonObject { put("category", "fact"); put("evidence", evidence); put("target", JsonNull) }
        suspend fun save(intent: CloudTaskIntent) = service.executeMemoryTool(identity, intent.clientRequestId, "fact", "memory.upsert", args)
        assertEquals(MemoryToolResult.Rejected("scope_denied"), save(old))
        val preview = prepare()
        val authorized = data(service.handle("cloud.memoryConsent", buildJsonObject {
            put("identityToken", consent.context(identity)); put("enabled", true); put("factsAllowed", true)
        }))
        assertEquals(JsonPrimitive(true), authorized["enabled"])
        assertEquals(JsonPrimitive(true), authorized["factsAllowed"])
        assertTrue(confirm(preview) is CommandOutcome.Rejected)
        assertEquals(MemoryToolResult.Rejected("consent_required"), save(old))
        val allowed = task(prompt = evidence)
        assertTrue(database.memoryToolBindingDao().find(allowed.clientRequestId)!!.factsAllowed)
        val saved = save(allowed) as MemoryToolResult.Saved
        assertEquals(evidence, saved.memory.content)
        assertTrue((save(allowed) as MemoryToolResult.Saved).replayed)
        assertEquals("fact", database.memoryDao().documentRows("local").single().category)
        set(true)
        assertEquals(MemoryToolResult.Rejected("consent_required"), save(allowed))
        database.openHelper.writableDatabase.execSQL("UPDATE agent_memory_tool_bindings SET factsAllowed = 1 WHERE requestId = ?", arrayOf(old.clientRequestId))
        assertEquals(MemoryToolResult.Rejected("scope_denied"), save(old))
        assertEquals(1, database.memoryDao().count("local"))
    }

    @Test fun 原生授权默认关闭可恢复且变更使预览失效() = runBlocking {
        assertFalse(data(service.handle("cloud.memoryAuthorization", buildJsonObject {})).getValue("enabled").jsonPrimitive.boolean)
        val old = prepare()
        assertTrue(set(true) is CommandOutcome.AcceptedWithData)
        assertTrue(confirm(old) is CommandOutcome.Rejected)
        assertTrue(data(createService().handle("cloud.memoryAuthorization", buildJsonObject {})).getValue("enabled").jsonPrimitive.boolean)
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
    }

    @Test fun 确认同事务保存授权绑定但不扩展现有网络正文() = runBlocking {
        set(true)
        val token = prepare()
        assertTrue(confirm(token) is CommandOutcome.AcceptedWithData)
        val intent = database.cloudIntentDao().find(identity, token)!!
        assertTrue(database.memoryToolBindingDao().find(token)!!.matches(intent))
        assertFalse(intent.submission().containsKey("memoryAuthorization"))
        assertFalse(intent.submissionAttempted)
    }

    @Test fun 未绑定旧任务不能借新授权且撤销再开启不恢复旧任务() = runBlocking {
        val old = task(bind = false)
        set(true)
        assertEquals(MemoryToolResult.Rejected("consent_required"), execute(old))
        val bound = task()
        set(false); set(true)
        assertEquals(MemoryToolResult.Rejected("consent_required"), execute(bound))
        assertEquals(0, database.memoryDao().count("local"))
    }

    @Test fun 已授权任务使用原生来源保存并支持幂等回执() = runBlocking {
        set(true)
        val intent = task()
        val saved = execute(intent) as MemoryToolResult.Saved
        assertEquals("我喜欢夜跑", saved.memory.content)
        assertTrue((execute(intent) as MemoryToolResult.Saved).replayed)
        assertEquals(1, database.memoryDao().count("local"))
        assertEquals(MemoryToolResult.Rejected("evidence_required"), service.executeMemoryTool(identity,
            intent.clientRequestId, "call_2", "memory.upsert", buildJsonObject {
                put("category", "preference"); put("evidence", "我喜欢晨跑"); put("target", JsonNull)
            }))
    }

    @Test fun 未提交控制终态与过期任务均不能读写记忆() = runBlocking {
        set(true)
        assertEquals(MemoryToolResult.Rejected("task_inactive"), execute(task(attempted = false)))
        for (control in listOf("cancel", "withdraw")) {
            val intent = task()
            database.cloudIntentDao().requestControl(identity, intent.clientRequestId, control, clock)
            assertEquals(MemoryToolResult.Rejected("task_inactive"), execute(intent))
        }
        val completed = task()
        database.cloudIntentDao().accept(identity, completed.clientRequestId, CloudTaskResponse(
            UUID.randomUUID().toString(), "completed", null, 1000, 31000, 2000, "available",
            buildJsonObject { put("status", "completed"); put("answer", "合成答案"); put("actions", JsonArray(emptyList())) },
        ))
        assertEquals(MemoryToolResult.Rejected("task_inactive"), execute(completed))
        val expired = task()
        clock = 121001
        assertEquals(MemoryToolResult.Rejected("task_inactive"), execute(expired))
        assertEquals(0, database.memoryDao().count("local"))
    }

    @Test fun 身份切换与来源篡改均不能执行() = runBlocking {
        set(true)
        val intent = task()
        currentIdentity = identity.copy(credentialRef = "new")
        assertEquals(MemoryToolResult.Rejected("scope_denied"), execute(intent))
        currentIdentity = identity
        database.openHelper.writableDatabase.execSQL("UPDATE agent_cloud_intents SET prompt = '我喜欢晨跑' WHERE clientRequestId = ?", arrayOf(intent.clientRequestId))
        assertEquals(MemoryToolResult.Rejected("scope_denied"), execute(intent))
        assertEquals(0, database.memoryDao().count("local"))
    }

    private suspend fun listed(intent: CloudTaskIntent): JsonObject =
        data(service.handle("cloud.list", buildJsonObject {})).getValue("items").jsonArray
            .single { it.jsonObject.getValue("requestId").jsonPrimitive.content == intent.clientRequestId }.jsonObject

    @Test fun 保存提示只认原生回执且不依赖最终回答成功() = runBlocking {
        set(true)
        val intent = task()
        database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        assertFalse(listed(intent).containsKey("memorySaved"))
        execute(intent)
        assertTrue(listed(intent).getValue("memorySaved").jsonPrimitive.boolean)
        database.cloudIntentDao().accept(identity, intent.clientRequestId, CloudTaskResponse(
            UUID.randomUUID().toString(), "failed", "invalid_model_output", 1000, 31000, 2000, "expired", null,
        ))
        assertTrue(listed(intent).getValue("memorySaved").jsonPrimitive.boolean)
        service = createService()
        assertTrue(listed(intent).getValue("memorySaved").jsonPrimitive.boolean)
        database.openHelper.writableDatabase.execSQL("UPDATE agent_cloud_intents SET prompt = '我喜欢晨跑' WHERE clientRequestId = ?", arrayOf(intent.clientRequestId))
        assertFalse(listed(intent).containsKey("memorySaved"))
    }

    @Test fun 修改后恢复旧正文也不复活旧保存提示() = runBlocking {
        set(true)
        val intent = task()
        val saved = (execute(intent) as MemoryToolResult.Saved).memory
        assertTrue(listed(intent).containsKey("memorySaved"))
        database.memoryDao().update("local", saved.id, saved.version, "我喜欢晨跑", 2000)
        assertFalse(listed(intent).containsKey("memorySaved"))
        database.memoryDao().restore("local", saved.id, saved.version + 1, saved.version, 3000)
        assertFalse(listed(intent).containsKey("memorySaved"))
    }

    @Test fun 删除时不显示保存提示() = runBlocking {
        set(true)
        val intent = task()
        val saved = (execute(intent) as MemoryToolResult.Saved).memory
        assertTrue(listed(intent).containsKey("memorySaved"))
        database.memoryDao().delete("local", saved.id, saved.version, 2000)
        assertFalse(listed(intent).containsKey("memorySaved"))
    }

    @Test fun 待撤回及已确认撤回时不显示保存提示且不删除记忆() = runBlocking {
        set(true)
        val intent = task()
        val saved = (execute(intent) as MemoryToolResult.Saved).memory
        assertTrue(listed(intent).containsKey("memorySaved"))
        database.cloudIntentDao().requestControl(identity, intent.clientRequestId, "withdraw", clock)
        assertFalse(listed(intent).containsKey("memorySaved"))
        database.cloudIntentDao().accept(identity, intent.clientRequestId, CloudTaskResponse(
            UUID.randomUUID().toString(), "cancelled", "consent_revoked", 1000, 31000, 2000, "withdrawn", null,
        ))
        assertFalse(listed(intent).containsKey("memorySaved"))
        assertEquals(saved.content, database.memoryDao().find("local", saved.id)!!.content)
    }
}
