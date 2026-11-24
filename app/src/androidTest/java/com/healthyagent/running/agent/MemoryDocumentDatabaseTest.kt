package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.*
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 仅创建隔离的合成数据库，不读取手机用户记忆。 */
@RunWith(AndroidJUnit4::class)
class MemoryDocumentDatabaseTest {
    private lateinit var database: AgentDatabase
    private val turn = MemoryToolTurn("local", "a".repeat(64), "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", 1000, "我喜欢周三测试")
    private val grant = MemoryToolGrant(turn.scope, turn.grantGeneration)
    private val args = buildJsonObject {
        put("category", "preference"); put("evidence", turn.userText); put("target", JsonNull)
    }
    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
    }
    @After fun close() { database.close() }

    @Test fun 完整文档不受分页上限限制且隔离用户与删除内容() = runBlocking {
        val dao = database.memoryDao()
        val ids = List(121) { UUID.randomUUID().toString() }.sortedDescending()
        ids.forEach { dao.create("local", it, "合成偏好 $it", 1000) }
        val deleted = UUID.randomUUID().toString()
        dao.create("local", deleted, "已删除合成内容", 1000)
        dao.delete("local", deleted, 1, 2000)
        dao.create("other", UUID.randomUUID().toString(), "其他用户合成内容", 1000)
        val document = dao.document("local", 3000)
        assertEquals(3000L, document.generatedAtMillis)
        assertEquals(ids, document.entries.map { it.memory.id })
        assertTrue(document.entries.all { it.changeSource == "manual" && it.category == null && it.sourceTurnId == null })
        val markdown = StringBuilder().also { document.writeMarkdown(it) }.toString()
        assertTrue(markdown.contains("共 121 条"))
        assertFalse(markdown.contains("已删除合成内容"))
        assertFalse(markdown.contains("其他用户合成内容"))
        assertEquals(121, dao.count("local"))
        assertEquals(1, dao.document("other", 3000).entries.size)
        assertTrue(dao.document("absent", 3000).entries.isEmpty())
    }

    @Test fun 分类来源只取当前版本原始回合而不借用其他回执和旧版本() = runBlocking {
        val dao = database.memoryDao()
        val executor = MemoryToolExecutor(dao, { grant }, { 2000 })
        val first = executor.execute(turn, "save", "memory.upsert", args) as MemoryToolResult.Saved
        // 相同正文在另一个回合去重，不应覆盖原版本的来源消息。
        val laterTurn = turn.copy(turnId = "33333333-3333-4333-8333-333333333333")
        assertTrue(executor.execute(laterTurn, "dedupe", "memory.upsert", args) is MemoryToolResult.Saved)
        val agentEntry = dao.document("local", 3000).entries.single()
        assertEquals(first.memory, agentEntry.memory)
        assertEquals("agent", agentEntry.changeSource)
        assertEquals(turn.turnId, agentEntry.sourceTurnId)
        assertEquals("preference", agentEntry.category)
        dao.update("local", first.memory.id, 1, "手动更正合成内容", 4000)
        val manualEntry = dao.document("local", 5000).entries.single()
        assertEquals(2, manualEntry.memory.version)
        assertEquals("manual", manualEntry.changeSource)
        assertNull(manualEntry.category)
        assertNull(manualEntry.sourceTurnId)
        dao.restore("local", first.memory.id, 2, 1, 6000)
        val restoredEntry = dao.document("local", 7000).entries.single()
        assertEquals(3, restoredEntry.memory.version)
        assertEquals("restored", restoredEntry.changeSource)
        assertNull(restoredEntry.category)
        assertNull(restoredEntry.sourceTurnId)
    }

    @Test fun 旧记录不伪造来源且后续变更不修改既有只读快照() = runBlocking {
        val dao = database.memoryDao()
        val id = UUID.randomUUID().toString()
        dao.insert(MemoryEntity(id, "local", "旧版合成正文", "user_explicit", 1000, 2000, 4))
        val before = dao.document("local", 3000)
        val entry = before.entries.single()
        assertNull(entry.changeSource)
        assertNull(entry.sourceTurnId)
        assertNull(entry.category)
        dao.update("local", id, 4, "新版合成正文", 4000)
        assertEquals("新版合成正文", dao.document("local", 5000).entries.single().memory.content)
        dao.delete("local", id, 5, 6000)
        assertTrue(dao.document("local", 7000).entries.isEmpty())
        assertEquals("旧版合成正文", before.entries.single().memory.content)
        assertEquals(4, before.entries.single().memory.version)
        assertEquals(0, dao.revisionCount("local", id))
        assertNull(dao.find("local", id)!!.content)
    }

    @Test fun 并发编辑时每份文档的正文和来源来自同一已提交版本() = runBlocking {
        val dao = database.memoryDao()
        val first = (MemoryToolExecutor(dao, { grant }, { 2000 }).execute(turn, "save", "memory.upsert", args) as MemoryToolResult.Saved).memory
        coroutineScope {
            val writer = async {
                for (version in 1..30) dao.update("local", first.id, version, "合成版本 ${version + 1}", 3000L + version)
            }
            val reader = async {
                repeat(50) {
                    val entry = dao.document("local", 5000).entries.single()
                    if (entry.memory.version == 1) {
                        assertEquals(turn.userText, entry.memory.content)
                        assertEquals("agent", entry.changeSource)
                        assertEquals(turn.turnId, entry.sourceTurnId)
                        assertEquals("preference", entry.category)
                    } else {
                        assertEquals("合成版本 ${entry.memory.version}", entry.memory.content)
                        assertEquals("manual", entry.changeSource)
                        assertNull(entry.sourceTurnId)
                        assertNull(entry.category)
                    }
                }
            }
            writer.await(); reader.await()
        }
        assertEquals(31, dao.document("local", 6000).entries.single().memory.version)
    }
}
