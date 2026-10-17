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
import kotlinx.serialization.json.*
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
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val memoryId = UUID.randomUUID().toString()
    private val calls = CopyOnWriteArrayList<String>()
    private var selected: JsonObject? = null
    private var submittedIntent: CloudTaskIntent? = null
    private var clock = 1000L
    private var activeIdentity: CloudTaskIdentity? = identity
    private var storedConsent: String? = null
    private val chatConsent = CloudChatConsent({ storedConsent }, { storedConsent = it })
    private var response: suspend (String) -> CloudTaskResponse = { view(if (it == "acknowledge") "acknowledged" else if (it == "withdraw") "withdrawn" else "available") }
    private val api = object : CloudTaskApi {
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse { submittedIntent = intent; calls += "submit"; return response("submit") }
        override suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject): CloudTaskResponse { selected = memoryContext; return submit(intent) }
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse { calls += "find"; return response("find") }
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse { calls += "get"; return response("get") }
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse { calls += action; return response(action) }
    }
    private fun view(state: String = "available") = CloudTaskResponse("33333333-3333-4333-8333-333333333333", "completed", null, 1000, 31000, 2000, state,
        if (state == "available") buildJsonObject { put("status", "completed"); put("answer", "记得你喜欢晚跑，我们轻松一点。"); put("actions", JsonArray(emptyList())) } else null)
    private var runSummary: JsonObject? = null
    private val loadedRuns = mutableListOf<String>()
    private fun service() = CloudAgentService(database, { activeIdentity }, { api }, scope,
        loadRunSummary = { id -> loadedRuns += id; runSummary }, now = { clock }, chatConsent = chatConsent)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun data(outcome: CommandOutcome) = (outcome as CommandOutcome.AcceptedWithData).data
    private suspend fun prepare(service: CloudAgentService, withMemory: Boolean = true): String {
        val refs = if (withMemory) "[{\"id\":\"$memoryId\",\"version\":1}]" else "[]"
        return data(service.handle("cloud.prepare", json("""{"prompt":"今晚怎么跑","memoryRefs":$refs}"""))).getValue("token").jsonPrimitive.content
    }
    private suspend fun confirm(service: CloudAgentService, token: String) = service.handle("cloud.confirm", json("""{"token":"$token","approved":true}"""))
    private suspend fun idle(service: CloudAgentService) {
        withTimeout(10000) { while (data(service.handle("cloud.list", json("{}"))).getValue("items").jsonArray.any { it.jsonObject.getValue("busy").jsonPrimitive.boolean }) delay(20) }
    }
    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        database.memoryDao().create("local", memoryId, "喜欢晚跑", clock)
        Unit
    }
    @After fun tearDown() { scope.cancel(); database.close() }

    @Test fun 持续授权直发仅接受当前身份纯文字且单个令牌最多提交一次() = runBlocking {
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
            """{"prompt":"问题","memoryRefs":[],"conversationId":"$conversation","knowledge":{"version":"curated_knowledge_v1","library":"firefly","allowSpoilers":false}}""",
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
