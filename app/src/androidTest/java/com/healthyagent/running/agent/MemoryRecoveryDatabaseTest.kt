package com.healthyagent.running.agent

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.coordinator.CommandOutcome
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 仅专用断网模拟器：合成失联、真实文件 Room 重开与正式宿主恢复，不建立网络连接。 */
@RunWith(AndroidJUnit4::class)
class MemoryRecoveryDatabaseTest {
    private val identity = CloudTaskIdentity("https://synthetic.invalid", "synthetic-owner", "synthetic-device", "synthetic-credential")
    @Test fun dnsFailureReopenAndRegrantStillOnlyQueriesOriginalId() = runBlocking {
        verify(renew = true, missing = true)
    }

    @Test fun lostReceiptReopenAndRevokeRejectsLateSearch() = runBlocking {
        verify(renew = false, missing = false)
    }

    @Test fun lostReceiptReopenAndRegrantRejectsOldSearchButAllowsNewRead() = runBlocking {
        verify(renew = true, missing = false)
    }

    @Test fun truncatedConsentFileRejectsLateSearchWithoutRestoringGrant() = runBlocking {
        verify(renew = false, missing = false, damagedConsent = "{\"version\":")
    }

    @Test fun oversizedConsentFileRejectsLateSearchWithoutRestoringGrant() = runBlocking {
        verify(renew = false, missing = false, damagedConsent = "x".repeat(4097))
    }

    private suspend fun verify(renew: Boolean, missing: Boolean, damagedConsent: String? = null) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "禁止在真实手机运行" }
        check(InstrumentationRegistry.getArguments().getString("isolatedProbe") == "true")
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.noBackupFilesDir, "memory-recovery-${UUID.randomUUID()}.db")
        val consentFile = File(context.noBackupFilesDir, "memory-recovery-${UUID.randomUUID()}-consent.json")
        check(!file.exists())
        check(!consentFile.exists())
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        fun loadConsent() = MemoryToolConsent.create(consentFile)
        var consent = loadConsent()
        var database = open()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val intents = ConcurrentHashMap<String, CloudTaskIntent>()
        val generations = ConcurrentHashMap<String, String>()
        val cloudIds = ConcurrentHashMap<String, String>()
        val observations = ConcurrentHashMap<String, JsonObject>()
        var original: String? = null
        fun response(requestId: String, state: String = "none"): CloudTaskResponse {
            val complete = state != "none"
            return CloudTaskResponse(cloudIds.getValue(requestId), if (complete) "completed" else "running", null,
                1000, 31000, if (complete) 2000 else 1000, state,
                if (state == "available") buildJsonObject {
                    put("status", "completed"); put("answer", "合成恢复回答"); put("actions", JsonArray(emptyList()))
                } else null)
        }
        fun requestFor(taskId: String) = cloudIds.entries.single { it.value == taskId }.key
        val api = object : CloudTaskApi {
            override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse = error("不得降级普通提交")
            override suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean): CloudTaskResponse {
                calls += "submit:${intent.clientRequestId}"
                assertNull(context); assertFalse(factsAllowed)
                assertNull("同一请求不得重复提交", intents.putIfAbsent(intent.clientRequestId, intent))
                generations[intent.clientRequestId] = generation
                cloudIds[intent.clientRequestId] = UUID.randomUUID().toString()
                if (original == null) {
                    original = intent.clientRequestId
                    throw CloudTaskFailure(if (missing) "dns_unavailable" else "network_unavailable")
                }
                return response(intent.clientRequestId)
            }
            override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse {
                assertEquals(this@MemoryRecoveryDatabaseTest.identity, identity)
                calls += "find:$requestId"
                assertEquals(original, requestId)
                if (missing) throw CloudTaskFailure("not_found")
                return response(requestId)
            }
            override suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool {
                val id = requestFor(taskId)
                calls += "memory:$id"
                return CloudMemoryTool(id, taskId, generations.getValue(id), "search_1", "memory.search",
                    buildJsonObject { put("query", "专业") })
            }
            override suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject) {
                calls += "reply:${tool.requestId}"
                assertNull(observations.putIfAbsent(tool.requestId, observation))
            }
            override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse {
                val id = requestFor(taskId)
                calls += "get:$id"
                assertNotNull(observations[id])
                return response(id, "available")
            }
            override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse {
                assertEquals("acknowledge", action)
                val id = requestFor(taskId)
                calls += "ack:$id"
                return response(id, "acknowledged")
            }
        }
        fun host() = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, memoryConsent = consent)
        var service = host()
        suspend fun call(name: String, body: JsonObject = buildJsonObject {}): JsonObject {
            val outcome = service.handle(name, body)
            assertTrue("原生操作未接纳：$name", outcome is CommandOutcome.AcceptedWithData)
            return (outcome as CommandOutcome.AcceptedWithData).data
        }
        suspend fun enable(value: Boolean) {
            call("cloud.memoryConsent", buildJsonObject {
                put("identityToken", consent.context(identity)); put("enabled", value); put("factsAllowed", false)
            })
        }
        suspend fun send(prompt: String): String {
            val conversation = call("cloud.newConversation").getValue("conversationId")
            val preview = call("cloud.prepare", buildJsonObject {
                put("prompt", prompt); put("conversationId", conversation); put("memoryRefs", JsonArray(emptyList()))
            })
            assertTrue(preview.getValue("conversation").jsonObject.getValue("messages").jsonArray.isEmpty())
            val id = preview.getValue("token").jsonPrimitive.content
            call("cloud.confirm", buildJsonObject { put("token", id); put("approved", true) })
            return id
        }
        suspend fun idle(id: String): JsonObject = withTimeout(10000) {
            while (true) {
                val row = call("cloud.list").getValue("items").jsonArray.map { it.jsonObject }
                    .singleOrNull { it["requestId"] == JsonPrimitive(id) }
                if (row != null && row["busy"] == JsonPrimitive(false)) return@withTimeout row
                delay(10)
            }
            error("不可达")
        }
        suspend fun shutdown() { scope.cancel(); scope.coroutineContext[Job]!!.join(); database.close() }
        try {
            val memory = database.memoryDao().create("local", UUID.randomUUID().toString(), "我的专业是流萤记忆验收工程", 500)
            enable(true)
            val oldGeneration = consent.current(identity)!!.generation
            assertEquals(oldGeneration, Json.parseToJsonElement(consentFile.readText()).jsonObject.getValue("generation").jsonPrimitive.content)
            val id = send("我的专业是什么？请只检索已保存的专业，不保存或修改任何记忆。")
            val failed = idle(id)
            assertEquals(JsonPrimitive("unconfirmed"), failed["status"])
            assertEquals(JsonPrimitive(CloudTaskDiagnostics.message(if (missing) "dns_unavailable" else "network_unavailable")), failed["error"])
            val binding = database.memoryToolBindingDao().find(id)!!
            assertEquals(oldGeneration, binding.grantGeneration)
            assertTrue(database.cloudIntentDao().find(identity, id)!!.submissionAttempted)
            assertNull(database.cloudIntentDao().receipt(identity, id))
            assertEquals(listOf("submit:$id"), calls.toList())
            shutdown()

            // 真实关闭重开 Room 与正式 AtomicFile 授权读取器；不模拟 OS 进程终止。
            database = open(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            consent = loadConsent(); service = host()
            assertEquals(oldGeneration, consent.current(identity)!!.generation)
            assertEquals(JsonPrimitive(false), idle(id)["busy"])
            assertEquals(binding, database.memoryToolBindingDao().find(id))
            assertEquals(listOf("submit:$id"), calls.toList())
            if (damagedConsent == null) {
                enable(false)
                if (renew) enable(true)
            } else {
                // 只损坏本测试自己的合成授权文件，旧请求不能借损坏状态恢复权限。
                consentFile.writeText(damagedConsent, Charsets.UTF_8)
                assertNull(loadConsent().current(identity))
            }
            val newGeneration = consent.current(identity)?.generation
            assertNotEquals(oldGeneration, newGeneration)
            assertFalse(consent.current(identity)?.factsAllowed ?: false)
            val persisted = consentFile.readBytes()
            val newContext = consent.context(identity)
            shutdown()
            database = open(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            consent = loadConsent(); service = host()
            assertArrayEquals(persisted, consentFile.readBytes())
            assertEquals(newContext, consent.context(identity))
            assertEquals(newGeneration, consent.current(identity)?.generation)
            assertEquals(binding, database.memoryToolBindingDao().find(id))
            assertEquals(listOf("submit:$id"), calls.toList())

            suspend fun recover() { call("cloud.recover", buildJsonObject { put("requestId", id) }); idle(id) }
            recover()
            if (missing) {
                assertEquals(JsonPrimitive(CloudTaskDiagnostics.message("not_found")), idle(id)["error"])
                recover()
                assertEquals(listOf("submit:$id", "find:$id", "find:$id"), calls.toList())
                assertNull(database.cloudIntentDao().receipt(identity, id))
                assertTrue(observations.isEmpty())
            } else {
                assertEquals(buildJsonObject { put("status", "rejected"); put("reason", "consent_required") }, observations[id])
                assertEquals(listOf("submit:$id", "find:$id", "memory:$id", "reply:$id", "get:$id", "ack:$id"), calls.toList())
                assertTrue(database.memoryContextDependenciesDao().find(id)!!.state().refs.isEmpty())
                assertEquals(binding, database.memoryToolBindingDao().find(id))
                if (renew) {
                    val fresh = send("我的主要编程语言是Kotlin。我的专业是什么？")
                    idle(fresh)
                    assertNotEquals(id, fresh)
                    assertEquals(newGeneration, database.memoryToolBindingDao().find(fresh)!!.grantGeneration)
                    assertEquals(buildJsonObject {
                        put("status", "found"); put("items", JsonArray(listOf(buildJsonObject {
                            put("id", memory.id); put("version", 1); put("content", memory.content)
                        })))
                    }, observations[fresh])
                    assertEquals(setOf(memory.id to 1), database.memoryContextDependenciesDao().find(fresh)!!.state().refs.map { it.id to it.version }.toSet())
                    assertFalse(database.memoryDao().hasCurrentToolSave("local", fresh))
                    assertEquals(2, calls.count { it.startsWith("submit:") })
                }
            }
            assertEquals(listOf(memory), database.memoryDao().page("local", 0, 20).items)
            assertFalse(database.memoryDao().hasCurrentToolSave("local", id))
            assertEquals(if (renew && !missing) 2 else 1, database.cloudIntentDao().page(identity, 0, 20).size)
            enable(false)
            assertNull(consent.current(identity))
            assertNull(loadConsent().current(identity))
            assertEquals(consent.context(identity), loadConsent().context(identity))
        } finally {
            shutdown()
            check(file.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
            check(context.deleteDatabase(file.absolutePath)) { "合成测试数据库未清理" }
            check(consentFile.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
            AtomicFile(consentFile).delete()
            check(listOf(consentFile, File(consentFile.path + ".bak"), File(consentFile.path + ".new")).none { it.exists() })
        }
    }
}
