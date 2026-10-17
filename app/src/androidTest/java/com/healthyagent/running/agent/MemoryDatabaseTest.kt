package com.healthyagent.running.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryConflict
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.agent.memory.MemoryRef
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.coordinator.CommandOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AgentDatabase
    private val id = "11111111-1111-4111-8111-111111111111"

    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build() }
    @After fun close() { database.close() }

    @Test fun 创建编辑删除重试幂等且旧请求不能复活正文() = runBlocking {
        val dao = database.memoryDao()
        val first = dao.create("local", id, " 夜跑 ", 1000)
        assertEquals("夜跑", first.content)
        assertEquals(first, dao.create("local", id, "夜跑", 2000))
        val updated = dao.update("local", id, 1, "清晨跑", 3000)!!
        assertEquals(2, updated.version)
        assertEquals(updated, dao.update("local", id, 1, "清晨跑", 4000))
        assertTrue(runCatching { dao.update("local", id, 1, "旧修改", 4000) }.exceptionOrNull() is MemoryConflict)
        val deleted = dao.delete("local", id, 2, 5000)
        assertEquals(deleted, dao.delete("local", id, 2, 6000))
        assertEquals(0, dao.page("local", 0, 20).totalItems)
        assertNull(dao.find("local", id)!!.content)
        assertEquals(5000L, dao.find("local", id)!!.deletedAtMillis)
        assertTrue(runCatching { dao.create("local", id, "夜跑", 6000) }.exceptionOrNull() is MemoryConflict)
        assertTrue(runCatching { dao.update("local", id, 2, "旧修改", 6000) }.exceptionOrNull() is MemoryConflict)
    }

    @Test fun 所有读取修改删除均隔离owner() = runBlocking {
        val dao = database.memoryDao()
        val first = dao.create("local", id, "只属于本机用户", 1000)
        assertNull(dao.find("other", id))
        assertNull(dao.update("other", id, 1, "越权修改", 2000))
        assertNull(dao.delete("other", id, 1, 2000))
        assertEquals(0, dao.page("other", 0, 20).totalItems)
        assertTrue(runCatching { dao.create("other", id, "覆盖", 2000) }.isFailure)
        assertEquals(first, dao.page("local", 0, 20).items.single())
    }

    @Test fun 同版本并发编辑只成功一个且时钟回拨不倒退() = runBlocking {
        val dao = database.memoryDao()
        dao.create("local", id, "最初", 3000)
        val results = coroutineScope {
            listOf("夜跑", "晨跑").map { text -> async { runCatching { dao.update("local", id, 1, text, 1000) } } }.map { it.await() }
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(2, dao.find("local", id)!!.version)
        assertEquals(3000L, dao.find("local", id)!!.updatedAtMillis)
        dao.delete("local", id, 2, 500)
        assertEquals(3000L, dao.find("local", id)!!.deletedAtMillis)
    }

    @Test fun 编辑和删除竞争不能让删除内容复活() = runBlocking {
        val dao = database.memoryDao()
        dao.create("local", id, "最初", 1000)
        val results = coroutineScope {
            val edit = async { runCatching { dao.update("local", id, 1, "新内容", 2000) } }
            val delete = async { runCatching { dao.delete("local", id, 1, 2000) } }
            listOf(edit.await().isSuccess, delete.await().isSuccess)
        }
        assertEquals(1, results.count { it })
        val current = dao.find("local", id)!!
        if (current.deletedAtMillis == null) dao.delete("local", id, current.version, 3000)
        assertNull(dao.find("local", id)!!.content)
        assertTrue(runCatching { dao.update("local", id, 1, "迟到内容", 4000) }.isFailure)
    }

    @Test fun 分页排序稳定且不含删除记录() = runBlocking {
        val dao = database.memoryDao()
        val ids = List(23) { UUID.randomUUID().toString() }.sortedDescending()
        ids.forEach { dao.create("local", it, "同秒记录", 1000) }
        assertEquals(ids.take(20), dao.page("local", 0, 20).items.map { it.id })
        assertEquals(ids.drop(20), dao.page("local", 20, 20).items.map { it.id })
        dao.delete("local", ids.first(), 1, 2000)
        assertEquals(22, dao.page("local", 0, 20).totalItems)
    }

    @Test fun 重开文件库保留记忆与删除标记() = runBlocking {
        val file = File(context.noBackupFilesDir, "memory-test-${UUID.randomUUID()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        val keptId = UUID.randomUUID().toString()
        open().let { db -> try {
            db.memoryDao().create("local", keptId, "我喜欢夜跑", 1000)
            db.memoryDao().create("local", id, "待删除", 1000)
            db.memoryDao().delete("local", id, 1, 2000)
        } finally { db.close() } }
        open().let { db -> try {
            assertEquals(keptId, db.memoryDao().page("local", 0, 20).items.single().id)
            assertNull(db.memoryDao().find("local", id)!!.content)
            assertTrue(runCatching { db.memoryDao().create("local", id, "待删除", 3000) }.isFailure)
        } finally { db.close() } }
    }

    @Test fun 服务拒绝未确认删除并返回可解析结果() = runBlocking {
        val service = MemoryService(database.memoryDao(), now = { 1000 })
        fun body(text: String) = BridgeCodec.json.parseToJsonElement(text).jsonObject
        val created = service.handle("memory.create", body("""{"memoryId":"$id","content":"偏好夜跑"}"""))
        assertTrue(created is CommandOutcome.AcceptedWithData)
        val rejected = service.handle("memory.delete", body("""{"memoryId":"$id","version":1,"confirmed":false}"""))
        assertTrue(rejected is CommandOutcome.Rejected)
        assertEquals(1, database.memoryDao().count("local"))
        assertTrue(service.handle("memory.delete", body("""{"memoryId":"$id","version":1,"confirmed":true}""")) is CommandOutcome.AcceptedWithData)
        val conflict = service.handle("memory.update", body("""{"memoryId":"$id","version":1,"content":"旧修改"}"""))
        assertTrue(conflict is CommandOutcome.Rejected)
        assertEquals(0, database.memoryDao().count("local"))
    }

    @Test fun 预览逐条重读原生正文且拒绝过期删除和其他归属() = runBlocking {
        val dao = database.memoryDao()
        val otherId = UUID.randomUUID().toString()
        val first = dao.create("local", id, "偏好夜跑", 1000)
        dao.create("other", otherId, "其他归属", 1000)
        assertEquals(listOf(first), dao.selected("local", listOf(MemoryRef(id, 1))).items)
        assertEquals("local_preview", dao.selected("local", listOf(MemoryRef(id, 1))).scope)
        assertTrue(runCatching { dao.selected("local", listOf(MemoryRef(id, 1), MemoryRef(otherId, 1))) }.exceptionOrNull() is MemoryConflict)
        val latest = dao.update("local", id, 1, "更喜欢晨跑", 2000)!!
        assertTrue(runCatching { dao.selected("local", listOf(MemoryRef(id, 1))) }.exceptionOrNull() is MemoryConflict)
        assertEquals(listOf(latest), dao.selected("local", listOf(MemoryRef(id, 2))).items)
        dao.delete("local", id, 2, 3000)
        for (version in listOf(1, 2, 3)) assertTrue(runCatching { dao.selected("local", listOf(MemoryRef(id, version))) }.exceptionOrNull() is MemoryConflict)
        assertNull(dao.find("local", id)!!.content)
    }

    @Test fun 预览保持选择顺序且不混入未选择记录() = runBlocking {
        val dao = database.memoryDao()
        val ids = List(6) { UUID.randomUUID().toString() }
        ids.forEachIndexed { index, memoryId -> dao.create("local", memoryId, "偏好$index", 1000) }
        val selected = ids.take(5).reversed().map { MemoryRef(it, 1) }
        assertEquals(selected.map { it.id }, dao.selected("local", selected).items.map { it.id })
        assertTrue(runCatching { dao.selected("local", ids.map { MemoryRef(it, 1) }) }.isFailure)
        assertTrue(runCatching { dao.selected("local", listOf(selected[0], selected[0])) }.isFailure)
        val service = MemoryService(dao)
        val accepted = service.handle("memory.preview", BridgeCodec.json.parseToJsonElement("""{"memoryRefs":[{"id":"${ids[0]}","version":1}]}""").jsonObject)
        assertTrue(accepted is CommandOutcome.AcceptedWithData)
        assertEquals(6, dao.count("local"))
    }
}
