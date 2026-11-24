package com.healthyagent.running.agent.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.coordinator.CommandOutcome
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudAgentServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private var summaryServer: okhttp3.mockwebserver.MockWebServer? = null
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val memoryId = UUID.randomUUID().toString()
    private val calls = CopyOnWriteArrayList<String>()
    private val purgeCalls = CopyOnWriteArrayList<Pair<CloudTaskIdentity, String>>()
    private var purgeResponse: suspend (CloudTaskIdentity, String) -> Unit = { _, _ -> }
    private var selected: JsonObject? = null
    private var submittedIntent: CloudTaskIntent? = null
    private var clock = 1000L
    private var activeIdentity: CloudTaskIdentity? = identity
    private var storedConsent: String? = null
    private val chatConsent = CloudChatConsent({ storedConsent }, { storedConsent = it })
    private var storedMemoryConsent: String? = null
    private val memoryConsent = com.healthyagent.running.agent.memory.MemoryToolConsent({ storedMemoryConsent }, { storedMemoryConsent = it })
    private var response: suspend (String) -> CloudTaskResponse = { view(if (it == "acknowledge") "acknowledged" else if (it == "withdraw") "withdrawn" else "available") }
    private val api = object : CloudTaskApi {
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse { submittedIntent = intent; calls += "submit"; return response("submit") }
        override suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject): CloudTaskResponse { selected = memoryContext; return submit(intent) }
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse { calls += "find"; return response("find") }
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse { calls += "get"; return response("get") }
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse { calls += action; return response(action) }
        override suspend fun purge(identity: CloudTaskIdentity, requestId: String) {
            calls += "purge"; purgeCalls += identity to requestId; purgeResponse(identity, requestId)
        }
    }
    private fun view(state: String = "available") = CloudTaskResponse("33333333-3333-4333-8333-333333333333", "completed", null, 1000, 31000, 2000, state,
        if (state == "available") buildJsonObject { put("status", "completed"); put("answer", "记得你喜欢晚跑，我们轻松一点。"); put("actions", JsonArray(emptyList())) } else null)
    private var runSummary: JsonObject? = null
    private val loadedRuns = mutableListOf<String>()
    private fun service() = CloudAgentService(database, { activeIdentity }, { api }, scope,
        loadRunSummary = { id -> loadedRuns += id; runSummary }, now = { clock }, chatConsent = chatConsent, memoryConsent = memoryConsent)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun data(outcome: CommandOutcome) = (outcome as CommandOutcome.AcceptedWithData).data
    private suspend fun prepare(service: CloudAgentService, withMemory: Boolean = true): String {
        val refs = if (withMemory) "[{\"id\":\"$memoryId\",\"version\":1}]" else "[]"
        return data(service.handle("cloud.prepare", json("""{"prompt":"今晚怎么跑","memoryRefs":$refs}"""))).getValue("token").jsonPrimitive.content
    }
    private suspend fun confirm(service: CloudAgentService, token: String) = service.handle("cloud.confirm", json("""{"token":"$token","approved":true}"""))
    private suspend fun prepareIn(service: CloudAgentService, conversationId: String): String =
        data(service.handle("cloud.prepare", json("""{"prompt":"合成会话问题","memoryRefs":[],"conversationId":"$conversationId"}""")))
            .getValue("token").jsonPrimitive.content
    private suspend fun cleanupIdle(service: CloudAgentService) {
        withTimeout(10000) {
            while (data(service.handle("cloud.list", json("{}"))).getValue("cleanupBusy").jsonPrimitive.boolean) delay(20)
        }
    }
    private suspend fun idle(service: CloudAgentService) {
        withTimeout(10000) { while (data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.any { it.jsonObject.getValue("busy").jsonPrimitive.boolean }) delay(20) }
    }
    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database.memoryDao().create("local", memoryId, "喜欢晚跑", clock)
        Unit
    }
    @After fun tearDown() { summaryServer?.shutdown(); scope.cancel(); database.close() }

    @Test fun 连接失败无回执后允许准备新问题且不重发旧任务() = runBlocking {
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        response = { throw CloudTaskFailure("tls_failed") }
        confirm(service, prepareIn(service, conversation))
        idle(service)
        assertEquals(listOf("submit"), calls.toList())
        val next = data(service.handle("cloud.prepare", json("""{"prompt":"下一条新问题","memoryRefs":[],"conversationId":"$conversation"}""")))
        assertEquals("下一条新问题", next.getValue("prompt").jsonPrimitive.content)
        assertTrue(next.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
        assertEquals(listOf("submit"), calls.toList())
        assertEquals(1, data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.size)
    }

    @Test fun 云端运行回执查询失败后新问题不继承未知回答也不补发() = runBlocking {
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        response = { action ->
            if (action != "submit") throw CloudTaskFailure("network_timeout")
            view().copy(status = "running", resultState = "none", result = null)
        }
        val token = prepareIn(service, conversation)
        confirm(service, token)
        idle(service)
        assertEquals("running", database.cloudIntentDao().receipt(identity, token)!!.status)
        val before = calls.toList()
        assertEquals(listOf("submit", "get"), before)
        val next = data(service.handle("cloud.prepare", json("""{"prompt":"查询失败后的新问题","memoryRefs":[],"conversationId":"$conversation"}""")))
        assertTrue(next.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
        assertEquals(before, calls.toList())
        assertEquals("running", database.cloudIntentDao().receipt(identity, token)!!.status)
        assertTrue(database.cloudIntentDao().find(identity, token)!!.submissionAttempted)
    }

    @Test fun 本机仍执行的回合拒绝新问题且不增加提交() = runBlocking {
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        response = { entered.complete(Unit); release.await(); view() }
        confirm(service, prepareIn(service, conversation))
        withTimeout(10000) { entered.await() }
        try {
            assertTrue(service.handle("cloud.prepare", json("""{"prompt":"不应并发发送","memoryRefs":[],"conversationId":"$conversation"}""")) is CommandOutcome.Rejected)
            assertEquals(listOf("submit"), calls.toList())
        } finally { release.complete(Unit) }
        idle(service)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun 已确认保存后暂停调度再归档仍允许原纯文字及选中记忆任务完成() = runBlocking {
        for (withMemory in listOf(false, true)) {
            scope.cancel()
            val dispatcher = StandardTestDispatcher()
            scope = CoroutineScope(SupervisorJob() + dispatcher)
            val cloudId = UUID.randomUUID().toString()
            response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = cloudId) }
            val service = service()
            val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
            val refs = if (withMemory) "[{\"id\":\"$memoryId\",\"version\":1}]" else "[]"
            val token = data(service.handle("cloud.prepare", json("""{"prompt":"合成已授权任务","memoryRefs":$refs,"conversationId":"$conversation"}""")))
                .getValue("token").jsonPrimitive.content
            val before = calls.toList()
            assertTrue(confirm(service, token) is CommandOutcome.AcceptedWithData)
            assertFalse(database.cloudIntentDao().find(identity, token)!!.submissionAttempted)
            data(service.handle("cloud.archiveConversation", json("""{"conversationId":"$conversation"}""")))
            assertEquals(before, calls.toList())
            withTimeout(10000) {
                do { dispatcher.scheduler.runCurrent(); delay(20) }
                while (scope.coroutineContext[Job]!!.children.any { it.isActive })
            }
            assertTrue(database.cloudIntentDao().find(identity, token)!!.submissionAttempted)
            assertNotNull(database.cloudIntentDao().receipt(identity, token)!!.resultJson)
            assertNotNull(database.conversationDao().find(identity, conversation)!!.archivedAtMillis)
            assertEquals(before + listOf("submit", "acknowledge"), calls.toList())
        }
    }

    @Test fun 归档可选中阅读但恢复前不能发送或恢复执行且旧预览失效() = runBlocking {
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val token = prepareIn(service, conversation)
        confirm(service, token); idle(service)
        database.cloudIntentDao().save(CloudTaskIntent.confirm(identity, "合成旧版历史", true, clock))
        val stalePreview = prepareIn(service, conversation)
        val before = calls.toList()
        val target = json("""{"conversationId":"$conversation"}""")
        val archived = data(service.handle("cloud.archiveConversation", target))
        assertEquals(JsonNull, archived["conversationId"])
        assertTrue(archived.getValue("items").jsonArray.isEmpty())
        assertTrue(archived.getValue("legacyAvailable").jsonPrimitive.boolean)
        assertTrue(archived.getValue("conversations").jsonArray.single().jsonObject.getValue("archived").jsonPrimitive.boolean)
        assertEquals(before, calls.toList())
        assertTrue(confirm(service, stalePreview) is CommandOutcome.Rejected)
        val reading = data(service.handle("cloud.selectConversation", target))
        assertTrue(reading.getValue("conversationArchived").jsonPrimitive.boolean)
        assertEquals("合成会话问题", reading.getValue("items").jsonArray.single().jsonObject.getValue("prompt").jsonPrimitive.content)
        assertTrue(reading.getValue("items").jsonArray.single().jsonObject.getValue("answer") != JsonNull)
        assertTrue(service.handle("cloud.prepare", json("""{"prompt":"继续","memoryRefs":[],"conversationId":"$conversation"}""")) is CommandOutcome.Rejected)
        assertTrue(service.handle("cloud.recover", json("""{"requestId":"$token"}""")) is CommandOutcome.Rejected)
        data(service.handle("cloud.restoreConversation", target))
        prepareIn(service, conversation)
        assertFalse(data(service.handle("cloud.list", json("{}"))).getValue("conversationArchived").jsonPrimitive.boolean)
        assertEquals(before, calls.toList())
        assertEquals(1, database.conversationDao().list(identity).size)
    }

    @Test fun 删除当前会话进入持久空白态而不选中旧版历史且取消确认不写库() = runBlocking {
        val legacy = CloudTaskIntent.confirm(identity, "合成旧版历史", true, clock)
        database.cloudIntentDao().save(legacy)
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val token = prepareIn(service, conversation)
        confirm(service, token); idle(service)
        val stalePreview = prepareIn(service, conversation)
        val before = calls.toList()
        assertTrue(service.handle("cloud.deleteConversation", json("""{"conversationId":"$conversation","confirmed":false}""")) is CommandOutcome.Rejected)
        assertNotNull(database.conversationDao().find(identity, conversation))
        assertEquals(before, calls.toList())
        val cleared = data(service.handle("cloud.deleteConversation", json("""{"conversationId":"$conversation","confirmed":true}""")))
        assertEquals(JsonNull, cleared["conversationId"])
        assertTrue(cleared.getValue("items").jsonArray.isEmpty())
        cleanupIdle(service)
        assertTrue(confirm(service, stalePreview) is CommandOutcome.Rejected)
        val restored = data(service().handle("cloud.list", json("{}")))
        assertTrue(restored.getValue("items").jsonArray.isEmpty())
        assertTrue(restored.getValue("legacyAvailable").jsonPrimitive.boolean)
        assertTrue(restored.getValue("conversations").jsonArray.isEmpty())
        assertEquals(listOf(identity to token), purgeCalls.toList())
        assertNull(database.cloudIntentDao().find(identity, token))
        assertEquals(legacy, database.cloudIntentDao().find(identity, legacy.clientRequestId))
        assertNotNull(database.memoryDao().find("local", memoryId))
        val usage = restored.getValue("retainedUsage").jsonArray.single().jsonObject
        assertEquals(setOf("requestId", "atMillis", "cost"), usage.keys)
        assertEquals(JsonPrimitive(token), usage["requestId"])
        assertEquals(JsonNull, usage["cost"])
        val next = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        assertNotEquals(conversation, next)
        assertEquals(1, database.conversationDao().list(identity).size)
        prepareIn(service, next)
        assertEquals(1, calls.count { it == "submit" })
    }

    @Test fun 归档不取消已授权运行的任务且完成后回答仍属于原归档会话() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        response = { action ->
            if (action == "submit") { entered.complete(Unit); release.await(); view() } else view("acknowledged")
        }
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val token = prepareIn(service, conversation)
        confirm(service, token)
        withTimeout(10000) { entered.await() }
        try {
            val target = json("""{"conversationId":"$conversation"}""")
            data(service.handle("cloud.archiveConversation", target))
            val reading = data(service.handle("cloud.selectConversation", target))
            assertTrue(reading.getValue("conversationArchived").jsonPrimitive.boolean)
            assertTrue(reading.getValue("items").jsonArray.single().jsonObject.getValue("busy").jsonPrimitive.boolean)
        } finally { release.complete(Unit) }
        idle(service)
        assertNotNull(database.cloudIntentDao().receipt(identity, token)!!.resultJson)
        assertNotNull(database.conversationDao().find(identity, conversation)!!.archivedAtMillis)
        assertEquals(listOf("submit", "acknowledge"), calls.toList())
        assertTrue(purgeCalls.isEmpty())
    }

    @Test fun 清理失败保留待办且重建宿主不自动发网更换身份不能清旧键() = runBlocking {
        val intent = CloudTaskIntent.confirm(identity, "合成待清理正文", true, clock)
        database.cloudIntentDao().save(intent)
        assertTrue(database.cloudIntentDao().claimSubmission(identity, intent.clientRequestId, clock))
        purgeResponse = { _, _ -> throw CloudTaskFailure("network_unavailable") }
        val service = service()
        data(service.handle("cloud.deleteConversation", json("""{"conversationId":null,"confirmed":true}""")))
        cleanupIdle(service)
        assertEquals(1, data(service.handle("cloud.list", json("{}"))).getValue("cleanupPending").jsonPrimitive.int)
        assertNull(database.cloudIntentDao().find(identity, intent.clientRequestId))
        val reopened = service()
        data(reopened.handle("cloud.list", json("{}")))
        assertEquals(listOf(identity to intent.clientRequestId), purgeCalls.toList())
        val others = listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"),
            identity.copy(deviceId = "other"), identity.copy(credentialRef = "rotated"))
        for (other in others) {
            activeIdentity = other
            val listing = data(reopened.handle("cloud.retryConversationCleanup", json("{}")))
            assertEquals(0, listing.getValue("cleanupPending").jsonPrimitive.int)
            cleanupIdle(reopened)
        }
        assertEquals(1, purgeCalls.size)
        assertEquals(1, database.conversationLifecycleDao().pending(identity).size)
        activeIdentity = identity
        purgeResponse = { _, _ -> }
        data(reopened.handle("cloud.retryConversationCleanup", json("{}")))
        cleanupIdle(reopened)
        assertTrue(database.conversationLifecycleDao().pending(identity).isEmpty())
        assertEquals(List(2) { identity to intent.clientRequestId }, purgeCalls.toList())
        assertTrue(calls.all { it == "purge" })
    }

    @Test fun 删除后迟到提交响应不写回正文也不能从恢复入口重新执行() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        response = { action ->
            if (action == "submit") {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await(); returned.complete(Unit); view() }
            } else view("acknowledged")
        }
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val token = prepareIn(service, conversation)
        confirm(service, token)
        withTimeout(10000) { entered.await() }
        try {
            data(service.handle("cloud.deleteConversation", json("""{"conversationId":"$conversation","confirmed":true}""")))
            assertNull(database.cloudIntentDao().find(identity, token))
        } finally { release.complete(Unit) }
        withTimeout(10000) {
            returned.await()
            while (scope.coroutineContext[Job]!!.children.any { it.isActive }) delay(20)
        }
        assertNull(database.cloudIntentDao().find(identity, token))
        assertNull(database.cloudIntentDao().receipt(identity, token))
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
        assertTrue(data(service().handle("cloud.list", json("{}"))).getValue("items").jsonArray.isEmpty())
        assertTrue(service.handle("cloud.recover", json("""{"requestId":"$token"}""")) is CommandOutcome.Rejected)
        assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        assertEquals(listOf(identity to token), purgeCalls.toList())
        assertEquals(1, calls.count { it == "submit" })
        assertEquals(0, calls.count { it == "acknowledge" || it == "find" })
    }

    @Test fun 持续授权直发仅接受当前身份纯文字且单个令牌最多提交一次() = runBlocking {
        storedConsent = "{}"
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val input = json("""{"prompt":"你好","memoryRefs":[],"conversationId":"$conversation"}""")
        suspend fun send(): CommandOutcome {
            val token = data(service.handle("cloud.prepare", input)).getValue("token").jsonPrimitive.content
            return service.handle("cloud.send", json("""{"token":"$token"}"""))
        }
        assertTrue(send() is CommandOutcome.Rejected)
        assertTrue(calls.isEmpty())
        data(service.handle("cloud.chatConsent", json("""{"identityToken":"${CloudChatConsent.context(identity)}","enabled":true}""")))
        val token = data(service.handle("cloud.prepare", input)).getValue("token").jsonPrimitive.content
        assertTrue(service.handle("cloud.send", json("""{"token":"$token"}""")) is CommandOutcome.AcceptedWithData)
        assertTrue(service.handle("cloud.send", json("""{"token":"$token"}""")) is CommandOutcome.Rejected)
        idle(service)
        assertEquals(1, calls.count { it == "submit" })
        runSummary = json("""{"runId":"run-1","distanceMeters":2000,"durationSeconds":720,"averagePaceSecPerKm":360,"averageHeartRate":null}""")
        for (extraInput in listOf(
            """{"prompt":"问题","memoryRefs":[{"id":"$memoryId","version":1}],"conversationId":"$conversation"}""",
            """{"prompt":"问题","memoryRefs":[],"conversationId":"$conversation","runId":"run-1"}""",
        )) {
            val extra = data(service.handle("cloud.prepare", json(extraInput))).getValue("token").jsonPrimitive.content
            assertTrue(service.handle("cloud.send", json("""{"token":"$extra"}""")) is CommandOutcome.Rejected)
        }
        data(service.handle("cloud.chatConsent", json("""{"identityToken":"${CloudChatConsent.context(identity)}","enabled":false}""")))
        assertTrue(send() is CommandOutcome.Rejected)
        activeIdentity = identity.copy(credentialRef = "rotated")
        assertFalse(data(service.handle("cloud.list", json("{}"))).getValue("chatAuthorized").jsonPrimitive.boolean)
        assertEquals(1, calls.count { it == "submit" })
    }

    @Test fun 默认双库直发保留选择且关闭后旧预览不能提交() = runBlocking {
        val service = service()
        assertTrue(data(service.handle("cloud.list", json("{}"))).getValue("chatAuthorized").jsonPrimitive.boolean)
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        for (library in listOf("both", "health", "firefly")) {
            val cloudId = UUID.randomUUID().toString()
            response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = cloudId) }
            val knowledge = json("""{"version":"curated_knowledge_v1","library":"$library","allowSpoilers":false}""")
            val input = json("""{"prompt":"合成知识问题","memoryRefs":[],"conversationId":"$conversation","knowledge":$knowledge}""")
            val preview = data(service.handle("cloud.prepare", input))
            assertEquals(knowledge, preview["knowledge"])
            val token = preview.getValue("token").jsonPrimitive.content
            assertTrue(service.handle("cloud.send", json("""{"token":"$token"}""")) is CommandOutcome.AcceptedWithData)
            assertTrue(service.handle("cloud.send", json("""{"token":"$token"}""")) is CommandOutcome.Rejected)
            idle(service)
            val receipt = database.cloudIntentDao().receipt(identity, token)!!
            assertEquals(cloudId, receipt.cloudTaskId)
            assertEquals("completed", receipt.status)
            assertNotNull(receipt.resultJson)
            assertEquals(knowledge, database.cloudIntentDao().find(identity, token)!!.knowledge())
            assertEquals(knowledge, submittedIntent!!.knowledge())
            assertTrue(submittedIntent!!.memoryRefs().isEmpty())
            assertNull(submittedIntent!!.runSummary())
            assertNull(submittedIntent!!.mcpToolsJson)
        }
        assertEquals(3, calls.count { it == "submit" })
        val token = prepareIn(service, conversation)
        data(service.handle("cloud.chatConsent", json("""{"identityToken":"${CloudChatConsent.context(identity)}","enabled":false}""")))
        assertTrue(service.handle("cloud.send", json("""{"token":"$token"}""")) is CommandOutcome.Rejected)
        assertFalse(data(service().handle("cloud.list", json("{}"))).getValue("chatAuthorized").jsonPrimitive.boolean)
        assertEquals(3, calls.count { it == "submit" })
        assertTrue(loadedRuns.isEmpty())
    }

    @Test fun 会话隔离与恢复保留多条消息上下文() = runBlocking {
        val service = service()
        val first = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val request = json("""{"prompt":"我叫阿然","memoryRefs":[],"conversationId":"$first"}""")
        val preview = data(service.handle("cloud.prepare", request))
        assertTrue(preview.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
        assertTrue(confirm(service, preview.getValue("token").jsonPrimitive.content) is CommandOutcome.AcceptedWithData)
        idle(service)
        val followup = data(service.handle("cloud.prepare", json("""{"prompt":"我叫什么","memoryRefs":[],"conversationId":"$first"}""")))
        val history = followup.getValue("conversation").jsonObject.getValue("messages").jsonArray
        assertEquals(2, history.size)
        assertEquals("我叫阿然", history[0].jsonObject.getValue("text").jsonPrimitive.content)
        val second = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        assertNotEquals(first, second)
        assertTrue(confirm(service, followup.getValue("token").jsonPrimitive.content) is CommandOutcome.Rejected)
        assertTrue(data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.isEmpty())
        val resumed = service()
        data(resumed.handle("cloud.selectConversation", json("""{"conversationId":"$first"}""")))
        val restored = data(resumed.handle("cloud.prepare", request)).getValue("conversation").jsonObject
        assertEquals(history, restored.getValue("messages"))
        val other = identity.copy(ownerId = "other")
        assertNull(database.conversationDao().find(other, first))
        assertEquals(1, calls.count { it == "submit" })
    }

    @Test fun 删除记忆后直接与间接继承的历史均排除且旧预览拒绝() = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val first = data(service.handle("cloud.prepare", json("""{"prompt":"合成偏好问题","memoryRefs":[{"id":"$memoryId","version":1}],"conversationId":"$conversation"}""")))
            .getValue("token").jsonPrimitive.content
        assertTrue(confirm(service, first) is CommandOutcome.AcceptedWithData)
        idle(service)
        val second = prepareIn(service, conversation)
        assertTrue(confirm(service, second) is CommandOutcome.AcceptedWithData)
        idle(service)
        val inherited = database.memoryContextDependenciesDao().find(second)!!.state()
        assertEquals(setOf(com.healthyagent.running.agent.memory.MemoryRef(memoryId, 1)), inherited.refs)
        assertTrue(database.cloudIntentDao().find(identity, second)!!.memoryRefs().isEmpty())
        val preview = data(service.handle("cloud.prepare", json("""{"prompt":"后续合成问题","memoryRefs":[],"conversationId":"$conversation"}""")))
        assertEquals(4, preview.getValue("conversation").jsonObject.getValue("messages").jsonArray.size)
        database.memoryDao().delete("local", memoryId, 1, 2000)
        assertTrue(confirm(service, preview.getValue("token").jsonPrimitive.content) is CommandOutcome.Rejected)
        val restored = service()
        val fresh = data(restored.handle("cloud.prepare", json("""{"prompt":"后续合成问题","memoryRefs":[],"conversationId":"$conversation"}""")))
        assertTrue(fresh.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
        assertEquals(2, data(restored.handle("cloud.list", json("{}"))).getValue("items").jsonArray.size)
        assertEquals(2, calls.count { it == "submit" })
    }

    @Test fun 单次摘要执行器并发失联及取消均不重发提交() = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val host = service()
        val conversation = data(host.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) {
            clock++
            assertTrue(confirm(host, prepareIn(host, conversation)) is CommandOutcome.AcceptedWithData)
            idle(host)
        }
        val before = calls.count { it == "submit" }
        val request = UUID.randomUUID().toString()
        val candidate = host.summaryCandidate(conversation)!!.candidate!!
        assertTrue(host.prepareSummaryRequest(conversation, request))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sent = CopyOnWriteArrayList<String>()
        val http = CloudTaskHttpClient.clientBuilder().addInterceptor { chain ->
            sent += chain.request().method
            if (chain.request().method == "POST") {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            throw java.io.IOException("隔离合成失联")
        }.build()
        val client = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) }, http)
        val executor = ConversationSummaryExecutor(host, client)
        assertEquals(0, executor.recoverOnce())
        assertTrue(sent.isEmpty())
        val first = async(Dispatchers.IO) { runCatching { executor.execute(conversation, request, 0) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertFalse(ConversationSummaryExecutor(service(), client).execute(conversation, request, 0))
            assertEquals(listOf("POST"), sent.toList())
        } finally { release.countDown() }
        assertTrue(first.await().isFailure)
        val row = database.conversationSummaryRequestDao().find(request)!!
        assertEquals(1L, row.revision); assertTrue(row.attempted); assertFalse(row.remoteFinished)
        assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN.name, row.cost)
        assertTrue(runCatching { ConversationSummaryExecutor(service(), client).execute(conversation, request, 1) }.isFailure)
        assertEquals(listOf("POST", "GET"), sent.toList())
        val cancelled = async(start = CoroutineStart.LAZY) { executor.execute(conversation, request, 2) }
        cancelled.cancel(); cancelled.join()
        assertEquals(2L, database.conversationSummaryRequestDao().find(request)!!.revision)
        assertEquals(listOf("POST", "GET"), sent.toList())
        val waiting = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val waitingHttp = CloudTaskHttpClient.clientBuilder().addInterceptor { chain ->
            sent += chain.request().method
            waiting.countDown()
            check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw java.io.IOException("隔离取消")
        }.build()
        val waitingClient = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) }, waitingHttp)
        val pending = async(Dispatchers.IO) { ConversationSummaryExecutor(service(), waitingClient).execute(conversation, request, 2) }
        try {
            withContext(Dispatchers.IO) { assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            pending.cancel()
        } finally { finish.countDown() }
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(3L, database.conversationSummaryRequestDao().find(request)!!.revision)
        activeIdentity = identity.copy(credentialRef = "changed")
        assertEquals(0, executor.recoverOnce())
        assertFalse(executor.execute(conversation, request, 3))
        assertEquals(3L, database.conversationSummaryRequestDao().find(request)!!.revision)
        activeIdentity = identity
        val broken = UUID.randomUUID().toString()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO agent_summary_requests (requestId, origin, ownerId, deviceId, credentialRef, conversationId, fingerprint, snapshotJson, sourceIdsJson, referencesJson, createdAtMillis, revision, attempted, remoteFinished, cost, inputTokens, outputTokens) SELECT ?, origin, ownerId, deviceId, credentialRef, NULL, fingerprint, 'broken', NULL, NULL, createdAtMillis, revision, attempted, remoteFinished, cost, inputTokens, outputTokens FROM agent_summary_requests WHERE requestId = ?",
            arrayOf(broken, request))
        val body = buildJsonObject {
            put("version", "summary_v1"); put("purpose", "conversation_summary")
            put("requestId", request); put("fingerprint", candidate.fingerprint)
            put("state", "completed"); put("remoteFinished", true); put("summary", "合成摘要")
            put("cost", "usage"); put("usage", buildJsonObject { put("inputTokens", 7); put("outputTokens", 2) })
        }.toString()
        val successHttp = CloudTaskHttpClient.clientBuilder().addInterceptor { chain ->
            sent += chain.request().method
            assertEquals("GET", chain.request().method)
            assertNull(chain.request().body)
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val successClient = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) }, successHttp)
        assertEquals(1, ConversationSummaryExecutor(service(), successClient).recoverOnce())
        val completed = database.conversationSummaryRequestDao().find(request)!!
        assertEquals("合成摘要", ConversationSummaryProtocol.decode(completed.snapshotJson!!).summary)
        assertTrue(completed.remoteFinished)
        assertEquals(0, ConversationSummaryExecutor(service(), successClient).recoverOnce())
        assertEquals("broken", database.conversationSummaryRequestDao().find(broken)!!.snapshotJson)
        assertFalse(executor.execute(conversation, request, 5))
        assertEquals(listOf("POST", "GET", "GET", "GET"), sent.toList())
        assertEquals(before, calls.count { it == "submit" })
    }

    @Test fun 实际摘要处理器经回环桥接入Room且重授权拒绝旧正文() = verifySummaryLoopback(true)

    @Test fun 实际摘要工厂正文接纳后重开Room不再请求() = verifySummaryLoopback(false)

    private fun verifySummaryLoopback(renewConsent: Boolean) = runBlocking {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val port = arguments.getString("summaryPort")
        org.junit.Assume.assumeTrue("仅由隔离跨进程验收运行器提供参数", port != null)
        require(port!!.toInt() in 1..65535)
        database.close()
        val file = File(context.noBackupFilesDir, "summary-network-recovery-" + UUID.randomUUID() + ".db")
        fun openSummaryDatabase() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        database = openSummaryDatabase()
        val pem = String(android.util.Base64.decode(requireNotNull(arguments.getString("summaryCertificate")), android.util.Base64.NO_WRAP), Charsets.UTF_8)
        val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as java.security.cert.X509Certificate
        val tls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val identity = this@CloudAgentServiceTest.identity.copy(origin = "https://127.0.0.1:" + port)
        activeIdentity = identity
        val networkMethods = CopyOnWriteArrayList<String>()
        val client = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) },
            CloudTaskHttpClient.clientBuilder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
                .addNetworkInterceptor { chain ->
                    networkMethods += chain.request().method
                    chain.proceed(chain.request())
                }
                .eventListener(object : okhttp3.EventListener() {
                    override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                        if (renewConsent) {
                            val context = CloudChatConsent.context(identity)
                            chatConsent.set(identity, context, false); chatConsent.set(identity, context, true)
                        }
                    }
                })
                .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build())
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val host = service()
        val conversation = data(host.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) {
            clock++
            assertTrue(confirm(host, prepareIn(host, conversation)) is CommandOutcome.AcceptedWithData)
            idle(host)
        }
        val before = calls.count { it == "submit" }
        val request = UUID.randomUUID().toString()
        assertTrue(host.prepareSummaryRequest(conversation, request))
        assertTrue(runCatching { ConversationSummaryExecutor(host, client).execute(conversation, request, 0) }.isFailure)
        assertEquals(1L, database.conversationSummaryRequestDao().find(request)!!.revision)
        scope.cancel()
        database.close()
        database = openSummaryDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        assertEquals(1, ConversationSummaryExecutor(service(), client).recoverOnce())
        val saved = database.conversationSummaryRequestDao().find(request)!!
        assertTrue(saved.remoteFinished)
        assertEquals(7L, saved.inputTokens); assertEquals(2L, saved.outputTokens)
        assertEquals(if (renewConsent) null else "跨进程合成摘要",
            ConversationSummaryProtocol.decode(saved.snapshotJson!!).summary)
        scope.cancel()
        database.close()
        database = openSummaryDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        assertEquals(saved, database.conversationSummaryRequestDao().find(request))
        assertFalse(ConversationSummaryExecutor(service(), client).execute(conversation, request, 0))
        assertEquals(0, ConversationSummaryExecutor(service(), client).recoverOnce())
        assertEquals(listOf("POST", "GET"), networkMethods.toList())
        assertEquals(before, calls.count { it == "submit" })
    }

    @Test fun 网络摘要回执撤回未结束阻挡替代且重授权拒绝正文() = runBlocking {
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer().also { summaryServer = it }
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        val identity = this@CloudAgentServiceTest.identity.copy(origin = "https://127.0.0.1:${server.port}")
        activeIdentity = identity
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val host = service()
        val conversation = data(host.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) {
            clock++
            assertTrue(confirm(host, prepareIn(host, conversation)) is CommandOutcome.AcceptedWithData)
            idle(host)
        }
        val before = calls.count { it == "submit" }
        val request = UUID.randomUUID().toString()
        val original = host.summaryCandidate(conversation)!!.candidate!!
        val summaryRequests = mutableListOf<String>()
        suspend fun receipt(requestId: String, fingerprint: String, state: String, finished: Boolean, used: Boolean,
            step: ConversationSummaryProtocol.Step, candidate: ConversationSummaryPolicy.Candidate?) : ConversationSummaryHttpClient.Receipt {
            val body = buildJsonObject {
                put("version", "summary_v1"); put("purpose", "conversation_summary")
                put("requestId", requestId); put("fingerprint", fingerprint); put("state", state)
                put("remoteFinished", finished)
                put("summary", if (state == "completed") JsonPrimitive("不得恢复的合成正文") else JsonNull)
                put("cost", if (used) "usage" else "uncertain")
                put("usage", if (used) buildJsonObject { put("inputTokens", 7); put("outputTokens", 2) } else JsonNull)
            }.toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val client = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) },
                CloudTaskHttpClient.clientBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build())
            val result = client.execute(identity, step, candidate)
            val sent = requireNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
            summaryRequests += requireNotNull(sent.method)
            assertEquals("Bearer lyd_" + "a".repeat(64), sent.getHeader("Authorization"))
            assertNotNull(sent.handshake)
            if (step.action == ConversationSummaryProtocol.Action.SUBMIT) {
                assertEquals("/v1/agent/summaries", sent.path)
                val submitted = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertEquals(requestId, submitted["requestId"]!!.jsonPrimitive.content)
                assertEquals(fingerprint, submitted["fingerprint"]!!.jsonPrimitive.content)
                assertEquals(candidate!!.sources.size, submitted["sources"]!!.jsonArray.size)
            } else {
                assertEquals("/v1/agent/summaries/$requestId", sent.path)
                assertEquals(0L, sent.bodySize)
            }
            return result
        }
        assertTrue(host.prepareSummaryRequest(conversation, request))
        val submit = host.nextSummaryRequest(conversation, request, 0)!!
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, submit.action)
        assertTrue(host.receiveSummaryReceipt(conversation, request, 1, original.fingerprint,
            receipt(request, original.fingerprint, "withdrawn", false, false, submit, original)))
        assertFalse(database.conversationSummaryRequestDao().find(request)!!.remoteFinished)
        val context = CloudChatConsent.context(identity)
        chatConsent.set(identity, context, false); chatConsent.set(identity, context, true)
        assertFalse(host.prepareSummaryRequest(conversation, UUID.randomUUID().toString()))
        val query = service().nextSummaryRequest(conversation, request, 2)!!
        assertEquals(ConversationSummaryProtocol.Action.QUERY, query.action)
        val late = receipt(request, original.fingerprint, "withdrawn", true, true, query, null)
        assertTrue(service().receiveSummaryReceipt(conversation, request, 3, original.fingerprint, late))
        assertFalse(host.receiveSummaryReceipt(conversation, request, 3, original.fingerprint, late))
        val settled = database.conversationSummaryRequestDao().find(request)!!
        assertTrue(settled.remoteFinished); assertEquals(7L, settled.inputTokens)
        assertNull(ConversationSummaryProtocol.decode(settled.snapshotJson!!).summary)
        val nextRequest = UUID.randomUUID().toString()
        val nextCandidate = host.summaryCandidate(conversation)!!.candidate!!
        assertTrue(host.prepareSummaryRequest(conversation, nextRequest))
        val nextSubmit = host.nextSummaryRequest(conversation, nextRequest, 0)!!
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, nextSubmit.action)
        chatConsent.set(identity, context, false); chatConsent.set(identity, context, true)
        assertTrue(service().receiveSummaryReceipt(conversation, nextRequest, 1, nextCandidate.fingerprint,
            receipt(nextRequest, nextCandidate.fingerprint, "completed", true, true, nextSubmit, nextCandidate)))
        val invalid = database.conversationSummaryRequestDao().find(nextRequest)!!
        assertTrue(invalid.remoteFinished); assertEquals(7L, invalid.inputTokens)
        assertNull(ConversationSummaryProtocol.decode(invalid.snapshotJson!!).summary)
        assertEquals(before, calls.count { it == "submit" })
        assertEquals(listOf("POST", "GET", "POST"), summaryRequests)
    }

    @Test fun 删除会话后宿主仅补记摘要用量且不恢复正文() = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val host = service()
        val conversation = data(host.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) {
            clock++
            assertTrue(confirm(host, prepareIn(host, conversation)) is CommandOutcome.AcceptedWithData)
            idle(host)
        }
        val request = UUID.randomUUID().toString()
        val fingerprint = host.summaryCandidate(conversation)!!.candidate!!.fingerprint
        assertTrue(host.prepareSummaryRequest(conversation, request))
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, host.nextSummaryRequest(conversation, request, 0)!!.action)
        data(host.handle("cloud.deleteConversation", json("""{"conversationId":"$conversation","confirmed":true}""")))
        cleanupIdle(host)
        val before = calls.count { it == "submit" }
        val row = database.conversationSummaryRequestDao().find(request)!!
        assertNull(row.conversationId)
        val result = ConversationSummaryProtocol.Result.Completed("不得恢复的正文", ConversationSummaryProtocol.Usage(7, 1))
        assertFalse(host.receiveSummaryRequest(conversation, request, row.revision, fingerprint, result))
        assertFalse(host.receiveSummaryRequest(null, request, row.revision, "b".repeat(64), result))
        assertTrue(service().receiveSummaryRequest(null, request, row.revision, fingerprint, result))
        assertFalse(host.receiveSummaryRequest(null, request, row.revision, fingerprint, result))
        val settled = database.conversationSummaryRequestDao().find(request)!!
        assertNull(settled.snapshotJson); assertNull(settled.sourceIdsJson); assertNull(settled.referencesJson)
        assertEquals(7L, settled.inputTokens); assertTrue(settled.remoteFinished)
        assertNull(database.conversationDao().find(identity, conversation))
        assertNull(host.nextSummaryRequest(conversation, request, settled.revision))
        assertEquals(before, calls.count { it == "submit" })
    }

    @Test fun 请求账本摘要读取复核授权及记忆变化且清除后不复活() = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val host = service()
        val conversation = data(host.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) { index ->
            clock++
            val refs = if (index == 0) "[{\"id\":\"$memoryId\",\"version\":1}]" else "[]"
            val token = data(host.handle("cloud.prepare", json("""{"prompt":"合成问题$index","memoryRefs":$refs,"conversationId":"$conversation"}""")))
                .getValue("token").jsonPrimitive.content
            assertTrue(confirm(host, token) is CommandOutcome.AcceptedWithData)
            idle(host)
        }
        val before = calls.count { it == "submit" }
        suspend fun complete(): String {
            val request = UUID.randomUUID().toString()
            val candidate = host.summaryCandidate(conversation)!!.candidate!!
            assertTrue(host.prepareSummaryRequest(conversation, request))
            assertEquals(ConversationSummaryProtocol.Action.SUBMIT, host.nextSummaryRequest(conversation, request, 0)!!.action)
            assertTrue(host.receiveSummaryRequest(conversation, request, 1, candidate.fingerprint,
                ConversationSummaryProtocol.Result.Completed("账本合成摘要", ConversationSummaryProtocol.Usage(7, 2))))
            assertEquals("账本合成摘要", service().readRequestSummary(conversation))
            return request
        }
        suspend fun cleared(request: String) {
            assertNull(service().readRequestSummary(conversation))
            val row = database.conversationSummaryRequestDao().find(request)!!
            assertNull(ConversationSummaryProtocol.decode(row.snapshotJson!!).summary)
            assertEquals(7L, row.inputTokens); assertEquals(2L, row.outputTokens)
            assertTrue(row.remoteFinished)
        }
        val first = complete()
        var identityReads = 0
        val racingHost = CloudAgentService(database, {
            identityReads++
            if (identityReads == 4) {
                val token = CloudChatConsent.context(identity)
                chatConsent.set(identity, token, false)
                chatConsent.set(identity, token, true)
            }
            activeIdentity
        }, { api }, scope, now = { clock }, chatConsent = chatConsent, memoryConsent = memoryConsent)
        assertNull(racingHost.readRequestSummary(conversation))
        assertEquals(4, identityReads)
        cleared(first)
        val afterRace = complete()
        val context = CloudChatConsent.context(identity)
        chatConsent.set(identity, context, false)
        cleared(afterRace)
        chatConsent.set(identity, context, true)
        cleared(afterRace)
        val second = complete()
        memoryConsent.set(identity, memoryConsent.context(identity), true, true)
        cleared(second)
        val third = complete()
        database.memoryDao().update("local", memoryId, 1, "合成新版偏好", ++clock)
        cleared(third)
        database.memoryDao().delete("local", memoryId, 2, ++clock)
        cleared(third)
        assertEquals(before, calls.count { it == "submit" })
    }

    @Test fun 摘要账本宿主复核重授权后拒绝旧结果且不增加网络提交() = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val host = service()
        val conversation = data(host.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) {
            clock++
            assertTrue(confirm(host, prepareIn(host, conversation)) is CommandOutcome.AcceptedWithData)
            idle(host)
        }
        val before = calls.count { it == "submit" }
        val request = UUID.randomUUID().toString()
        val original = host.summaryCandidate(conversation)!!.candidate!!
        assertTrue(host.prepareSummaryRequest(conversation, request))
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, host.nextSummaryRequest(conversation, request, 0)!!.action)
        assertEquals(ConversationSummaryProtocol.Action.QUERY, service().nextSummaryRequest(conversation, request, 1)!!.action)
        val context = CloudChatConsent.context(identity)
        chatConsent.set(identity, context, false)
        assertEquals(ConversationSummaryProtocol.Action.QUERY, host.nextSummaryRequest(conversation, request, 2)!!.action)
        chatConsent.set(identity, context, true)
        assertNotEquals(original.fingerprint, host.summaryCandidate(conversation)!!.candidate!!.fingerprint)
        assertFalse(host.prepareSummaryRequest(conversation, UUID.randomUUID().toString()))
        assertTrue(host.receiveSummaryRequest(conversation, request, 3, original.fingerprint,
            ConversationSummaryProtocol.Result.Completed("不得恢复的摘要", ConversationSummaryProtocol.Usage(9, 2))))
        val row = database.conversationSummaryRequestDao().find(request)!!
        val snapshot = ConversationSummaryProtocol.decode(row.snapshotJson!!)
        assertEquals(ConversationSummaryProtocol.State.INVALID, snapshot.state)
        assertNull(snapshot.summary)
        assertEquals(9L, row.inputTokens)
        assertTrue(row.remoteFinished)
        val directRequest = UUID.randomUUID().toString()
        val directFingerprint = host.summaryCandidate(conversation)!!.candidate!!.fingerprint
        assertTrue(host.prepareSummaryRequest(conversation, directRequest))
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, host.nextSummaryRequest(conversation, directRequest, 0)!!.action)
        chatConsent.set(identity, context, false)
        chatConsent.set(identity, context, true)
        // 不经过查询或候选读取，直接由回执入口复核新授权代次。
        assertTrue(service().receiveSummaryRequest(conversation, directRequest, 1, directFingerprint,
            ConversationSummaryProtocol.Result.Completed("直接迟到正文", ConversationSummaryProtocol.Usage(5, 1))))
        val directRow = database.conversationSummaryRequestDao().find(directRequest)!!
        val directSnapshot = ConversationSummaryProtocol.decode(directRow.snapshotJson!!)
        assertEquals(ConversationSummaryProtocol.State.INVALID, directSnapshot.state)
        assertNull(directSnapshot.summary)
        assertEquals(5L, directRow.inputTokens)
        assertTrue(directRow.remoteFinished)
        assertEquals(before, calls.count { it == "submit" })
    }

    @Test fun 本地摘要候选重建稳定且聊天重授权后旧指纹失效() = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        val ids = mutableListOf<String>()
        repeat(5) {
            clock++
            val token = prepareIn(service, conversation)
            ids += token
            assertTrue(confirm(service, token) is CommandOutcome.AcceptedWithData)
            idle(service)
        }
        val original = service.summaryCandidate(conversation)!!.candidate!!
        assertEquals(listOf(ids.first()), original.sourceIds)
        assertEquals(ids.drop(1), service.summaryCandidate(conversation)!!.recentIds)
        assertEquals(original.fingerprint, service().summaryCandidate(conversation)!!.candidate!!.fingerprint)
        val summaryAttempt = UUID.randomUUID().toString()
        assertTrue(service.beginSummary(conversation, summaryAttempt, clock + 1000))
        assertFalse(service.beginSummary(conversation, UUID.randomUUID().toString(), clock + 1000))
        assertTrue(service.acceptSummary(conversation, summaryAttempt, "合成摘要"))
        assertFalse(service.acceptSummary(conversation, summaryAttempt, "重复摘要"))
        assertEquals("合成摘要", service().readSummary(conversation))
        val dependencyRow = database.memoryContextDependenciesDao().find(ids.first())!!
        for (invalid in listOf(dependencyRow.copy(referencesJson = "{}"),
            dependencyRow.copy(referencesJson = "[]", exhausted = true))) {
            database.memoryContextDependenciesDao().save(invalid)
            val excluded = service.summaryCandidate(conversation)!!
            assertNull(excluded.candidate)
            assertEquals(ids.drop(1), excluded.recentIds)
            assertNull(service.readSummary(conversation))
            assertNull(database.conversationSummaryDao().find(conversation)!!.summary)
        }
        database.memoryContextDependenciesDao().save(dependencyRow)
        assertEquals(original.fingerprint, service.summaryCandidate(conversation)!!.candidate!!.fingerprint)
        assertNull(service().readSummary(conversation))
        val inheritedRow = database.memoryContextDependenciesDao().find(ids[1])!!
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("DELETE FROM agent_memory_context_dependencies WHERE requestId = ?", arrayOf(ids[1]))
        }
        val missing = service.summaryCandidate(conversation)!!
        assertNull(missing.candidate)
        assertFalse(ids[1] in missing.recentIds)
        assertEquals(listOf(ids.first()) + ids.drop(2), missing.recentIds)
        database.memoryContextDependenciesDao().save(inheritedRow)
        val context = CloudChatConsent.context(identity)
        chatConsent.set(identity, context, false)
        assertNull(service.summaryCandidate(conversation))
        assertNull(service.readSummary(conversation))
        chatConsent.set(identity, context, true)
        val renewed = service.summaryCandidate(conversation)!!.candidate!!
        assertNotEquals(original.fingerprint, renewed.fingerprint)
        assertNull(service().readSummary(conversation))
        val attemptId = UUID.randomUUID().toString()
        assertNull(ConversationSummaryPolicy.Attempt(original, attemptId, 100).accept(renewed, attemptId, "旧摘要", 1))
        var previous = renewed
        for ((enabled, facts) in listOf(true to false, false to false, true to false, true to true, true to false)) {
            val persistedAttempt = UUID.randomUUID().toString()
            assertTrue(service.beginSummary(conversation, persistedAttempt, clock + 1000))
            assertTrue(service.acceptSummary(conversation, persistedAttempt, "合成授权摘要"))
            assertEquals("合成授权摘要", service().readSummary(conversation))
            memoryConsent.set(identity, memoryConsent.context(identity), enabled, facts)
            val changed = service.summaryCandidate(conversation)!!.candidate!!
            assertNotEquals(previous.fingerprint, changed.fingerprint)
            assertNull(ConversationSummaryPolicy.Attempt(previous, attemptId, 100).accept(changed, attemptId, "旧授权摘要", 1))
            assertEquals(changed.fingerprint, service().summaryCandidate(conversation)!!.candidate!!.fingerprint)
            assertNull(service().readSummary(conversation))
            assertNull(database.conversationSummaryDao().find(conversation)!!.summary)
            assertFalse(service.acceptSummary(conversation, persistedAttempt, "迟到旧授权摘要"))
            previous = changed
        }
        assertEquals(5, calls.count { it == "submit" })
        val withdrawnAttempt = UUID.randomUUID().toString()
        assertTrue(service.beginSummary(conversation, withdrawnAttempt, clock + 1000))
        assertTrue(service.acceptSummary(conversation, withdrawnAttempt, "合成撤回前摘要"))
        assertEquals("合成撤回前摘要", service().readSummary(conversation))
        response = { action -> view(if (action == "withdraw") "withdrawn" else "acknowledged").copy(id = ids.first()) }
        service.handle("cloud.withdraw", json("""{"requestId":"${ids.first()}"}"""))
        idle(service)
        val withdrawn = service.summaryCandidate(conversation)!!
        assertNull(withdrawn.candidate)
        assertEquals(ids.drop(1), withdrawn.recentIds)
        assertNull(service().readSummary(conversation))
        assertNull(database.conversationSummaryDao().find(conversation)!!.summary)
        assertFalse(service.acceptSummary(conversation, withdrawnAttempt, "迟到撤回摘要"))
        assertEquals(5, calls.count { it == "submit" })
        data(service.handle("cloud.newConversation", json("{}")))
        assertNull(service.summaryCandidate(conversation))
    }

    @Test fun 摘要候选归档不可用且更新继承记忆后清除旧摘要() = verifySummaryMemoryInvalidation(true)

    @Test fun 直接删除继承记忆后清除旧摘要且迟到结果不复活() = verifySummaryMemoryInvalidation(false)

    private fun verifySummaryMemoryInvalidation(updateFirst: Boolean) = runBlocking {
        response = { action -> view(if (action == "acknowledge") "acknowledged" else "available").copy(id = submittedIntent!!.clientRequestId) }
        val service = service()
        val conversation = data(service.handle("cloud.newConversation", json("{}"))).getValue("conversationId").jsonPrimitive.content
        repeat(5) { index ->
            clock++
            val refs = if (index == 0) "[{\"id\":\"$memoryId\",\"version\":1}]" else "[]"
            val token = data(service.handle("cloud.prepare", json("""{"prompt":"合成问题$index","memoryRefs":$refs,"conversationId":"$conversation"}""")))
                .getValue("token").jsonPrimitive.content
            assertTrue(confirm(service, token) is CommandOutcome.AcceptedWithData)
            idle(service)
        }
        val original = service.summaryCandidate(conversation)!!.candidate!!
        assertEquals(setOf(com.healthyagent.running.agent.memory.MemoryRef(memoryId, 1)), original.dependencies)
        val target = json("""{"conversationId":"$conversation"}""")
        data(service.handle("cloud.archiveConversation", target))
        data(service.handle("cloud.selectConversation", target))
        assertNull(service.summaryCandidate(conversation))
        data(service.handle("cloud.restoreConversation", target))
        assertEquals(original.fingerprint, service.summaryCandidate(conversation)!!.candidate!!.fingerprint)
        val persistedAttempt = UUID.randomUUID().toString()
        assertTrue(service.beginSummary(conversation, persistedAttempt, clock + 1000))
        assertTrue(service.acceptSummary(conversation, persistedAttempt, "合成记忆摘要"))
        assertEquals("合成记忆摘要", service().readSummary(conversation))
        if (updateFirst) database.memoryDao().update("local", memoryId, 1, "合成新版偏好", ++clock)
        else database.memoryDao().delete("local", memoryId, 1, ++clock)
        val updated = service.summaryCandidate(conversation)!!
        assertNull(updated.candidate)
        assertTrue(updated.recentIds.isEmpty())
        assertNull(service().readSummary(conversation))
        assertNull(database.conversationSummaryDao().find(conversation)!!.summary)
        assertFalse(service.acceptSummary(conversation, persistedAttempt, "迟到记忆摘要"))
        if (updateFirst) database.memoryDao().delete("local", memoryId, 2, ++clock)
        val after = service().summaryCandidate(conversation)!!
        assertNull(after.candidate)
        assertTrue(after.recentIds.isEmpty())
        assertEquals(5, data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.size)
        assertEquals(5, calls.count { it == "submit" })
    }

    @Test fun 指定单条概要预览不发网确认后固定快照并兼容记忆() = runBlocking {
        val summary = json("""{"runId":"run-1","distanceMeters":2000,"durationSeconds":720,"averagePaceSecPerKm":360,"averageHeartRate":null}""")
        runSummary = summary
        val service = service()
        val preview = data(service.handle("cloud.prepare", json("""{"prompt":"复盘","memoryRefs":[{"id":"$memoryId","version":1}],"runId":"run-1"}""")))
        assertEquals(summary, preview["runSummary"])
        assertEquals(listOf("run-1"), loadedRuns)
        assertTrue(calls.isEmpty())
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
        val token = preview.getValue("token").jsonPrimitive.content
        assertTrue(confirm(service, token) is CommandOutcome.AcceptedWithData)
        idle(service)
        assertEquals(listOf("run-1", "run-1"), loadedRuns)
        assertEquals(summary, submittedIntent!!.submission(selected).getValue("summaries").jsonArray.single())
        runSummary = null
        val restored = data(service().handle("cloud.list", json("{}"))).getValue("items").jsonArray.single().jsonObject
        assertEquals(summary, restored["runSummary"])
        assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        service.handle("cloud.withdraw", json("""{"requestId":"$token"}""")); idle(service)
        val hidden = data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.single().jsonObject
        assertFalse(hidden.containsKey("runSummary"))
        assertEquals(1, calls.count { it == "submit" })
    }

    @Test fun 确认前概要改变或删除拒绝发送且缺失记录不降级成纯问题() = runBlocking {
        val summary = json("""{"runId":"run-1","distanceMeters":2000,"durationSeconds":720,"averagePaceSecPerKm":null,"averageHeartRate":null}""")
        val service = service()
        val request = json("""{"prompt":"复盘","memoryRefs":[],"runId":"run-1"}""")
        assertTrue(service.handle("cloud.prepare", request) is CommandOutcome.Rejected)
        for (changed in listOf(null, JsonObject(summary + ("distanceMeters" to JsonPrimitive(2100))))) {
            runSummary = summary
            val token = data(service.handle("cloud.prepare", request)).getValue("token").jsonPrimitive.content
            runSummary = changed
            assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        }
        runSummary = JsonObject(summary + ("runId" to JsonPrimitive("other")))
        assertTrue(service.handle("cloud.prepare", request) is CommandOutcome.Rejected)
        assertTrue(calls.isEmpty())
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
    }

    @Test fun 纯文字不读取跑步且带概要断网恢复不会重发() = runBlocking {
        val service = service()
        prepare(service, false)
        assertTrue(loadedRuns.isEmpty())
        runSummary = json("""{"runId":"run-1","distanceMeters":2000,"durationSeconds":720,"averagePaceSecPerKm":null,"averageHeartRate":null}""")
        val token = data(service.handle("cloud.prepare", json("""{"prompt":"复盘","memoryRefs":[],"runId":"run-1"}"""))).getValue("token").jsonPrimitive.content
        response = { throw CloudTaskFailure("network_unavailable") }
        confirm(service, token); idle(service)
        val restored = service()
        restored.handle("cloud.recover", json("""{"requestId":"$token"}""")); idle(restored)
        assertEquals(listOf("submit", "find"), calls.toList())
        assertEquals(2, loadedRuns.size)
    }

    @Test fun 准备与取消不发网确认只发一次并落盘回答() = runBlocking {
        val service = service()
        val abandoned = prepare(service)
        assertTrue(calls.isEmpty())
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
        service.handle("cloud.discard", json("""{"token":"$abandoned"}"""))
        assertTrue(confirm(service, abandoned) is CommandOutcome.Rejected)
        val token = prepare(service)
        assertTrue(confirm(service, token) is CommandOutcome.AcceptedWithData)
        assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        idle(service)
        assertEquals(listOf("submit", "acknowledge"), calls.toList())
        assertEquals("喜欢晚跑", selected!!.getValue("items").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
        val restored = data(service().handle("cloud.list", json("{}"))).getValue("items").jsonArray.single().jsonObject
        assertTrue(restored.getValue("answer").jsonPrimitive.content.contains("晚跑"))
        assertEquals(1, restored.getValue("memoryRefs").jsonArray.size)
    }

    @Test fun 知识选择单次确认并与来源持久保存且撤回同时隐藏() = runBlocking {
        val knowledge = json("""{"version":"curated_knowledge_v1","library":"firefly","allowSpoilers":true}""")
        val sources = Json.parseToJsonElement("""[{"citationId":"official_v1_1","title":"官方资料","url":"https://hsr.hoyoverse.com/zh-tw/character","publishedAt":null,"checkedAtMillis":1000}]""")
        response = { action -> if (action == "acknowledge") view("acknowledged") else if (action == "withdraw") view("withdrawn")
            else view().let { it.copy(result = JsonObject(it.result!! + ("sources" to sources))) } }
        val service = service()
        val request = json("""{"prompt":"流萤是谁","memoryRefs":[{"id":"$memoryId","version":1}],"knowledge":$knowledge}""")
        val preview = data(service.handle("cloud.prepare", request))
        assertEquals(knowledge, preview["knowledge"])
        assertTrue(calls.isEmpty())
        val token = preview.getValue("token").jsonPrimitive.content
        confirm(service, token); idle(service)
        assertEquals(knowledge, submittedIntent!!.knowledge())
        assertNotNull(selected)
        val restored = service()
        val row = data(restored.handle("cloud.list", json("{}"))).getValue("items").jsonArray.single().jsonObject
        assertEquals(knowledge, row["knowledge"])
        assertEquals(sources, row["sources"])
        val saved = database.cloudIntentDao().find(identity, token)!!
        assertTrue(runCatching { database.cloudIntentDao().save(saved.copy(submissionAttempted = false, updatedAtMillis = saved.consentedAtMillis,
            knowledgeJson = knowledge.toString().replace("true", "false"))) }.isFailure)
        restored.handle("cloud.withdraw", json("""{"requestId":"$token"}""")); idle(restored)
        val hidden = data(restored.handle("cloud.list", json("{}"))).getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonNull, hidden["answer"])
        assertFalse(hidden.containsKey("sources"))
        assertEquals(1, calls.count { it == "submit" })
    }

    @Test fun 确认前记忆更新删除或授权过期均不发送() = runBlocking {
        val service = service()
        var token = prepare(service)
        database.memoryDao().update("local", memoryId, 1, "喜欢晨跑", 2000)
        assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        token = prepare(service, false)
        clock += 120001
        assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        token = prepare(service, false)
        activeIdentity = identity.copy(deviceId = "changed")
        assertTrue(confirm(service, token) is CommandOutcome.Rejected)
        assertTrue(calls.isEmpty())
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
    }

    @Test fun 占用发送许可时仍重新核对记忆且删除不留正文副本() = runBlocking {
        val intent = CloudTaskIntent.confirmSelected(identity, "问题", listOf(com.healthyagent.running.agent.memory.MemoryRef(memoryId, 1)), 1000)
        database.cloudIntentDao().save(intent)
        database.memoryDao().delete("local", memoryId, 1, 2000)
        assertTrue(runCatching { CloudTaskSync(database.cloudIntentDao(), api).submit(identity, intent.clientRequestId) }.isFailure)
        assertFalse(database.cloudIntentDao().find(identity, intent.clientRequestId)!!.submissionAttempted)
        assertTrue(calls.isEmpty())
        assertFalse(intent.memoryRefsJson!!.contains("喜欢晚跑"))
    }

    @Test fun POST失联后新服务恢复只查询原任务() = runBlocking {
        val service = service()
        response = { throw CloudTaskFailure("network_unavailable") }
        val token = prepare(service, false)
        confirm(service, token); idle(service)
        val restored = service()
        restored.handle("cloud.recover", json("""{"requestId":"$token"}""")); idle(restored)
        assertEquals(listOf("submit", "find"), calls.toList())
    }

    @Test fun ack途中撤回不会漏发且回答立即隐藏() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        response = { action ->
            if (action == "acknowledge") { entered.complete(Unit); release.await(); view("acknowledged") }
            else if (action == "withdraw") view("withdrawn") else view()
        }
        val service = service()
        val token = prepare(service, false)
        confirm(service, token)
        withTimeout(10000) { entered.await() }
        val pending = data(service.handle("cloud.withdraw", json("""{"requestId":"$token"}""")))
        assertEquals(JsonNull, pending.getValue("items").jsonArray.single().jsonObject["answer"])
        release.complete(Unit); idle(service)
        assertTrue(calls.contains("withdraw"))
        val row = data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("withdraw"), row["confirmedControl"])
        assertEquals(JsonNull, row["answer"])
        assertEquals(1, calls.count { it == "submit" })
    }

    @Test fun 同样配置重复保存不换绑且重读不暴露凭据() {
        val root = File(context.noBackupFilesDir, "cloud-config-test-${UUID.randomUUID()}")
        val credentials = CloudDeviceCredentials(File(root, "keys"), "cloud-test-${UUID.randomUUID()}")
        val config = CloudAgentConfiguration(File(root, "identity.json"), credentials)
        val bearer = "lyd_" + "a".repeat(64)
        config.save(identity.origin, identity.ownerId, identity.deviceId, bearer)
        val first = config.read()
        config.save(identity.origin, identity.ownerId, identity.deviceId, bearer)
        assertEquals(first, config.read())
        assertFalse(File(root, "identity.json").readText().contains(bearer))
        File(root, "identity.json").writeText("损坏的合成配置")
        config.save(identity.origin, identity.ownerId, identity.deviceId, bearer)
        assertNotEquals(first, config.read())
        assertEquals(identity.origin, config.read()!!.origin)
    }

    @Test fun 极端JSON转义也不超过Bridge上限且不截断回答() = runBlocking {
        val answer = "\u0000".repeat(4000)
        repeat(10) {
            val intent = CloudTaskIntent.confirm(identity, "\u0000".repeat(2000), true, 1000)
            database.cloudIntentDao().save(intent)
            database.cloudIntentDao().claimSubmission(identity, intent.clientRequestId, 1000)
            database.cloudIntentDao().accept(identity, intent.clientRequestId, view().copy(id = UUID.randomUUID().toString(), result = buildJsonObject {
                put("status", "completed"); put("answer", answer); put("actions", JsonArray(emptyList()))
            }))
        }
        val listing = data(service().handle("cloud.list", json("{}")))
        assertTrue(listing.toString().toByteArray(Charsets.UTF_8).size < 230_000)
        val rows = listing.getValue("items").jsonArray
        assertTrue(rows.size in 1..9)
        rows.forEach { assertEquals(answer, it.jsonObject.getValue("answer").jsonPrimitive.content) }
        assertTrue(calls.isEmpty())
    }
}
