package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 暂停网络调度，只向隔离 Room 写入合成回执；需设备执行后才算数据库验收。 */
@RunWith(AndroidJUnit4::class)
class MemoryContextDatabaseTest {
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var service: CloudAgentService
    private lateinit var conversation: String
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private var stored: String? = null
    private val consent = MemoryToolConsent({ stored }, { stored = it })
    private var clock = 1000L
    private var networkCalls = 0
    private val api = object : CloudTaskApi {
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse { networkCalls++; error("禁止派发") }
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse { networkCalls++; error("禁止查询") }
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse { networkCalls++; error("禁止查询") }
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse { networkCalls++; error("禁止控制") }
    }
    private fun createService() = CloudAgentService(database, { identity }, { api }, scope, now = { clock }, memoryConsent = consent)
    private fun data(value: CommandOutcome) = (value as CommandOutcome.AcceptedWithData).data
    private fun history() = buildJsonObject {
        put("version", "conversation_v1"); put("id", conversation); put("approved", true); put("messages", JsonArray(emptyList()))
    }
    private suspend fun seed(prompt: String = "我喜欢夜跑"): CloudTaskIntent {
        val intent = CloudTaskIntent.confirm(identity, prompt, true, clock).copy(conversationId = conversation, conversationJson = history().toString())
        database.cloudIntentDao().save(intent)
        database.memoryContextDependenciesDao().save(MemoryContextState().row(intent.clientRequestId))
        database.memoryToolBindingDao().insert(MemoryToolBinding.capture(intent, consent.current(identity)!!))
        assertTrue(database.cloudIntentDao().claimSubmission(identity, intent.clientRequestId, clock))
        return intent
    }
    private suspend fun complete(intent: CloudTaskIntent, answer: String = "合成回答：你喜欢夜跑") {
        database.cloudIntentDao().accept(identity, intent.clientRequestId, CloudTaskResponse(
            UUID.randomUUID().toString(), "completed", null, intent.consentedAtMillis, intent.consentedAtMillis + 30000,
            clock, "available", buildJsonObject {
                put("status", "completed"); put("answer", answer); put("actions", JsonArray(emptyList()))
            }))
        clock++
    }
    private suspend fun completeFailed(intent: CloudTaskIntent) {
        database.cloudIntentDao().accept(identity, intent.clientRequestId, CloudTaskResponse(
            UUID.randomUUID().toString(), "failed", "model_unavailable", intent.consentedAtMillis,
            intent.consentedAtMillis + 30000, clock, "available", buildJsonObject {
                put("status", "failed"); put("reason", "model_unavailable"); put("actions", JsonArray(emptyList()))
            }))
        clock++
    }
    private suspend fun save(intent: CloudTaskIntent): MemoryRecord = (service.executeMemoryTool(identity, intent.clientRequestId,
        "save", "memory.upsert", buildJsonObject {
            put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
        }) as MemoryToolResult.Saved).memory
    private suspend fun preview(): JsonObject = data(service.handle("cloud.prepare", buildJsonObject {
        put("prompt", "继续聊聊"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversation)
    }))
    private suspend fun confirm(preview: JsonObject) = service.handle("cloud.confirm", buildJsonObject {
        put("token", preview.getValue("token")); put("approved", true)
    })
    private fun messages(preview: JsonObject) = preview.getValue("conversation").jsonObject.getValue("messages").jsonArray
    private val correctionPrompt = "我喜欢晨跑。请更正之前的夜跑偏好，不要新增重复记录。"
    private suspend fun confirmedMemoryTurn(prompt: String, selected: List<MemoryRef> = emptyList()): CloudTaskIntent {
        val prepared = data(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", prompt); put("conversationId", conversation)
            put("memoryRefs", JsonArray(selected.map { buildJsonObject { put("id", it.id); put("version", it.version) } }))
        }))
        assertTrue(confirm(prepared) is CommandOutcome.AcceptedWithData)
        val intent = database.cloudIntentDao().find(identity, prepared.getValue("token").jsonPrimitive.content)!!
        if (selected.isEmpty()) assertTrue(database.cloudIntentDao().claimSubmission(identity, intent.clientRequestId, clock))
        else assertNotNull(database.cloudIntentDao().claimSelected(identity, intent.clientRequestId, clock))
        // 不运行暂停的网络任务；新宿主只读取已保存的真实任务、授权绑定及依赖快照。
        service = createService()
        return intent
    }
    private suspend fun searchAndCorrect(intent: CloudTaskIntent, original: MemoryRecord): MemoryRecord {
        val found = service.executeMemoryTool(identity, intent.clientRequestId, "search_original", "memory.search",
            buildJsonObject { put("query", "夜跑") }) as MemoryToolResult.Found
        assertEquals(listOf(MemoryRef(original.id, original.version)), found.items.map { MemoryRef(it.id, it.version) })
        val result = service.executeMemoryTool(identity, intent.clientRequestId, "correct_original", "memory.upsert", buildJsonObject {
            put("category", "preference"); put("evidence", "我喜欢晨跑")
            put("target", buildJsonObject { put("id", original.id); put("version", original.version) })
        })
        assertTrue("搜索后应只更正原记忆", result is MemoryToolResult.Saved)
        return (result as MemoryToolResult.Saved).memory.also {
            assertEquals(original.id, it.id)
            assertEquals(original.version + 1, it.version)
            assertEquals("我喜欢晨跑", it.content)
        }
    }

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        service = createService()
        consent.set(identity, consent.context(identity), true)
        conversation = database.conversationDao().create(identity, clock).id
    }
    @After fun close() { scope.cancel(); database.close() }

    @Test fun 多页失败任务不挤掉同会话最近完整问答() = runBlocking {
        val first = seed(); complete(first)
        repeat(51) {
            completeFailed(seed())
        }
        assertTrue(database.cloudIntentDao().conversationPage(identity, conversation).all {
            database.cloudIntentDao().receipt(identity, it.clientRequestId)!!.status == "failed"
        })
        assertEquals(listOf("我喜欢夜跑", "合成回答：你喜欢夜跑"),
            messages(preview()).map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertEquals(0, networkCalls)
    }

    @Test fun 分页后仍只取最近四轮且保持旧问答在前() = runBlocking {
        repeat(6) { complete(seed("第${it}问"), "第${it}答") }
        repeat(51) { completeFailed(seed()) }
        assertEquals((2..5).flatMap { listOf("第${it}问", "第${it}答") },
            messages(preview()).map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertEquals(0, networkCalls)
    }

    @Test fun 分页后仍保留完整往返且不超过原字数上限() = runBlocking {
        repeat(5) { complete(seed("第${it}问"), "$it" + "字".repeat(3899)) }
        repeat(51) { completeFailed(seed()) }
        val history = messages(preview())
        assertEquals(6, history.size)
        assertTrue(history.sumOf { it.jsonObject.getValue("text").jsonPrimitive.content.length } <= 12000)
        assertEquals(listOf("第2问", "第3问", "第4问"),
            history.filterIndexed { index, _ -> index % 2 == 0 }.map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertEquals(0, networkCalls)
    }

    @Test fun 更早页面存在未确认任务仍阻止继续提交() = runBlocking {
        seed()
        clock++
        repeat(51) { completeFailed(seed()) }
        assertTrue(service.handle("cloud.prepare", buildJsonObject {
            put("prompt", "继续聊聊"); put("memoryRefs", JsonArray(emptyList())); put("conversationId", conversation)
        }) is CommandOutcome.Rejected)
        assertEquals(0, networkCalls)
    }

    @Test fun 文件库重开及宿主重建保留三轮上下文但新会话隔离() = runBlocking {
        database.close()
        val file = java.io.File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir,
            "conversation-reopen-${UUID.randomUUID()}.db")
        fun open() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java, file.absolutePath).build()
        database = open()
        conversation = database.conversationDao().create(identity, clock).id
        service = createService()
        complete(seed())
        repeat(2) { index ->
            val next = preview()
            assertEquals((index + 1) * 2, messages(next).size)
            assertTrue(confirm(next) is CommandOutcome.AcceptedWithData)
            val intent = database.cloudIntentDao().find(identity, next.getValue("token").jsonPrimitive.content)!!
            assertTrue(database.cloudIntentDao().claimSubmission(identity, intent.clientRequestId, clock))
            complete(intent)
            service = createService()
        }
        val expected = messages(preview())
        assertEquals(6, expected.size)
        database.close()
        database = open()
        service = createService()
        assertEquals(expected, messages(preview()))
        assertEquals(conversation, data(service.handle("cloud.list", buildJsonObject {}))["conversationId"]?.jsonPrimitive?.content)
        val original = conversation
        conversation = database.conversationDao().create(identity, clock).id
        assertTrue(messages(preview()).isEmpty())
        database.conversationDao().select(identity, original)
        conversation = original
        assertEquals(expected, messages(preview()))
        assertEquals(0, networkCalls)
    }

    @Test fun 自动记忆引用传给连续追问且删除后两轮均不再上传但旧答案仍可查看() = runBlocking {
        val first = seed()
        val memory = save(first)
        complete(first)
        val followup = preview()
        assertEquals(2, messages(followup).size)
        assertTrue(confirm(followup) is CommandOutcome.AcceptedWithData)
        val second = database.cloudIntentDao().find(identity, followup.getValue("token").jsonPrimitive.content)!!
        assertEquals(setOf(MemoryRef(memory.id, 1)), database.memoryContextDependenciesDao().find(second.clientRequestId)!!.state().refs)
        assertTrue(database.cloudIntentDao().claimSubmission(identity, second.clientRequestId, clock))
        complete(second)
        // 新宿主模拟重建，原暂停调度不执行网络。
        service = createService()
        assertEquals(4, messages(preview()).size)
        database.memoryDao().delete("local", memory.id, 1, clock)
        assertTrue(messages(preview()).isEmpty())
        val displayed = data(service.handle("cloud.list", buildJsonObject {})).getValue("items").jsonArray
        assertEquals(2, displayed.size)
        assertTrue(displayed.all { it.jsonObject.getValue("answer") != JsonNull })
        assertEquals(0, networkCalls)
    }

    @Test fun 工具搜索也跟踪引用修改与恢复同正文都不使旧版本重新有效() = runBlocking {
        val memory = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val intent = seed()
        assertTrue(service.executeMemoryTool(identity, intent.clientRequestId, "search", "memory.search",
            buildJsonObject { put("query", "夜跑") }) is MemoryToolResult.Found)
        assertEquals(setOf(MemoryRef(memory.id, 1)), database.memoryContextDependenciesDao().find(intent.clientRequestId)!!.state().refs)
        complete(intent)
        assertEquals(2, messages(preview()).size)
        database.memoryDao().update("local", memory.id, 1, "我喜欢晨跑", clock)
        assertTrue(messages(preview()).isEmpty())
        database.memoryDao().restore("local", memory.id, 2, 1, clock)
        assertTrue(messages(preview()).isEmpty())
        assertEquals(0, networkCalls)
    }

    @Test fun 空历史当前轮搜索后定向更正同会话预览仍包含修正问答() = runBlocking {
        val original = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val intent = confirmedMemoryTurn(correctionPrompt)
        assertTrue(intent.conversation()!!.getValue("messages").jsonArray.isEmpty())
        val corrected = searchAndCorrect(intent, original)
        complete(intent, "已更正为晨跑偏好")
        assertEquals(listOf(correctionPrompt, "已更正为晨跑偏好"),
            messages(preview()).map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertEquals(2, database.memoryDao().find("local", corrected.id)!!.version)
        assertEquals(0, networkCalls)
    }

    @Test fun 继承的旧版本不能借当前轮搜索后更正重新成为有效上下文() = runBlocking {
        val first = seed()
        val original = save(first)
        complete(first)
        val intent = confirmedMemoryTurn(correctionPrompt)
        assertEquals(2, intent.conversation()!!.getValue("messages").jsonArray.size)
        searchAndCorrect(intent, original)
        complete(intent, "已更正为晨跑偏好")
        assertTrue("继承旧版本的问答均应失效，不能只把引用整体替换成新版本", messages(preview()).isEmpty())
        assertEquals(0, networkCalls)
    }

    @Test fun 手动选取的旧版本不能借当前轮搜索后更正重新成为有效上下文() = runBlocking {
        val original = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val selected = listOf(MemoryRef(original.id, original.version))
        val intent = confirmedMemoryTurn(correctionPrompt, selected)
        assertTrue(intent.conversation()!!.getValue("messages").jsonArray.isEmpty())
        assertEquals(selected, intent.memoryRefs())
        searchAndCorrect(intent, original)
        complete(intent, "已更正为晨跑偏好")
        assertTrue("手动授权的原版本仍是固定依赖", messages(preview()).isEmpty())
        assertEquals(0, networkCalls)
    }

    @Test fun 当前轮搜索后更正的有效问答在后续用户修改记忆时失效() = runBlocking {
        val original = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val intent = confirmedMemoryTurn(correctionPrompt)
        val corrected = searchAndCorrect(intent, original)
        complete(intent, "已更正为晨跑偏好")
        assertEquals("修改前修正问答应可用于追问", 2, messages(preview()).size)
        database.memoryDao().update("local", corrected.id, corrected.version, "我喜欢午后跑步", clock)
        assertTrue(messages(preview()).isEmpty())
        database.memoryDao().restore("local", corrected.id, corrected.version + 1, corrected.version, clock)
        assertTrue("恢复同正文也不能复活旧版本问答", messages(preview()).isEmpty())
        assertEquals(0, networkCalls)
    }

    @Test fun 当前轮搜索后更正的有效问答在后续用户删除记忆时失效() = runBlocking {
        val original = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val intent = confirmedMemoryTurn(correctionPrompt)
        val corrected = searchAndCorrect(intent, original)
        complete(intent, "已更正为晨跑偏好")
        assertEquals("删除前修正问答应可用于追问", 2, messages(preview()).size)
        database.memoryDao().delete("local", corrected.id, corrected.version, clock)
        assertTrue(messages(preview()).isEmpty())
        val displayed = data(service.handle("cloud.list", buildJsonObject {})).getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("已更正为晨跑偏好"), displayed["answer"])
        assertEquals(0, networkCalls)
    }

    @Test fun 预览后记忆变化拒绝确认不保存半次任务或绑定() = runBlocking {
        val intent = seed(); val memory = save(intent); complete(intent)
        val pending = preview()
        database.memoryDao().update("local", memory.id, 1, "我喜欢晨跑", clock)
        assertTrue(confirm(pending) is CommandOutcome.Rejected)
        val token = pending.getValue("token").jsonPrimitive.content
        assertNull(database.cloudIntentDao().find(identity, token))
        assertNull(database.memoryToolBindingDao().find(token))
        assertNull(database.memoryContextDependenciesDao().find(token))
        assertEquals(0, networkCalls)
    }

    @Test fun 确认后提交前再次检查继承引用且失败不消耗提交许可() = runBlocking {
        val first = seed(); val memory = save(first); complete(first)
        val other = database.memoryDao().create("local", UUID.randomUUID().toString(), "独立手选记忆", clock)
        for (selected in listOf(false, true)) {
            val next = (if (selected) CloudTaskIntent.confirmSelected(identity, "继续", listOf(MemoryRef(other.id, 1)), clock)
                else CloudTaskIntent.confirm(identity, "继续", true, clock)).copy(conversationId = conversation, conversationJson = history().toString())
            database.cloudIntentDao().save(next)
            database.memoryContextDependenciesDao().save(MemoryContextState(setOf(MemoryRef(memory.id, 1))).row(next.clientRequestId))
            if (!selected) database.memoryDao().delete("local", memory.id, 1, clock)
            try { CloudTaskSync(database.cloudIntentDao(), api, { clock }).submit(identity, next.clientRequestId); fail("不应提交旧记忆上下文") }
            catch (failure: CloudTaskFailure) { assertEquals("memory_changed", failure.code) }
            assertFalse(database.cloudIntentDao().find(identity, next.clientRequestId)!!.submissionAttempted)
        }
        assertEquals(0, networkCalls)
    }

    @Test fun 引用丢失损坏或超限时只排除受影响回答不阻止空上下文新问题() = runBlocking {
        val intent = seed(); save(intent); complete(intent)
        for (state in listOf("missing", "broken", "exhausted")) {
            when (state) {
                "missing" -> database.openHelper.writableDatabase.execSQL("DELETE FROM agent_memory_context_dependencies WHERE requestId = ?", arrayOf(intent.clientRequestId))
                "broken" -> database.memoryContextDependenciesDao().save(MemoryContextDependencies(intent.clientRequestId, "{}", false))
                else -> database.memoryContextDependenciesDao().save(MemoryContextState(exhausted = true).row(intent.clientRequestId))
            }
            assertTrue(messages(preview()).isEmpty())
        }
        assertEquals(0, networkCalls)
    }

    @Test fun 引用落盘失败会回滚工具记忆版本及回执() = runBlocking {
        val intent = seed()
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_fail_dependencies
            BEFORE INSERT ON agent_memory_context_dependencies BEGIN SELECT RAISE(ABORT, '合成引用失败'); END""")
        val result = service.executeMemoryTool(identity, intent.clientRequestId, "save", "memory.upsert", buildJsonObject {
            put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
        })
        assertEquals(MemoryToolResult.Rejected("storage_unavailable"), result)
        assertEquals(0, database.memoryDao().count("local"))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_revisions").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }

    @Test fun 搜索后更正的同调用重放不重复版本历史回执或引用且问答仍可继承() = runBlocking {
        val original = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val intent = confirmedMemoryTurn(correctionPrompt)
        assertEquals("[]", database.memoryContextDependenciesDao().find(intent.clientRequestId)!!.protectedReferencesJson)
        val corrected = searchAndCorrect(intent, original)
        val dependencies = database.memoryContextDependenciesDao().find(intent.clientRequestId)!!
        assertEquals(setOf(MemoryRef(corrected.id, 2)), dependencies.state().refs)
        assertEquals("[]", dependencies.protectedReferencesJson)
        val history = database.memoryDao().history("local", corrected.id, 0, 20)!!
        assertEquals(2, history.totalItems)
        val operationId = database.openHelper.readableDatabase.query(
            "SELECT operationId FROM agent_memory_tool_receipts WHERE turnId = ?", arrayOf(intent.clientRequestId)).use {
            assertTrue(it.moveToFirst())
            val value = it.getString(0)
            assertFalse(it.moveToNext())
            value
        }
        val receipt = database.memoryDao().toolReceipt("local", operationId)!!
        val replay = service.executeMemoryTool(identity, intent.clientRequestId, "correct_original", "memory.upsert", buildJsonObject {
            put("category", "preference"); put("evidence", "我喜欢晨跑")
            put("target", buildJsonObject { put("id", original.id); put("version", original.version) })
        })
        assertEquals(MemoryToolResult.Saved(corrected, true), replay)
        assertEquals(corrected, database.memoryDao().find("local", corrected.id)!!.record())
        assertEquals(history, database.memoryDao().history("local", corrected.id, 0, 20))
        assertEquals(2, database.memoryDao().revisionCount("local", corrected.id))
        assertEquals(1, database.memoryDao().count("local"))
        assertEquals(receipt, database.memoryDao().toolReceipt("local", operationId))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        assertEquals(dependencies, database.memoryContextDependenciesDao().find(intent.clientRequestId))
        complete(intent, "已更正为晨跑偏好")
        assertEquals(listOf(correctionPrompt, "已更正为晨跑偏好"),
            messages(preview()).map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertEquals(0, networkCalls)
    }

    @Test fun 搜索后保护引用损坏时定向更正被拒且事务回滚记忆历史与保存回执() = runBlocking {
        val original = database.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", clock)
        val intent = confirmedMemoryTurn(correctionPrompt)
        val found = service.executeMemoryTool(identity, intent.clientRequestId, "search_original", "memory.search",
            buildJsonObject { put("query", "夜跑") }) as MemoryToolResult.Found
        assertEquals(listOf(original), found.items)
        val dependencies = database.memoryContextDependenciesDao().find(intent.clientRequestId)!!
        assertEquals(setOf(MemoryRef(original.id, 1)), dependencies.state().refs)
        assertEquals("[]", dependencies.protectedReferencesJson)
        val corrupted = dependencies.copy(protectedReferencesJson = "{}")
        database.memoryContextDependenciesDao().save(corrupted)
        val history = database.memoryDao().history("local", original.id, 0, 20)!!
        val result = service.executeMemoryTool(identity, intent.clientRequestId, "correct_original", "memory.upsert", buildJsonObject {
            put("category", "preference"); put("evidence", "我喜欢晨跑")
            put("target", buildJsonObject { put("id", original.id); put("version", original.version) })
        })
        assertEquals(MemoryToolResult.Rejected("invalid_tool"), result)
        assertEquals(original, database.memoryDao().find("local", original.id)!!.record())
        assertEquals(history, database.memoryDao().history("local", original.id, 0, 20))
        assertEquals(1, database.memoryDao().revisionCount("local", original.id))
        assertEquals(1, database.memoryDao().count("local"))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertNull(database.cloudIntentDao().receipt(identity, intent.clientRequestId))
        assertEquals("损坏行必须原样保留，不能降级为空保护基线", corrupted,
            database.memoryContextDependenciesDao().find(intent.clientRequestId))
        complete(intent, "不得用于追问的合成回答")
        assertTrue(messages(preview()).isEmpty())
        assertEquals(0, networkCalls)
    }
}
