package com.healthyagent.running.agent

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.agent.memory.MemoryToolPolicy
import com.healthyagent.running.coordinator.CommandOutcome
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 本地隔离夹具：正式原生 HTTPS、Room、Worker/DO，只有模型输出使用合成数据。 */
@RunWith(AndroidJUnit4::class)
class MemoryWorkerJointTest {
    @Test fun saveSearchCorrectAndDeleteAcrossConversations() = runBlocking { verify("lifecycle") }
    @Test fun 自然更正附言更新后新会话读取新版() = runBlocking { verify("natural_correction") }
    @Test fun revokedGenerationCannotReviveAfterReenable() = runBlocking { verify("revoke") }
    @Test fun delayedSearchRejectsAfterBaseRevocation() = runBlocking { verify("search_revoke") }
    @Test fun delayedSearchCannotReviveButNewGenerationCanRead() = runBlocking { verify("search_reenable") }
    @Test fun lostAcknowledgementRecoversWithoutSavingAgain() = runBlocking { verify("lose_reply") }
    @Test fun naturalPreferenceWithQuestionKeepsOnlyEvidence() = runBlocking { verify("natural") }
    @Test fun negativePreferenceKeepsNegation() = runBlocking { verify("negative") }
    @Test fun optOutInAnotherClauseRejectsModelSave() = runBlocking { verify("decline") }
    @Test fun manuallyDeletedVersionsRejectLateSaveAndAllowExplicitNewMemory() = runBlocking { verify("manual_delete") }
    @Test fun factsSaveSearchCorrectAndDeleteAcrossConversations() = runBlocking { verify("fact_lifecycle") }
    @Test fun factScopeRevocationRejectsLateSaveAfterReenable() = runBlocking { verify("fact_revoke") }
    @Test fun legacyScopeRejectsFactWithoutNativeDispatch() = runBlocking { verify("fact_legacy") }
    @Test fun factWriteOffKeepsReadAndBaseOffRemovesAllTools() = runBlocking { verify("fact_readonly") }
    @Test fun factReceiptFailureRollsBackAndStopsChangedTargetRetry() = runBlocking { verify("fact_storage") }
    @Test fun factLostAcknowledgementRecoversWithoutSavingAgain() = runBlocking { verify("fact_lose_reply") }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun data(outcome: CommandOutcome): JsonObject {
        assertTrue("正式原生命令必须成功", outcome is CommandOutcome.AcceptedWithData)
        return (outcome as CommandOutcome.AcceptedWithData).data
    }

    private suspend fun verify(name: String) {
        val raw = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("必须由明确的隔离联合夹具启动", raw != null)
        val fixture = Json.parseToJsonElement(String(Base64.decode(raw!!, Base64.DEFAULT), Charsets.UTF_8)).jsonObject
        require(fixture.getValue("fixtureOnly") == JsonPrimitive(true))
        val origin = fixture.text("origin").also { require(it.matches(Regex("https://127\\.0\\.0\\.1:[0-9]{1,5}"))) }
        val current = fixture.getValue("cases").jsonArray.single { it.jsonObject.text("name") == name }.jsonObject
        val identity = CloudTaskIdentity(origin, current.text("ownerId"), current.text("deviceId"), "synthetic-memory-$name")
        val token = current.text("token")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(fixture.text("certificate"), Base64.DEFAULT).inputStream())
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        // 保留生产禁重试和正常主机名校验；不安装系统证书，不接真实用户配置。
        val http = CloudTaskHttpClient.clientBuilder().sslSocketFactory(tls.socketFactory, manager).build()
        val api = CloudTaskHttpClient(origin, CloudCredentialSource { checked ->
            require(checked == identity); CloudDeviceBearer(token)
        }, http)
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var storedConsent: String? = null
        val consent = MemoryToolConsent({ storedConsent }, { storedConsent = it })
        var host = CloudAgentService(database, { identity }, { api }, scope, memoryConsent = consent)
        suspend fun call(command: String, body: JsonObject = buildJsonObject {}): JsonObject {
            val outcome = host.handle(command, body)
            assertTrue("原生命令 $command 必须成功", outcome is CommandOutcome.AcceptedWithData)
            return data(outcome)
        }
        suspend fun enable(value: Boolean, factsAllowed: Boolean? = null) = call("cloud.memoryConsent", buildJsonObject {
            put("identityToken", consent.context(identity)); put("enabled", value)
            factsAllowed?.let { put("factsAllowed", it) }
        })
        suspend fun fixtureCall(path: String): JsonObject = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$origin/__fixture/$path").header("Authorization", "Bearer $token")
                .tag(AtomicBoolean::class.java, AtomicBoolean()).build()).execute().use {
                check(it.code == 200); Json.parseToJsonElement(it.body!!.string()).jsonObject
            }
        }
        suspend fun finished(): JsonObject = withTimeout(40000) {
            while (true) {
                val row = call("cloud.list").getValue("items").jsonArray.single().jsonObject
                if (!row.getValue("busy").jsonPrimitive.boolean) return@withTimeout row
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("没有原任务终态")
        }
        suspend fun send(prompt: String): Pair<JsonElement, String> {
            val conversation = call("cloud.newConversation").getValue("conversationId")
            val preview = call("cloud.prepare", buildJsonObject {
                put("prompt", prompt); put("conversationId", conversation); put("memoryRefs", JsonArray(emptyList()))
            })
            assertTrue("跨会话不能偷偷夹带上个会话", preview.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
            call("cloud.confirm", buildJsonObject { put("token", preview.getValue("token")); put("approved", true) })
            return conversation to database.cloudIntentDao().conversationPage(identity, conversation.jsonPrimitive.content).single().clientRequestId
        }
        suspend fun recoverTwice(requestId: String, status: String = "completed") {
            val before = fixtureCall("stats").filterKeys { it !in setOf("get", "control") }
            var recovered: Map<String, JsonElement>? = null
            repeat(2) {
                call("cloud.recover", buildJsonObject { put("requestId", requestId) })
                assertEquals(status, finished().text("status"))
                val after = fixtureCall("stats")
                assertEquals("恢复不能重发问题或记忆结果", before, after.filterKeys { it !in setOf("get", "control") })
                // 首次恢复可以确认之前未收到的最终答案，此后不能重复确认。
                recovered?.let { assertEquals(it, after.filterKeys { key -> key != "get" }) }
                recovered = after.filterKeys { key -> key != "get" }
            }
        }
        try {
            assertNull(consent.current(identity))
            val fact = name.startsWith("fact_")
            val factsAllowed = fact && name != "fact_legacy"
            val naturalCorrection = name == "natural_correction"
            val correctedText = if (naturalCorrection) "我喜欢把测试计划排在周四" else if (fact) "我的主要编程语言是TypeScript" else "我喜欢晨跑"
            val correctionPrompt = if (naturalCorrection) "$correctedText。请更正之前的周三偏好，不要新增重复记录。" else correctedText
            val recallPrompt = if (naturalCorrection) "我希望把测试计划安排在哪一天？" else if (fact) "我主要用什么编程语言" else "我喜欢什么跑步时间"
            val deletedPrompt = if (naturalCorrection) "现在还记得我的测试计划偏好吗" else if (fact) "现在还记得我的主要编程语言吗" else "现在还有晨跑偏好吗"
            enable(true, if (factsAllowed) true else null)
            val grant = consent.current(identity)!!
            assertEquals(factsAllowed, grant.factsAllowed)
            if (name in setOf("search_revoke", "search_reenable")) {
                val memories = database.memoryDao()
                val original = memories.create("local", UUID.randomUUID().toString(), current.text("evidence"), System.currentTimeMillis())
                val (oldConversation, oldRequest) = send(current.text("prompt"))
                val binding = database.memoryToolBindingDao().find(oldRequest)!!
                assertEquals(grant.generation, binding.grantGeneration)
                assertFalse(binding.factsAllowed)
                withTimeout(15000) {
                    while (fixtureCall("stats")["held"] != JsonPrimitive(true)) delay(25)
                }
                // 真正的 HTTPS 工具响应尚未交给宿主；撤权不能被网络等待持锁阻塞。
                withTimeout(3000) { enable(false) }
                assertNull(consent.current(identity))
                if (name == "search_reenable") {
                    enable(true)
                    assertNotEquals(grant.generation, consent.current(identity)!!.generation)
                    assertFalse(consent.current(identity)!!.factsAllowed)
                }
                fixtureCall("release")
                val rejected = finished()
                assertEquals("completed", rejected.text("status"))
                assertEquals(JsonNull, rejected.getValue("error"))
                assertFalse(rejected.containsKey("memorySaved"))
                assertTrue(database.memoryContextDependenciesDao().find(oldRequest)?.state()?.refs.orEmpty().isEmpty())
                recoverTwice(oldRequest)
                assertEquals(grant.generation, database.memoryToolBindingDao().find(oldRequest)!!.grantGeneration)
                if (name == "search_reenable") {
                    val renewed = consent.current(identity)!!
                    host = CloudAgentService(database, { identity }, { api }, scope, memoryConsent = consent)
                    val (newConversation, newRequest) = send("我的主要编程语言是Kotlin。我的专业是什么？")
                    assertNotEquals(oldConversation, newConversation)
                    assertNotEquals(oldRequest, newRequest)
                    assertEquals(renewed.generation, database.memoryToolBindingDao().find(newRequest)!!.grantGeneration)
                    assertFalse(database.memoryToolBindingDao().find(newRequest)!!.factsAllowed)
                    val found = finished()
                    assertEquals("completed", found.text("status"))
                    assertEquals(JsonNull, found.getValue("error"))
                    assertFalse(found.containsKey("memorySaved"))
                    recoverTwice(newRequest)
                    assertEquals(setOf(original.id to original.version),
                        database.memoryContextDependenciesDao().find(newRequest)!!.state().refs.map { it.id to it.version }.toSet())
                    enable(false)
                }
                assertNull(consent.current(identity))
                assertEquals(listOf(original), memories.page("local", 0, 20).items)
                assertEquals(1, memories.revisionCount("local", original.id))
                assertTrue(memories.search("local", "Kotlin").isEmpty())
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
                    assertTrue(it.moveToFirst()); assertEquals("搜索或迟到拒绝不能产生写入回执", 0, it.getInt(0))
                }
                return
            }
            if (name == "fact_storage") {
                // 真实 SQLite 在记忆正文及历史已写、保存回执未插入时失败，整笔事务必须回滚。
                database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_fail_fact_receipt
                    BEFORE INSERT ON agent_memory_tool_receipts BEGIN SELECT RAISE(ABORT, '合成事实回执失败'); END""")
            }
            if (name == "manual_delete") {
                val memories = database.memoryDao()
                val management = MemoryService(memories)
                val id = UUID.randomUUID().toString()
                data(management.handle("memory.create", buildJsonObject {
                    put("memoryId", id); put("content", current.text("evidence"))
                }))
                data(management.handle("memory.update", buildJsonObject {
                    put("memoryId", id); put("version", 1); put("content", "我喜欢晨跑")
                }))
                assertEquals(2, memories.revisionCount("local", id))
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
                    assertTrue(it.moveToFirst()); assertEquals("手工版本不能借用 Agent 回执保护", 0, it.getInt(0))
                }
                val (lateConversation, lateRequest) = send(current.text("prompt"))
                withTimeout(15000) {
                    while (fixtureCall("stats")["held"] != JsonPrimitive(true)) delay(25)
                }
                data(management.handle("memory.delete", buildJsonObject {
                    put("memoryId", id); put("version", 2); put("confirmed", true)
                }))
                val deletedAt = checkNotNull(memories.find("local", id)!!.deletedAtMillis)
                val lateIntent = database.cloudIntentDao().conversationPage(identity, lateConversation.jsonPrimitive.content).single()
                assertTrue("迟到请求必须在删除之前获得原生确认", lateIntent.consentedAtMillis <= deletedAt)
                for (text in listOf(current.text("evidence"), "我喜欢晨跑")) {
                    assertEquals(deletedAt, memories.deletionTime("local", MemoryToolPolicy.hash(text)))
                }
                fixtureCall("release")
                val late = finished()
                assertEquals("completed", late.text("status"))
                assertEquals(JsonNull, late.getValue("error"))
                assertFalse(late.containsKey("memorySaved"))
                recoverTwice(lateRequest)
                assertEquals(0, memories.count("local"))
                assertTrue(memories.document("local", System.currentTimeMillis()).entries.isEmpty())
                val (ordinaryConversation, ordinaryRequest) = send("我喜欢晨跑")
                assertNotEquals(lateConversation, ordinaryConversation)
                val ordinary = finished()
                assertEquals("completed", ordinary.text("status"))
                assertEquals(JsonNull, ordinary.getValue("error"))
                assertFalse(ordinary.containsKey("memorySaved"))
                recoverTwice(ordinaryRequest)
                assertEquals(0, memories.count("local"))
                // 严格越过删除时间，不靠模型自报时间或放宽生产边界。
                withTimeout(1000) { while (System.currentTimeMillis() <= deletedAt) delay(1) }
                val (explicitConversation, explicitRequest) = send("请记住：我喜欢晨跑")
                assertNotEquals(ordinaryConversation, explicitConversation)
                val explicit = finished()
                assertEquals("completed", explicit.text("status"))
                assertEquals(JsonNull, explicit.getValue("error"))
                assertEquals(JsonPrimitive(true), explicit["memorySaved"])
                val explicitIntent = database.cloudIntentDao().conversationPage(identity, explicitConversation.jsonPrimitive.content).single()
                assertTrue("重记必须来自删除后新确认的请求", explicitIntent.consentedAtMillis > deletedAt)
                recoverTwice(explicitRequest)
                val remembered = memories.page("local", 0, 20).items.single()
                assertNotEquals(id, remembered.id)
                assertEquals("我喜欢晨跑", remembered.content)
                assertEquals(1, remembered.version)
                val (searchedConversation, searchedRequest) = send("现在还有晨跑偏好吗")
                assertNotEquals(explicitConversation, searchedConversation)
                assertEquals("completed", finished().text("status"))
                recoverTwice(searchedRequest)
                assertEquals(remembered, memories.page("local", 0, 20).items.single())
                assertNull(memories.find("local", id)!!.content)
                assertNull(memories.history("local", id, 0, 20))
                assertEquals(0, memories.revisionCount("local", id))
                return
            }
            val (savedConversation, savedRequest) = send(current.text("prompt"))
            assertEquals(factsAllowed, database.memoryToolBindingDao().find(savedRequest)!!.factsAllowed)
            if (name in setOf("revoke", "fact_revoke")) {
                withTimeout(15000) {
                    while (fixtureCall("stats")["held"] != JsonPrimitive(true)) delay(25)
                }
                if (name == "fact_revoke") {
                    enable(true, false)
                    assertNotNull("撤事实不关闭基础记忆", consent.current(identity))
                    assertFalse(consent.current(identity)!!.factsAllowed)
                    enable(true, true)
                } else { enable(false); enable(true) }
                assertNotEquals(grant.generation, consent.current(identity)!!.generation)
                fixtureCall("release")
            }
            val initial = finished()
            if (name == "fact_storage") {
                assertEquals("completed", initial.text("status"))
                assertEquals(JsonNull, initial.getValue("error"))
                assertFalse(initial.containsKey("memorySaved"))
                recoverTwice(savedRequest)
                for (table in listOf("agent_memories", "agent_memory_revisions", "agent_memory_tool_receipts")) {
                    database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals("失败回滚不能残留 $table", 0, it.getInt(0))
                    }
                }
                assertTrue(database.memoryDao().documentRows("local").isEmpty())
                return
            }
            if (name == "fact_legacy") {
                assertEquals("blocked", initial.text("status"))
                assertEquals("scope_denied", initial.text("reason"))
                assertEquals(JsonNull, initial.getValue("error"))
                assertFalse(initial.containsKey("memorySaved"))
                recoverTwice(savedRequest, "blocked")
                assertEquals(0, database.memoryDao().count("local"))
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
                    assertTrue(it.moveToFirst()); assertEquals("越权事实不能下发原生执行", 0, it.getInt(0))
                }
                return
            }
            if (name in setOf("lose_reply", "fact_lose_reply")) {
                assertNotEquals("回执确实丢失，不能冒充已正常结束", JsonNull, initial.getValue("error"))
            } else {
                assertEquals("completed", initial.text("status"))
                assertEquals(JsonNull, initial.getValue("error"))
            }
            if (name in setOf("revoke", "fact_revoke", "decline")) {
                assertEquals(0, database.memoryDao().count("local"))
                assertFalse(initial.containsKey("memorySaved"))
                recoverTwice(savedRequest)
                assertEquals(0, database.memoryDao().count("local"))
            } else {
                val first = database.memoryDao().page("local", 0, 20).items.single()
                assertEquals(current.text("evidence"), first.content)
                assertEquals(1, first.version)
                assertEquals(if (fact) "fact" else "preference", database.memoryDao().documentRows("local").single().category)
                assertEquals(JsonPrimitive(true), initial["memorySaved"])
                recoverTwice(savedRequest)
                assertEquals(first, database.memoryDao().page("local", 0, 20).items.single())
                if (name == "fact_readonly") {
                    val reduced = enable(true, false)
                    assertEquals(JsonPrimitive(true), reduced["enabled"])
                    assertEquals(JsonPrimitive(false), reduced["factsAllowed"])
                    assertNotEquals(grant.generation, consent.current(identity)!!.generation)
                    val (readConversation, readRequest) = send(recallPrompt)
                    assertNotEquals(savedConversation, readConversation)
                    assertFalse(database.memoryToolBindingDao().find(readRequest)!!.factsAllowed)
                    val read = finished()
                    assertEquals("completed", read.text("status"))
                    assertEquals(JsonNull, read.getValue("error"))
                    assertFalse(read.containsKey("memorySaved"))
                    recoverTwice(readRequest)
                    val (_, deniedRequest) = send(correctedText)
                    val denied = finished()
                    assertEquals("blocked", denied.text("status"))
                    assertEquals("scope_denied", denied.text("reason"))
                    assertFalse(denied.containsKey("memorySaved"))
                    recoverTwice(deniedRequest, "blocked")
                    enable(false)
                    assertNull(consent.current(identity))
                    val (_, disabledRequest) = send(deletedPrompt)
                    assertNull(database.memoryToolBindingDao().find(disabledRequest))
                    val disabled = finished()
                    assertEquals("blocked", disabled.text("status"))
                    assertEquals("scope_denied", disabled.text("reason"))
                    assertFalse(disabled.containsKey("memorySaved"))
                    recoverTwice(disabledRequest, "blocked")
                    assertEquals(first, database.memoryDao().page("local", 0, 20).items.single())
                    return
                }
                if (name in setOf("natural", "negative")) {
                    val (nextConversation, nextRequest) = send("我的偏好是什么")
                    assertNotEquals(savedConversation, nextConversation)
                    assertEquals("completed", finished().text("status"))
                    recoverTwice(nextRequest)
                    assertEquals(first, database.memoryDao().page("local", 0, 20).items.single())
                }
                if (name in setOf("lifecycle", "fact_lifecycle", "natural_correction")) {
                    val (searchedConversation, searchedRequest) = send(recallPrompt)
                    assertNotEquals(savedConversation, searchedConversation)
                    assertEquals("completed", finished().text("status"))
                    assertEquals(1, database.memoryDao().count("local"))
                    recoverTwice(searchedRequest)
                    val (correctedConversation, correctedRequest) = send(correctionPrompt)
                    assertNotEquals(searchedConversation, correctedConversation)
                    assertEquals("completed", finished().text("status"))
                    val corrected = database.memoryDao().page("local", 0, 20).items.single()
                    assertEquals(first.id, corrected.id)
                    assertEquals(2, corrected.version)
                    assertEquals(correctedText, corrected.content)
                    val entry = database.memoryDao().documentRows("local").single()
                    assertEquals(if (fact) "fact" else "preference", entry.category)
                    assertEquals(correctedRequest, entry.sourceTurnId)
                    recoverTwice(correctedRequest)
                    val (recalledConversation, recalledRequest) = send(recallPrompt)
                    assertNotEquals(correctedConversation, recalledConversation)
                    val recalled = finished()
                    assertEquals("completed", recalled.text("status"))
                    assertFalse("读取新版不应再次保存记忆", recalled.containsKey("memorySaved"))
                    recoverTwice(recalledRequest)
                    assertEquals(corrected, database.memoryDao().page("local", 0, 20).items.single())
                    assertEquals(2, database.memoryDao().revisionCount("local", corrected.id))
                    call("cloud.selectConversation", buildJsonObject { put("conversationId", savedConversation) })
                    val oldPreview = call("cloud.prepare", buildJsonObject {
                        put("prompt", "继续聊聊"); put("conversationId", savedConversation); put("memoryRefs", JsonArray(emptyList()))
                    })
                    assertTrue("修改后旧回答不能继续作为当前记忆上下文", oldPreview.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
                    data(MemoryService(database.memoryDao()).handle("memory.delete", buildJsonObject {
                        put("memoryId", corrected.id); put("version", corrected.version); put("confirmed", true)
                    }))
                    assertEquals(0, database.memoryDao().count("local"))
                    val (_, deletedRequest) = send(deletedPrompt)
                    assertEquals("completed", finished().text("status"))
                    recoverTwice(deletedRequest)
                    assertEquals(0, database.memoryDao().count("local"))
                    assertTrue(database.memoryDao().documentRows("local").isEmpty())
                }
            }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}
