package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 实际 Room 与虚拟调度时钟；所有云端接口均为合成替身，不访问网络或用户数据。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CloudTaskStreamWakeDatabaseTest {
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var service: CloudAgentService
    private val dispatcher = StandardTestDispatcher()
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val taskId = UUID.randomUUID().toString()
    private val events = Channel<CloudTaskEvent>(Channel.UNLIMITED)
    private val submits = AtomicInteger()
    private val gets = AtomicInteger()
    private val memoryQueries = AtomicInteger()
    private val acceptedEvents = AtomicInteger()
    private val rejectedEvents = AtomicInteger()
    private val subscriptions = AtomicInteger()
    private val cancellations = AtomicInteger()
    private val replies = Channel<JsonObject>(Channel.UNLIMITED)
    private var storedConsent: String? = null
    private val consent = MemoryToolConsent({ storedConsent }, { storedConsent = it })
    private var requestId = ""
    private var generation = ""
    @Volatile private var toolReady = false
    private fun response() = CloudTaskResponse(taskId, "running", null, 1000, 31000, 1000, "none", null)
    private val api = object : CloudTaskApi {
        override val supportsStreaming = true
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse = error("必须沿用已授权的记忆任务提交")
        override suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean): CloudTaskResponse {
            submits.incrementAndGet()
            assertEquals(requestId, intent.clientRequestId)
            assertNull(context)
            assertFalse(factsAllowed)
            this@CloudTaskStreamWakeDatabaseTest.generation = generation
            return response()
        }
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse {
            assertEquals(this@CloudTaskStreamWakeDatabaseTest.identity, identity)
            assertEquals(this@CloudTaskStreamWakeDatabaseTest.taskId, taskId)
            gets.incrementAndGet()
            return response()
        }
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse = error("不能查询其他任务")
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse = error("不能修改终态回执")
        override suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool? {
            assertEquals(this@CloudTaskStreamWakeDatabaseTest.identity, identity)
            assertEquals(this@CloudTaskStreamWakeDatabaseTest.taskId, taskId)
            memoryQueries.incrementAndGet()
            if (!toolReady) return null
            return CloudMemoryTool(requestId, taskId, generation, "save_1", "memory.upsert", buildJsonObject {
                put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
            })
        }
        override suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject) {
            assertEquals(requestId, tool.requestId)
            assertEquals(generation, tool.generation)
            replies.send(observation)
            toolReady = false
        }
        override suspend fun events(identity: CloudTaskIdentity, taskId: String, after: Int, consume: suspend (CloudTaskEvent) -> Unit) {
            assertEquals(0, after)
            subscriptions.incrementAndGet()
            try {
                for (event in events) {
                    try {
                        consume(event)
                        acceptedEvents.incrementAndGet()
                    } catch (_: IllegalArgumentException) {
                        // 替身保留连接，单独核验 accept 拒绝时不唤醒；断流降级仍由原有测试覆盖。
                        rejectedEvents.incrementAndGet()
                    }
                }
            } finally { cancellations.incrementAndGet() }
        }
    }
    private fun data(value: CommandOutcome) = (value as CommandOutcome.AcceptedWithData).data
    private suspend fun drainUntil(message: String, check: () -> Boolean) {
        val completed = withTimeoutOrNull(4000) {
            do {
                dispatcher.scheduler.runCurrent()
                if (check()) return@withTimeoutOrNull true
                delay(10)
            } while (true)
        }
        assertEquals(message, true, completed)
    }
    private fun emit(sequence: Int, kind: String, round: Int = 1, text: String? = null, outcome: String? = null, eventTaskId: String = taskId) {
        assertTrue(events.trySend(CloudTaskEvent(eventTaskId, sequence, round, kind, text, outcome)).isSuccess)
    }
    private suspend fun assertNotExecuted() {
        assertEquals(0L, dispatcher.scheduler.currentTime)
        assertTrue(replies.tryReceive().isFailure)
        assertEquals(0, database.memoryDao().count("local"))
        assertEquals(1, submits.get())
    }

    @Before fun open() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        service = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, memoryConsent = consent)
        service.setMcpForeground(true)
        data(service.handle("cloud.memoryConsent", buildJsonObject {
            put("identityToken", consent.context(identity)); put("enabled", true)
        }))
        val conversationId = data(service.handle("cloud.newConversation", buildJsonObject {})).getValue("conversationId")
        val prepared = data(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", "我喜欢夜跑"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversationId)
        }))
        requestId = prepared.getValue("token").jsonPrimitive.content
        data(service.handle("cloud.confirm", buildJsonObject { put("token", requestId); put("approved", true) }))
        drainUntil("首次空记忆查询应完成且订阅已建立") { memoryQueries.get() == 1 && subscriptions.get() == 1 }
        assertEquals(0, gets.get())
        assertNotExecuted()
    }
    @After fun close() = runBlocking {
        scope.cancel()
        drainUntil("合成任务应完成取消") { scope.coroutineContext[Job]!!.isCompleted }
        events.close(); replies.close(); database.close()
    }

    @Test fun 连续文本不轮询且合法工具轮结束在虚拟零毫秒内处理原任务记忆() = runBlocking {
        emit(1, "round_start")
        repeat(32) { emit(it + 2, "text_delta", text = "合成片段") }
        drainUntil("所有文本片段应已通过正式校验") { acceptedEvents.get() == 33 }
        assertEquals(0, gets.get())
        assertEquals(1, memoryQueries.get())
        assertNotExecuted()

        toolReady = true
        emit(34, "round_end", outcome = "tools")
        drainUntil("合法工具轮结束应在不推进 2000ms 轮询时钟时再次查询记忆") { memoryQueries.get() == 2 }
        drainUntil("合法工具应完成实际 Room 保存及原任务回传") { !replies.isEmpty }
        assertEquals(JsonPrimitive("saved"), replies.tryReceive().getOrThrow()["status"])
        assertEquals("我喜欢夜跑", database.memoryDao().page("local", 0, 20).items.single().content)
        assertEquals(0L, dispatcher.scheduler.currentTime)
        assertEquals(1, gets.get())
        assertEquals(2, memoryQueries.get())
        assertEquals(1, submits.get())
        assertNull(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
    }

    @Test fun 旧序号错误任务及非法轮次的工具事件不唤醒查询或执行() = runBlocking {
        emit(1, "round_start")
        drainUntil("首轮应已开始") { acceptedEvents.get() == 1 }
        toolReady = true
        emit(1, "round_end", outcome = "tools")
        emit(2, "round_end", outcome = "tools", eventTaskId = UUID.randomUUID().toString())
        emit(2, "round_end", round = 2, outcome = "tools")
        drainUntil("三个非法事件应被正式 accept 拒绝") { rejectedEvents.get() == 3 }
        assertEquals(1, acceptedEvents.get())
        assertEquals(0, gets.get())
        assertEquals(1, memoryQueries.get())
        assertNotExecuted()
    }

    @Test fun 后台关闭订阅后迟到工具轮事件不再唤醒或执行() = runBlocking {
        emit(1, "round_start")
        drainUntil("首轮应已开始") { acceptedEvents.get() == 1 }
        service.setMcpForeground(false)
        drainUntil("关闭订阅后的既有降级查询应完成") { cancellations.get() == 1 && memoryQueries.get() == 2 }
        assertEquals(1, gets.get())
        toolReady = true
        emit(2, "round_end", outcome = "tools")
        dispatcher.scheduler.runCurrent()
        assertEquals(1, acceptedEvents.get())
        assertEquals(1, gets.get())
        assertEquals(2, memoryQueries.get())
        assertNotExecuted()
    }
}
