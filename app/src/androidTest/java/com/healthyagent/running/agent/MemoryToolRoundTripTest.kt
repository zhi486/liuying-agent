package com.healthyagent.running.agent

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 合成服务端驱动正式原生调度与 Room；不接网络、麦克风或用户数据。 */
@RunWith(AndroidJUnit4::class)
class MemoryToolRoundTripTest {
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var service: CloudAgentService
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private var cloudId = "22222222-2222-4222-8222-222222222222"
    private var stored: String? = null
    private val consent = MemoryToolConsent({ stored }, { stored = it })
    private var intent: CloudTaskIntent? = null
    private var generation: String? = null
    private var entered = CompletableDeferred<Unit>()
    private var received = CompletableDeferred<JsonObject>()
    private var pollGate: CompletableDeferred<Unit>? = null
    private var badGeneration = false
    private var searchTool = false
    private var submits = 0
    private var ordinarySubmits = 0
    private val api = object : CloudTaskApi {
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse {
            ordinarySubmits++
            this@MemoryToolRoundTripTest.intent = intent
            return finished()
        }
        override suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean): CloudTaskResponse {
            submits++
            this@MemoryToolRoundTripTest.intent = intent
            this@MemoryToolRoundTripTest.generation = generation
            assertNull(context)
            assertFalse(factsAllowed)
            assertNotNull(CloudMemoryTool.submission(intent, context, generation)["memoryTools"])
            return active()
        }
        override suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool? {
            entered.complete(Unit)
            pollGate?.await()
            return CloudMemoryTool(intent!!.clientRequestId, taskId,
                if (badGeneration) "33333333-3333-4333-8333-333333333333" else generation!!,
                if (searchTool) "search_1" else "save_1", if (searchTool) "memory.search" else "memory.upsert", if (searchTool) buildJsonObject {
                    put("query", "专业")
                } else buildJsonObject {
                    put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
                })
        }
        override suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject) {
            assertEquals(intent!!.clientRequestId, tool.requestId)
            received.complete(observation)
        }
        override suspend fun find(identity: CloudTaskIdentity, requestId: String) = error("本测试不查其他任务")
        override suspend fun get(identity: CloudTaskIdentity, taskId: String) = finished()
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse {
            assertEquals("acknowledge", action)
            return finished("acknowledged")
        }
    }
    private fun active() = CloudTaskResponse(cloudId, "running", null, 1000, 31000, 1000, "none", null)
    private fun finished(state: String = "available") = CloudTaskResponse(cloudId, "completed", null, 1000, 31000, 2000, state,
        if (state == "available") buildJsonObject {
            put("status", "completed"); put("answer", "合成回答"); put("actions", JsonArray(emptyList()))
        } else null)
    private fun data(outcome: CommandOutcome) = (outcome as CommandOutcome.AcceptedWithData).data
    private suspend fun enable(enabled: Boolean) {
        assertTrue(service.handle("cloud.memoryConsent", buildJsonObject {
            put("identityToken", consent.context(identity)); put("enabled", enabled)
        }) is CommandOutcome.AcceptedWithData)
    }
    private suspend fun send(prompt: String = "我喜欢夜跑") {
        val conversation = data(service.handle("cloud.newConversation", buildJsonObject {})).getValue("conversationId")
        val preview = data(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", prompt); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversation)
        }))
        assertTrue(service.handle("cloud.confirm", buildJsonObject {
            put("token", preview.getValue("token")); put("approved", true)
        }) is CommandOutcome.AcceptedWithData)
    }
    private suspend fun waitFinished(): JsonObject = withTimeout(10000) {
        while (true) {
            val row = data(service.handle("cloud.list", buildJsonObject {})).getValue("items").jsonArray.singleOrNull()?.jsonObject
            if (row != null && row.getValue("busy") == JsonPrimitive(false)) return@withTimeout row
            delay(10)
        }
        error("不可达")
    }

    private fun memoryDiagnostics(): List<String> {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d -v raw --pid=${Process.myPid()} -s LiuyingCloud:I '*:S'")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().useLines { lines ->
            lines.filter { it.startsWith("stage=memory_tool_local ") }.toList()
        }
    }

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        service = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, memoryConsent = consent)
    }
    @After fun tearDown() = runBlocking { scope.cancel(); scope.coroutineContext[Job]?.join(); database.close() }

    @Test fun 正式原生调度携带绑定代际并将Room结果回传一次() = runBlocking<Unit> {
        enable(true); send()
        val observation = withTimeout(5000) { received.await() }
        assertEquals(JsonPrimitive("saved"), observation["status"])
        assertEquals("我喜欢夜跑", database.memoryDao().page("local", 0, 20).items.single().content)
        assertEquals(JsonPrimitive("completed"), waitFinished()["status"])
        assertEquals(1, submits)
        assertEquals(0, ordinarySubmits)
    }

    @Test fun 等待工具期间关闭授权不会持锁阻塞且随后拒绝写入() = runBlocking<Unit> {
        pollGate = CompletableDeferred()
        enable(true); send()
        withTimeout(5000) { entered.await() }
        withTimeout(3000) { enable(false) }
        pollGate!!.complete(Unit)
        val observation = withTimeout(5000) { received.await() }
        assertEquals(buildJsonObject { put("status", "rejected"); put("reason", "consent_required") }, observation)
        assertEquals(0, database.memoryDao().count("local"))
        waitFinished()
    }

    @Test fun 服务端错误代际不执行不回传也不重发提交() = runBlocking<Unit> {
        val before = memoryDiagnostics()
        badGeneration = true
        enable(true); send()
        val row = waitFinished()
        assertNotEquals(JsonNull, row["error"])
        assertFalse(received.isCompleted)
        assertEquals(0, database.memoryDao().count("local"))
        assertEquals(1, submits)
        assertEquals(0, ordinarySubmits)
        assertEquals(before, memoryDiagnostics())
    }

    @Test fun 等待旧工具时撤权再开启不复活旧调用且新任务可以保存() = runBlocking<Unit> {
        val before = memoryDiagnostics()
        pollGate = CompletableDeferred()
        enable(true); send()
        withTimeout(5000) { entered.await() }
        val oldRequest = intent!!.clientRequestId
        val oldGeneration = generation!!
        withTimeout(3000) { enable(false); enable(true) }
        val renewed = consent.current(identity)!!
        assertNotEquals(oldGeneration, renewed.generation)
        pollGate!!.complete(Unit)
        assertEquals(buildJsonObject { put("status", "rejected"); put("reason", "consent_required") },
            withTimeout(5000) { received.await() })
        assertFalse(waitFinished().containsKey("memorySaved"))
        assertEquals(0, database.memoryDao().count("local"))
        assertEquals(oldGeneration, database.memoryToolBindingDao().find(oldRequest)!!.grantGeneration)
        assertEquals(1, submits)

        // 旧调用真正结束后重建宿主，避免新授权被错误实现成永久禁用记忆。
        entered = CompletableDeferred(); received = CompletableDeferred(); pollGate = null
        cloudId = UUID.randomUUID().toString()
        service = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, memoryConsent = consent)
        send()
        assertEquals(JsonPrimitive("saved"), withTimeout(5000) { received.await() }["status"])
        val current = waitFinished()
        val newRequest = intent!!.clientRequestId
        assertNotEquals(oldRequest, newRequest)
        assertEquals(renewed.generation, database.memoryToolBindingDao().find(newRequest)!!.grantGeneration)
        assertEquals(JsonPrimitive(true), current["memorySaved"])
        assertEquals("我喜欢夜跑", database.memoryDao().page("local", 0, 20).items.single().content)
        assertEquals(newRequest, database.memoryDao().documentRows("local").single().sourceTurnId)
        assertEquals(2, submits)
        assertEquals(0, ordinarySubmits)
        assertEquals(listOf("stage=memory_tool_local code=rejected_consent_required", "stage=memory_tool_local code=saved"),
            memoryDiagnostics().drop(before.size))
        enable(false)
        assertNull(consent.current(identity))
    }

    @Test fun 旧搜索撤权后不返回正文而新代次可读取且事实关闭不写入() = runBlocking<Unit> {
        val before = memoryDiagnostics()
        val memory = database.memoryDao().create("local", UUID.randomUUID().toString(), "我的专业是流萤记忆验收工程", 500)
        searchTool = true
        pollGate = CompletableDeferred()
        enable(true); send("我的专业是什么？请只检索已保存的专业，不保存或修改任何记忆。")
        withTimeout(5000) { entered.await() }
        val oldRequest = intent!!.clientRequestId
        val oldGeneration = generation!!
        withTimeout(3000) { enable(false); enable(true) }
        val renewed = consent.current(identity)!!
        assertNotEquals(oldGeneration, renewed.generation)
        assertFalse(renewed.factsAllowed)
        pollGate!!.complete(Unit)
        assertEquals(buildJsonObject { put("status", "rejected"); put("reason", "consent_required") },
            withTimeout(5000) { received.await() })
        waitFinished()
        assertTrue(database.memoryContextDependenciesDao().find(oldRequest)?.state()?.refs.orEmpty().isEmpty())
        assertFalse(database.memoryDao().hasCurrentToolSave("local", oldRequest))

        entered = CompletableDeferred(); received = CompletableDeferred(); pollGate = null
        cloudId = UUID.randomUUID().toString()
        service = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, memoryConsent = consent)
        send("我的主要编程语言是Kotlin。我的专业是什么？")
        val observation = withTimeout(5000) { received.await() }
        assertEquals(buildJsonObject {
            put("status", "found")
            put("items", JsonArray(listOf(buildJsonObject {
                put("id", memory.id); put("version", 1); put("content", memory.content)
            })))
        }, observation)
        assertFalse(waitFinished().containsKey("memorySaved"))
        val newRequest = intent!!.clientRequestId
        assertNotEquals(oldRequest, newRequest)
        assertEquals(renewed.generation, database.memoryToolBindingDao().find(newRequest)!!.grantGeneration)
        assertEquals(listOf(memory), database.memoryDao().page("local", 0, 20).items)
        assertFalse(database.memoryDao().hasCurrentToolSave("local", newRequest))
        assertEquals(setOf(memory.id to 1), database.memoryContextDependenciesDao().find(newRequest)!!.state().refs.map { it.id to it.version }.toSet())
        assertEquals(2, submits)
        assertEquals(0, ordinarySubmits)
        assertEquals(listOf("stage=memory_tool_local code=rejected_consent_required", "stage=memory_tool_local code=found"),
            memoryDiagnostics().drop(before.size))
        enable(false)
        assertNull(consent.current(identity))
    }

    @Test fun 默认关闭仍能普通聊天但不宣告保存或开放记忆工具() = runBlocking<Unit> {
        send()
        val row = waitFinished()
        assertEquals(JsonPrimitive("completed"), row["status"])
        assertFalse(row.containsKey("memorySaved"))
        assertEquals(1, ordinarySubmits)
        assertEquals(0, submits)
        assertNull(database.memoryToolBindingDao().find(intent!!.clientRequestId))
        assertFalse(entered.isCompleted)
        assertEquals(0, database.memoryDao().count("local"))
    }

    @Test fun 正式回传完成后追问继承记忆而修改恢复及删除使旧上下文失效() = runBlocking<Unit> {
        enable(true); send()
        withTimeout(5000) { received.await() }
        val row = waitFinished()
        assertEquals(JsonPrimitive(true), row["memorySaved"])
        val memory = database.memoryDao().page("local", 0, 20).items.single()
        suspend fun preview() = data(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", "继续聊聊"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", intent!!.conversationId)
        }))
        fun messages(value: JsonObject) = value.getValue("conversation").jsonObject.getValue("messages").jsonArray
        val pending = preview()
        assertEquals(2, messages(pending).size)
        assertEquals(setOf(com.healthyagent.running.agent.memory.MemoryRef(memory.id, 1)),
            database.memoryContextDependenciesDao().find(intent!!.clientRequestId)!!.state().refs)
        database.memoryDao().update("local", memory.id, 1, "我喜欢晨跑", 2000)
        assertTrue(service.handle("cloud.confirm", buildJsonObject {
            put("token", pending.getValue("token")); put("approved", true)
        }) is CommandOutcome.Rejected)
        assertTrue(messages(preview()).isEmpty())
        database.memoryDao().restore("local", memory.id, 2, 1, 3000)
        assertTrue(messages(preview()).isEmpty())
        database.memoryDao().delete("local", memory.id, 3, 4000)
        assertTrue(messages(preview()).isEmpty())
        assertEquals(1, submits)
        assertEquals(0, ordinarySubmits)
        assertFalse(waitFinished().containsKey("memorySaved"))
        assertEquals(JsonPrimitive("合成回答"), waitFinished()["answer"])
    }
}
