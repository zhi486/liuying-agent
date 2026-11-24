package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Room 与服务调度配合固定事件替身；不访问外部网络或模型。 */
@RunWith(AndroidJUnit4::class)
class CloudTaskStreamDatabaseTest {
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var service: CloudAgentService
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val taskId = UUID.randomUUID().toString()
    private val events = Channel<CloudTaskEvent>(Channel.UNLIMITED)
    private val submits = AtomicInteger()
    private val subscriptions = AtomicInteger()
    private val cancellations = AtomicInteger()
    private val cursors = Channel<Int>(Channel.UNLIMITED)
    @Volatile private var done = false
    @Volatile private var failGet = false
    private var requestId = ""
    private var conversationId = ""
    private fun response() = CloudTaskResponse(taskId, if (done) "completed" else "running", null,
        1000, 31000, if (done) 2000 else 1000, if (done) "available" else "none",
        if (done) buildJsonObject { put("status", "completed"); put("answer", "正式回执回答"); put("actions", JsonArray(emptyList())) } else null)
    private val api = object : CloudTaskApi {
        override val supportsStreaming = true
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse { submits.incrementAndGet(); return response() }
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse {
            if (failGet) throw CloudTaskFailure("network_unavailable")
            return response()
        }
        override suspend fun find(identity: CloudTaskIdentity, requestId: String) = response()
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse {
            return when (action) {
                "acknowledge" -> response().copy(resultState = "acknowledged", result = null)
                "withdraw" -> response().copy(status = "cancelled", reason = "consent_revoked", resultState = "withdrawn", result = null)
                else -> error("合成测试禁止其他控制")
            }
        }
        override suspend fun events(identity: CloudTaskIdentity, taskId: String, after: Int, consume: suspend (CloudTaskEvent) -> Unit) {
            subscriptions.incrementAndGet()
            cursors.send(after)
            try {
                for (event in events) {
                    if (event.kind == "synthetic_disconnect") throw CloudTaskFailure("network_unavailable")
                    consume(event)
                    if (event.kind == "task_end") return
                }
            } finally { cancellations.incrementAndGet() }
        }
    }
    private fun data(value: CommandOutcome) = (value as CommandOutcome.AcceptedWithData).data
    private suspend fun listing() = data(service.handle("cloud.list", buildJsonObject {}))
    private suspend fun item() = listing().getValue("items").jsonArray.single().jsonObject
    private suspend fun until(check: suspend () -> Boolean) = withTimeout(6000) {
        while (!check()) delay(20)
    }
    private suspend fun emit(seq: Int, kind: String, round: Int = 1, text: String? = null, outcome: String? = null, status: String? = null) {
        events.send(CloudTaskEvent(taskId, seq, round, kind, text, outcome, status))
    }
    private suspend fun prefix() {
        emit(1, "round_start"); emit(2, "text_delta", text = "临时工具说明")
        until { item()["draftAnswer"] == JsonPrimitive("临时工具说明") }
    }
    @Before fun open() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        service = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 })
        service.setMcpForeground(true)
        conversationId = data(service.handle("cloud.newConversation", buildJsonObject {})).getValue("conversationId").jsonPrimitive.content
        val prepared = data(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", "合成问题"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversationId)
        }))
        requestId = prepared.getValue("token").jsonPrimitive.content
        data(service.handle("cloud.confirm", buildJsonObject { put("token", requestId); put("approved", true) }))
        until { subscriptions.get() == 1 }
    }
    @After fun close() = runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join(); events.close(); database.close() }

    @Test fun 草稿只在内存工具轮清空且终态只保存正式回执() = runBlocking {
        prefix()
        assertNull(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
        emit(3, "round_end", outcome = "tools")
        until { item()["draftAnswer"] == null }
        emit(4, "round_start", round = 2); emit(5, "text_delta", round = 2, text = "不同的候选答案")
        until { item()["draftAnswer"] == JsonPrimitive("不同的候选答案") }
        emit(6, "round_end", round = 2, outcome = "answer")
        done = true
        emit(7, "task_end", round = 2, status = "completed")
        until { item()["busy"] == JsonPrimitive(false) }
        val item = item()
        assertNull(item["draftAnswer"])
        assertEquals(JsonPrimitive("正式回执回答"), item["answer"])
        assertFalse(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson!!.contains("候选"))
        assertEquals(1, submits.get())
    }

    @Test fun 后台立即清除草稿并取消订阅且回前台不自动重发() = runBlocking {
        prefix()
        service.setMcpForeground(false)
        assertNull(item()["draftAnswer"])
        until { cancellations.get() == 1 }
        service.setMcpForeground(true)
        delay(100)
        assertNull(item()["draftAnswer"])
        assertEquals(1, subscriptions.get()); assertEquals(1, submits.get())
        assertNull(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
    }

    @Test fun 切换新会话清除订阅且返回旧会话不显示旧草稿() = runBlocking {
        prefix()
        data(service.handle("cloud.newConversation", buildJsonObject {}))
        assertTrue(listing().getValue("items").jsonArray.isEmpty())
        until { cancellations.get() == 1 }
        data(service.handle("cloud.selectConversation", buildJsonObject { put("conversationId", conversationId) }))
        assertNull(item()["draftAnswer"])
        assertEquals(1, subscriptions.get()); assertEquals(1, submits.get())
    }

    @Test fun 断流隐藏片段且显式恢复沿用原游标不重发问题() = runBlocking {
        prefix()
        assertEquals(0, withTimeout(1000) { cursors.receive() })
        failGet = true
        emit(3, "synthetic_disconnect")
        until { item()["busy"] == JsonPrimitive(false) }
        assertNull(item()["draftAnswer"])
        assertEquals(1, subscriptions.get())
        failGet = false
        data(service.handle("cloud.recover", buildJsonObject { put("requestId", requestId) }))
        assertEquals(2, withTimeout(3000) { cursors.receive() })
        emit(3, "text_delta", text = "，只读续段")
        until { item()["draftAnswer"] == JsonPrimitive("临时工具说明，只读续段") }
        assertEquals(1, submits.get()); assertEquals(2, subscriptions.get())
        assertNull(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
    }

    @Test fun 撤回操作立即移除草稿且迟到事件不再交付() = runBlocking {
        prefix()
        data(service.handle("cloud.withdraw", buildJsonObject { put("requestId", requestId) }))
        assertNull(item()["draftAnswer"])
        until { cancellations.get() == 1 && item()["busy"] == JsonPrimitive(false) }
        emit(3, "text_delta", text = "不得显示的迟到片段")
        assertNull(item()["draftAnswer"])
        assertEquals(JsonPrimitive("withdraw"), item()["confirmedControl"])
        assertNull(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
        assertEquals(1, submits.get()); assertEquals(1, subscriptions.get())
    }
}
