package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.local.AgentMessageEntity
import com.healthyagent.running.agent.local.AgentTaskEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentTaskDatabaseTest {
    private lateinit var database: AgentDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val requestId = "11111111-1111-4111-8111-111111111111"

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
    }

    @After fun tearDown() { database.close() }

    @Test fun 并发同键只保存一个任务消息和行动() = runTest {
        val dao = database.taskDao()
        val results = coroutineScope {
            (0..7).map { async { dao.saveLocal("local", requestId, "测试跑前计划", UUID.randomUUID().toString(), 1000) } }.awaitAll()
        }
        assertEquals(1, results.map { it.task.id }.toSet().size)
        assertEquals(1, dao.count("local"))
        assertEquals(1, results.first().messages.size)
        assertEquals(1, results.first().actions.size)
    }

    @Test fun 独立文件库关闭重开后任务与取消状态仍可读() = runTest {
        val file = File(context.noBackupFilesDir, "agent-test-${UUID.randomUUID()}.db")
        val first = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        val id = UUID.randomUUID().toString()
        try {
            first.taskDao().saveLocal("local", requestId, "持久化测试", id, 1000)
            first.taskDao().cancel("local", id, 2000)
        } finally { first.close() }
        val reopened = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        try {
            val restored = reopened.taskDao().detail("local", id)!!
            assertEquals("cancelled", restored.task.status)
            assertEquals("持久化测试", restored.messages.single().text)
            assertEquals(2, restored.actions.size)
        } finally {
            reopened.close()
            // 仅删除本测试用例随机生成的数据库，绝不触碰正式库。
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test fun 跨所有者隔离同键可独立保存且重复取消幂等() = runTest {
        val dao = database.taskDao()
        val a = dao.saveLocal("one", requestId, "甲", UUID.randomUUID().toString(), 1000)
        val b = dao.saveLocal("two", requestId, "乙", UUID.randomUUID().toString(), 1000)
        assertTrue(a.task.id != b.task.id)
        assertEquals(null, dao.detail("two", a.task.id))
        assertEquals(null, dao.cancel("two", a.task.id, 2000))
        val cancelled = dao.cancel("one", a.task.id, 2000)
        assertEquals(cancelled, dao.cancel("one", a.task.id, 3000))
        assertEquals(listOf(b.task.id), dao.page("two", 0, 20).items.map { it.id })
    }

    @Test fun 事务中重复消息主键失败时不会遗留半个任务() = runTest {
        val id = UUID.randomUUID().toString()
        var rejected = false
        try {
            database.withTransaction {
                database.taskDao().insertTask(AgentTaskEntity(id, "local", requestId, "回滚", "blocked", "cloud_not_connected", 1000, 1000))
                val message = AgentMessageEntity(id, 0, "user", "回滚", 1000)
                database.taskDao().insertMessage(message)
                database.taskDao().insertMessage(message)
            }
        } catch (_: android.database.sqlite.SQLiteConstraintException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, database.taskDao().count("local"))
    }
}
