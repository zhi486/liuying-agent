package com.healthyagent.running.agent

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.*
import com.healthyagent.running.coordinator.CommandOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 专用于隔离模拟器：外部按 seed、force-stop、recover 顺序启动两次独立 instrumentation。 */
@RunWith(AndroidJUnit4::class)
class MemoryProcessRecoveryTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val cloudId = "22222222-2222-4222-8222-222222222222"
    private fun data(value: CommandOutcome) = (value as CommandOutcome.AcceptedWithData).data
    private fun active() = CloudTaskResponse(cloudId, "running", null, 1000, 31000, 1000, "none", null)
    private fun finished(state: String = "available") = CloudTaskResponse(cloudId, "completed", null, 1000, 31000, 2000, state,
        if (state == "available") buildJsonObject {
            put("status", "completed"); put("answer", "合成恢复回答"); put("actions", JsonArray(emptyList()))
        } else null)
    private fun args() = buildJsonObject {
        put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
    }

    @Test fun 独立进程重启只在显式恢复后查询原任务且不重复写记忆() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("memoryPhase")
        assumeTrue("需要隔离模拟器上的两阶段驱动，不属于无参数全量测试", phase in setOf("seed", "recover"))
        val scenario = arguments.getString("memoryScenario") ?: "saved"
        require(scenario in setOf("saved", "deleted", "revoked", "reenabled"))
        val caseId = UUID.fromString(requireNotNull(arguments.getString("memoryCaseId"))).toString()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "synthetic-memory-process-$caseId.db")
        val proof = File(context.noBackupFilesDir, "synthetic-memory-process-$caseId.json")
        val grantFile = File(context.noBackupFilesDir, "synthetic-memory-process-$caseId-consent.json")
        if (phase == "seed") { assertFalse(file.exists()); assertFalse(proof.exists()) }
        else { assertTrue(file.exists()); assertTrue(proof.exists()) }
        val database = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        val consent = MemoryToolConsent.create(grantFile)
        val paused = if (phase == "seed") StandardTestDispatcher() else null
        val scope = CoroutineScope(SupervisorJob() + (paused ?: Dispatchers.Default))
        var networkCalls = 0
        var replies = 0
        val received = CompletableDeferred<JsonObject>()
        var requestId = ""
        var memoryId = ""
        val api = object : CloudTaskApi {
            override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse = error("重启不得重新提交")
            override suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean): CloudTaskResponse = error("重启不得重新提交记忆任务")
            override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse = error("原云任务标识已落盘，不应改查其他任务")
            override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse {
                networkCalls++; assertEquals(cloudId, taskId)
                return if (replies == 0) active() else finished()
            }
            override suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool {
                networkCalls++; assertEquals(cloudId, taskId)
                val binding = database.memoryToolBindingDao().find(requestId)!!
                return CloudMemoryTool(requestId, taskId, binding.grantGeneration, "save_1", "memory.upsert", args())
            }
            override suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject) {
                networkCalls++; replies++
                assertEquals(requestId, tool.requestId)
                received.complete(observation)
            }
            override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse {
                networkCalls++; assertEquals(cloudId, taskId); assertEquals("acknowledge", action)
                return finished("acknowledged")
            }
        }
        val service = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, memoryConsent = consent)
        suspend fun listing() = data(service.handle("cloud.list", buildJsonObject {})).getValue("items").jsonArray.single().jsonObject
        try {
            if (phase == "seed") {
                consent.set(identity, consent.context(identity), true)
                val conversation = data(service.handle("cloud.newConversation", buildJsonObject {})).getValue("conversationId")
                val prepared = data(service.handle("cloud.prepare", buildJsonObject {
                    put("prompt", "我喜欢夜跑"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversation)
                }))
                data(service.handle("cloud.confirm", buildJsonObject {
                    put("token", prepared.getValue("token")); put("approved", true)
                }))
                requestId = prepared.getValue("token").jsonPrimitive.content
                assertTrue(database.cloudIntentDao().claimSubmission(identity, requestId, 1000))
                database.cloudIntentDao().accept(identity, requestId, active())
                val saved = service.executeMemoryTool(identity, requestId, "save_1", "memory.upsert", args()) as MemoryToolResult.Saved
                memoryId = saved.memory.id
                val generation = consent.current(identity)!!.generation
                if (scenario == "deleted") {
                    // 删除覆盖手工更正前后的正文；重启后也不能换调用 ID 绕过删除指纹。
                    data(MemoryService(database.memoryDao(), now = { 1001 }).handle("memory.update", buildJsonObject {
                        put("memoryId", memoryId); put("version", 1); put("content", "我喜欢晨跑")
                    }))
                    data(MemoryService(database.memoryDao(), now = { 1002 }).handle("memory.delete", buildJsonObject {
                        put("memoryId", memoryId); put("version", 2); put("confirmed", true)
                    }))
                } else if (scenario in setOf("revoked", "reenabled")) {
                    data(service.handle("cloud.memoryConsent", buildJsonObject {
                        put("identityToken", consent.context(identity)); put("enabled", false)
                    }))
                    if (scenario == "reenabled") data(service.handle("cloud.memoryConsent", buildJsonObject {
                        put("identityToken", consent.context(identity)); put("enabled", true)
                    }))
                }
                // 此阶段模拟回传丢失；进程终止由外部执行，不据此声称网络恢复验收。
                proof.writeText(buildJsonObject {
                    put("pid", Process.myPid()); put("requestId", requestId); put("scenario", scenario)
                    put("memoryId", memoryId); put("generation", generation)
                }.toString())
                assertEquals(0, networkCalls)
            } else {
                val previous = Json.parseToJsonElement(proof.readText()).jsonObject
                assertNotEquals(previous.getValue("pid").jsonPrimitive.int, Process.myPid())
                assertEquals(JsonPrimitive(scenario), previous["scenario"])
                requestId = previous.getValue("requestId").jsonPrimitive.content
                memoryId = previous.getValue("memoryId").jsonPrimitive.content
                if (scenario == "revoked") assertNull(consent.current(identity))
                else {
                    val grant = checkNotNull(consent.current(identity))
                    if (scenario == "reenabled") assertNotEquals(previous.getValue("generation").jsonPrimitive.content, grant.generation)
                    else assertEquals(previous.getValue("generation").jsonPrimitive.content, grant.generation)
                }
                delay(100)
                assertEquals(JsonPrimitive(false), listing()["busy"])
                if (scenario == "deleted") assertFalse(listing().containsKey("memorySaved"))
                else assertEquals(JsonPrimitive(true), listing()["memorySaved"])
                if (scenario != "saved") {
                    val reason = if (scenario == "deleted") "conflict" else "consent_required"
                    for (callId in listOf("save_1", "late_new_key")) {
                        assertEquals(MemoryToolResult.Rejected(reason),
                            service.executeMemoryTool(identity, requestId, callId, "memory.upsert", args()))
                    }
                    val found = service.executeMemoryTool(identity, requestId, "search_after_restart", "memory.search",
                        buildJsonObject { put("query", "跑") })
                    if (scenario == "deleted") assertEquals(MemoryToolResult.Found(emptyList()), found)
                    else assertEquals(MemoryToolResult.Rejected("consent_required"), found)
                }
                assertEquals(0, networkCalls)
                data(service.handle("cloud.recover", buildJsonObject { put("requestId", requestId) }))
                val observation = withTimeout(5000) { received.await() }
                if (scenario == "saved") {
                    assertEquals(JsonPrimitive("saved"), observation["status"])
                    assertEquals(JsonPrimitive(true), observation["replayed"])
                } else {
                    assertEquals(JsonPrimitive("rejected"), observation["status"])
                    assertEquals(JsonPrimitive(if (scenario == "deleted") "conflict" else "consent_required"), observation["reason"])
                }
                withTimeout(10000) {
                    while (listing()["busy"] != JsonPrimitive(false)) delay(10)
                }
                assertEquals(JsonPrimitive("合成恢复回答"), listing()["answer"])
                assertEquals(JsonPrimitive("acknowledged"), listing()["resultState"])
                assertEquals(1, replies)
                assertEquals(5, networkCalls)
                if (scenario != "saved") {
                    data(service.handle("cloud.recover", buildJsonObject { put("requestId", requestId) }))
                    withTimeout(5000) { while (listing()["busy"] != JsonPrimitive(false)) delay(10) }
                    assertEquals(1, replies)
                    assertEquals(6, networkCalls)
                }
            }
            val memory = checkNotNull(database.memoryDao().find("local", memoryId))
            if (scenario == "deleted") {
                assertEquals(0, database.memoryDao().count("local"))
                assertNull(memory.content)
                assertEquals(3, memory.version)
                assertNotNull(memory.deletedAtMillis)
                assertEquals(0, database.memoryDao().revisionCount("local", memory.id))
                for (content in listOf("我喜欢夜跑", "我喜欢晨跑"))
                    assertEquals(memory.deletedAtMillis, database.memoryDao().deletionTime("local", MemoryToolPolicy.hash(content)))
            } else {
                assertEquals(1, database.memoryDao().count("local"))
                assertEquals("我喜欢夜跑", memory.content)
                assertEquals(1, memory.version)
                assertEquals(1, database.memoryDao().revisionCount("local", memory.id))
            }
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            assertEquals(setOf(MemoryRef(memory.id, 1)), database.memoryContextDependenciesDao().find(requestId)!!.state().refs)
        } finally {
            scope.cancel(); paused?.scheduler?.runCurrent()
            scope.coroutineContext[Job]?.join(); database.close()
        }
    }
}
