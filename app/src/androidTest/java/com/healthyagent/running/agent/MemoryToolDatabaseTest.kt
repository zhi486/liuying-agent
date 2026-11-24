package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 只用合成数据；本套需设备执行，不能把编译成功当作事务验收。 */
@RunWith(AndroidJUnit4::class)
class MemoryToolDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AgentDatabase
    private val turn = MemoryToolTurn("local", "a".repeat(64), "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", 1000, "我喜欢夜跑")
    private val grant = MemoryToolGrant(turn.scope, turn.grantGeneration)
    private val args = buildJsonObject {
        put("category", "preference"); put("evidence", "我喜欢夜跑"); put("target", JsonNull)
    }
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build() }
    @After fun close() { database.close() }
    private fun executor(db: AgentDatabase = database) = MemoryToolExecutor(db.memoryDao(), { grant }, { 2000 })

    @Test fun 并发相同调用只创建一条记忆且不同调用内容相同也去重() = runTest {
        val service = executor()
        val results = coroutineScope {
            (0..7).map { async { service.execute(turn, "call", "memory.upsert", args) } }.awaitAll()
        }
        assertTrue(results.all { it is MemoryToolResult.Saved })
        assertEquals(1, results.filterIsInstance<MemoryToolResult.Saved>().count { !it.replayed })
        assertEquals(1, database.memoryDao().count("local"))
        assertTrue(service.execute(turn, "other_call", "memory.upsert", args) is MemoryToolResult.Saved)
        assertEquals(1, database.memoryDao().count("local"))
    }

    @Test fun 自然更正同一记录后重开库与新回合只搜索到新版() = runTest {
        val file = File(context.noBackupFilesDir, "memory-correction-${UUID.randomUUID()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        fun upsert(evidence: String, target: MemoryRef? = null) = buildJsonObject {
            put("category", "preference"); put("evidence", evidence)
            put("target", target?.let { buildJsonObject { put("id", it.id); put("version", it.version) } } ?: JsonNull)
        }
        val original = "我喜欢把测试计划排在周三"
        val updated = "我喜欢把测试计划排在周四"
        val seedTurn = turn.copy(userText = original)
        val correctionTurn = turn.copy(turnId = "33333333-3333-4333-8333-333333333333",
            userText = "$updated。请更正之前的周三偏好，不要新增重复记录。")
        val first = open().let { db -> try {
            val service = executor(db)
            val saved = (service.execute(seedTurn, "seed", "memory.upsert", upsert(original)) as MemoryToolResult.Saved).memory
            val found = service.execute(correctionTurn, "find_original", "memory.search", buildJsonObject { put("query", "测试计划") }) as MemoryToolResult.Found
            assertEquals(listOf(saved), found.items)
            val result = service.execute(correctionTurn, "correct", "memory.upsert", upsert(updated, MemoryRef(saved.id, saved.version)))
            assertTrue("更正附言应被接受且只更新搜索到的原记录", result is MemoryToolResult.Saved)
            assertEquals(saved.id, (result as MemoryToolResult.Saved).memory.id)
            assertEquals(2, result.memory.version)
            assertEquals(1, db.memoryDao().count("local"))
            assertEquals(listOf(updated, original), db.memoryDao().history("local", saved.id, 0, 20)!!.items.map { it.content })
            saved
        } finally { db.close() } }
        open().let { db -> try {
            val service = executor(db)
            val newTurn = turn.copy(turnId = "44444444-4444-4444-8444-444444444444", userText = "我希望把测试计划安排在哪一天？")
            val found = service.execute(newTurn, "recall", "memory.search", buildJsonObject { put("query", "测试计划") }) as MemoryToolResult.Found
            assertEquals(1, found.items.size)
            assertEquals(first.id, found.items.single().id)
            assertEquals(2, found.items.single().version)
            assertEquals(updated, found.items.single().content)
            assertEquals(1, db.memoryDao().count("local"))
            assertEquals(MemoryToolResult.Rejected("conflict"), service.execute(seedTurn, "seed", "memory.upsert", upsert(original)))
            assertEquals(MemoryToolResult.Rejected("conflict"), service.execute(correctionTurn, "stale", "memory.upsert", upsert(updated, MemoryRef(first.id, 1))))
        } finally { db.close() } }
    }

    @Test fun 事务内撤权回滚记忆及回执() = runTest {
        var checks = 0
        // 第四次读取位于写记忆和回执之后、事务提交之前，必须整笔回滚。
        val service = MemoryToolExecutor(database.memoryDao(), { if (++checks < 4) grant else null }, { 2000 })
        assertEquals(MemoryToolResult.Rejected("consent_required"), service.execute(turn, "call", "memory.upsert", args))
        assertEquals(0, database.memoryDao().count("local"))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_revisions").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_memory_tool_receipts").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun 写回执失败不能留下正文或报告成功() = runTest {
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_fail_receipt
            BEFORE INSERT ON agent_memory_tool_receipts BEGIN SELECT RAISE(ABORT, '合成回执失败'); END""")
        val result = executor().execute(turn, "call", "memory.upsert", args)
        assertEquals(MemoryToolResult.Rejected("storage_unavailable"), result)
        assertEquals(0, database.memoryDao().count("local"))
    }

    @Test fun 版本历史恢复和删除与正文处于同一事务() = runTest {
        val dao = database.memoryDao()
        val first = (executor().execute(turn, "call", "memory.upsert", args) as MemoryToolResult.Saved).memory
        dao.update("local", first.id, 1, "我喜欢晨跑", 3000)
        assertEquals(listOf("manual", "agent"), dao.history("local", first.id, 0, 20)!!.items.map { it.changeSource })
        val restored = dao.restore("local", first.id, 2, 1, 4000)!!
        assertEquals(3, restored.version)
        assertEquals(first.content, restored.content)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_fail_history_delete
            BEFORE DELETE ON agent_memory_revisions BEGIN SELECT RAISE(ABORT, '合成历史删除失败'); END""")
        try { dao.delete("local", first.id, 3, 5000); fail("整笔删除应回滚") } catch (_: Exception) { }
        assertEquals(restored, dao.find("local", first.id)!!.record())
        assertEquals(3, dao.revisionCount("local", first.id))
        assertNull(dao.deletionTime("local", MemoryToolPolicy.hash("我喜欢夜跑")))
        assertNull(dao.deletionTime("local", MemoryToolPolicy.hash("我喜欢晨跑")))
        // 仅移除当前合成测试创建的触发器，不涉及用户数据库。
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_fail_history_delete")
        dao.delete("local", first.id, 3, 6000)
        assertNull(dao.find("local", first.id)!!.content)
        assertEquals(0, dao.revisionCount("local", first.id))
    }

    @Test fun 手工更正删除的全部正文指纹阻止迟到创建但允许明确重新记住() = runTest {
        val dao = database.memoryDao()
        val first = dao.create("local", UUID.randomUUID().toString(), "我喜欢夜跑", 1000)
        dao.update("local", first.id, 1, "我喜欢晨跑", 2000)
        dao.delete("local", first.id, 2, 3000)
        val service = MemoryToolExecutor(dao, { grant }, { 5000 })
        suspend fun attempt(text: String, started: Long, explicit: Boolean = false, owner: String = "local") = service.execute(
            turn.copy(ownerId = owner, startedAtMillis = started, userText = (if (explicit) "请记住：" else "") + text),
            UUID.randomUUID().toString(), "memory.upsert", buildJsonObject {
                put("category", "preference"); put("evidence", text); put("target", JsonNull)
            })
        for (text in listOf("我喜欢夜跑", "我喜欢晨跑")) {
            assertEquals(MemoryToolResult.Rejected("conflict"), attempt(text, 2500))
            assertEquals(MemoryToolResult.Rejected("conflict"), attempt(text, 4000))
            assertEquals(MemoryToolResult.Rejected("conflict"), attempt(text, 3000, true))
            assertEquals(3000L, dao.deletionTime("local", MemoryToolPolicy.hash(text)))
        }
        assertEquals(0, dao.count("local")); assertEquals(0, dao.revisionCount("local", first.id))
        assertTrue(attempt("我喜欢晨跑", 4000, true) is MemoryToolResult.Saved)
        assertTrue(attempt("我喜欢夜跑", 2500, owner = "other") is MemoryToolResult.Saved)
        assertTrue(attempt("我喜欢短回答", 2500) is MemoryToolResult.Saved)
        assertNull(dao.find("local", first.id)!!.content)
        assertNull(dao.history("local", first.id, 0, 20))
        database.openHelper.readableDatabase.query("SELECT ownerId, contentHash, deletedAtMillis FROM agent_memory_deletion_markers").use {
            var count = 0
            while (it.moveToNext()) { count++; assertEquals("local", it.getString(0)); assertTrue(it.getString(1).matches(Regex("[0-9a-f]{64}"))); assertEquals(3000L, it.getLong(2)) }
            assertEquals(2, count)
        }
    }

    @Test fun 写删除指纹失败不删除正文或历史也不能降低同内容边界() = runTest {
        val dao = database.memoryDao()
        val first = dao.create("local", UUID.randomUUID().toString(), "我喜欢夜跑", 1000)
        val duplicate = dao.create("local", UUID.randomUUID().toString(), "我喜欢夜跑", 1000)
        dao.delete("local", first.id, 1, 4000)
        dao.delete("local", duplicate.id, 1, 2000)
        assertEquals(4000L, dao.deletionTime("local", MemoryToolPolicy.hash("我喜欢夜跑")))
        val kept = dao.create("local", UUID.randomUUID().toString(), "我喜欢晨跑", 1000)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_fail_deletion_marker
            BEFORE INSERT ON agent_memory_deletion_markers BEGIN SELECT RAISE(ABORT, '合成删除指纹失败'); END""")
        try { dao.delete("local", kept.id, 1, 5000); fail("删除应该失败") } catch (_: Exception) { }
        assertEquals(kept, dao.find("local", kept.id)!!.record())
        assertEquals(1, dao.revisionCount("local", kept.id))
        assertNull(dao.deletionTime("local", MemoryToolPolicy.hash("我喜欢晨跑")))
    }

    @Test fun 手工删除指纹重开文件库后仍拒绝迟到工具() = runTest {
        val file = File(context.noBackupFilesDir, "memory-deletion-${UUID.randomUUID()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        open().let { db -> try {
            val row = db.memoryDao().create("local", UUID.randomUUID().toString(), "我喜欢夜跑", 1000)
            db.memoryDao().delete("local", row.id, 1, 3000)
        } finally { db.close() } }
        open().let { db -> try {
            assertEquals(MemoryToolResult.Rejected("conflict"), executor(db).execute(turn, "new_call", "memory.upsert", args))
            assertEquals(0, db.memoryDao().count("local"))
            assertTrue(db.memoryDao().search("local", "夜跑").isEmpty())
            assertTrue(db.memoryDao().document("local", 4000).entries.isEmpty())
        } finally { db.close() } }
    }

    @Test fun 历史写入失败时编辑正文同时回滚() = runTest {
        val dao = database.memoryDao()
        val id = UUID.randomUUID().toString()
        val first = dao.create("local", id, "原始偏好", 1000)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_fail_history_insert
            BEFORE INSERT ON agent_memory_revisions WHEN NEW.version = 2
            BEGIN SELECT RAISE(ABORT, '合成历史保存失败'); END""")
        try { dao.update("local", id, 1, "改后偏好", 2000); fail("编辑应回滚") } catch (_: Exception) { }
        assertEquals(first, dao.find("local", id)!!.record())
        assertEquals(1, dao.revisionCount("local", id))
    }

    @Test fun 搜索按字面匹配不把SQL通配符当作全库读取() = runTest {
        val dao = database.memoryDao()
        repeat(7) { dao.create("local", UUID.randomUUID().toString(), "我喜欢RUN$it", 1000L + it) }
        dao.create("other", UUID.randomUUID().toString(), "我喜欢RUN其他", 9999)
        assertEquals(5, dao.search("local", "run").size)
        assertEquals("我喜欢RUN6", dao.search("local", "run").first().content)
        assertTrue(dao.search("local", "%").isEmpty())
        val removed = dao.search("local", "run").first()
        dao.delete("local", removed.id, removed.version, 2000)
        assertFalse(dao.search("local", "run").any { it.id == removed.id })
    }

    @Test fun 重开库保留工具回执且删除后重放不恢复正文() = runTest {
        val file = File(context.noBackupFilesDir, "memory-tool-${UUID.randomUUID()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        val first = open()
        val saved = try { executor(first).execute(turn, "call", "memory.upsert", args) as MemoryToolResult.Saved }
            finally { first.close() }
        val second = open()
        try {
            assertEquals(MemoryToolResult.Saved(saved.memory, true), executor(second).execute(turn, "call", "memory.upsert", args))
            second.memoryDao().delete("local", saved.memory.id, saved.memory.version, 3000)
        } finally { second.close() }
        val third = open()
        try {
            assertEquals(MemoryToolResult.Rejected("conflict"), executor(third).execute(turn, "call", "memory.upsert", args))
            assertEquals(MemoryToolResult.Rejected("conflict"), executor(third).execute(turn, "changed_call", "memory.upsert", args))
            assertEquals(0, third.memoryDao().count("local"))
            assertNull(third.memoryDao().find("local", saved.memory.id)!!.content)
        } finally { third.close() }
    }
}
