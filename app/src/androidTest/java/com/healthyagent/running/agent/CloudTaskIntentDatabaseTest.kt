package com.healthyagent.running.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.CloudTaskIntent
import com.healthyagent.running.agent.cloud.CloudTaskIntentConflict
import com.healthyagent.running.agent.local.AgentDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudTaskIntentDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AgentDatabase
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation_1")
    private fun intent() = CloudTaskIntent.confirm(identity, "测试跑前计划", true, 1000)

    @Before fun setUp() { database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build() }
    @After fun tearDown() { database.close() }

    @Test fun 并发保存与派发只产生一条意图和一个许可() = runTest {
        val dao = database.cloudIntentDao()
        val value = intent()
        val saved = coroutineScope { (0..7).map { async { dao.save(value) } }.awaitAll() }
        assertTrue(saved.all { it == value })
        assertEquals(1, dao.page(identity, 0, 20).size)
        val claims = coroutineScope { (0..7).map { async { dao.claimSubmission(identity, value.clientRequestId, 2000) } }.awaitAll() }
        assertEquals(1, claims.count { it })
        assertTrue(dao.save(value).submissionAttempted)
        assertEquals(0, database.taskDao().count("local"))
    }

    @Test fun 同键任何授权快照变化都拒绝且不覆盖原文() = runTest {
        val dao = database.cloudIntentDao()
        val value = intent()
        dao.save(value)
        val changes = listOf(
            value.copy(prompt = "另一请求"), value.copy(localTaskId = UUID.randomUUID().toString()),
            value.copy(identity = identity.copy(ownerId = "another")),
            value.copy(identity = identity.copy(deviceId = "another")),
            value.copy(identity = identity.copy(credentialRef = "generation_2")),
            value.copy(identity = identity.copy(origin = "https://another.example.com")),
            value.copy(consentedAtMillis = 2000, updatedAtMillis = 2000),
        )
        changes.forEach { changed ->
            var conflict = false
            try { dao.save(changed) } catch (_: CloudTaskIntentConflict) { conflict = true }
            assertTrue(conflict)
        }
        assertEquals(value, dao.find(identity, value.clientRequestId))
    }

    @Test fun 查询派发和待控制操作均按完整身份隔离() = runTest {
        val dao = database.cloudIntentDao()
        val value = intent()
        dao.save(value)
        listOf(identity.copy(ownerId = "other"), identity.copy(deviceId = "other"),
            identity.copy(credentialRef = "generation_2"), identity.copy(origin = "https://other.example.com")).forEach { other ->
            assertNull(dao.find(other, value.clientRequestId))
            assertEquals(emptyList<CloudTaskIntent>(), dao.page(other, 0, 20))
            assertFalse(dao.claimSubmission(other, value.clientRequestId, 2000))
            assertNull(dao.requestControl(other, value.clientRequestId, "cancel", 2000))
        }
        assertEquals(value, dao.find(identity, value.clientRequestId))
    }

    @Test fun 待控制先持久化且撤回不降级重复操作不改时间() = runTest {
        val dao = database.cloudIntentDao()
        val value = intent()
        dao.save(value)
        val cancelled = dao.requestControl(identity, value.clientRequestId, "cancel", 500)!!
        assertEquals("cancel", cancelled.pendingControl)
        assertEquals(1000L, cancelled.updatedAtMillis)
        assertFalse(dao.claimSubmission(identity, value.clientRequestId, 2000))
        assertEquals(cancelled, dao.requestControl(identity, value.clientRequestId, "cancel", 3000))
        val withdrawn = dao.requestControl(identity, value.clientRequestId, "withdraw", 2000)!!
        assertEquals("withdraw", withdrawn.pendingControl)
        assertEquals(withdrawn, dao.requestControl(identity, value.clientRequestId, "cancel", 4000))
        assertEquals(withdrawn, dao.save(value))
        assertFalse(dao.claimSubmission(identity, value.clientRequestId, 5000))
    }

    @Test fun 已尝试提交的待撤回及原始身份重开库不改变() = runTest {
        val file = File(context.noBackupFilesDir, "cloud-intent-${UUID.randomUUID()}.db")
        val value = intent()
        val first = open(file)
        try {
            first.cloudIntentDao().save(value)
            assertTrue(first.cloudIntentDao().claimSubmission(identity, value.clientRequestId, 2000))
            first.cloudIntentDao().requestControl(identity, value.clientRequestId, "withdraw", 3000)
        } finally { first.close() }
        val reopened = open(file)
        try {
            val restored = reopened.cloudIntentDao().find(identity, value.clientRequestId)!!
            assertEquals(value.submission(), restored.submission())
            assertEquals(identity, restored.identity)
            assertEquals(1000L, restored.consentedAtMillis)
            assertEquals("withdraw", restored.pendingControl)
            assertTrue(restored.submissionAttempted)
            assertFalse(reopened.cloudIntentDao().claimSubmission(identity, value.clientRequestId, 4000))
        } finally { reopened.close() }
    }

    @Test fun 知识选择与结构化来源关闭重开库后保留且不再获得发送许可() = runTest {
        val file = File(context.noBackupFilesDir, "knowledge-intent-${UUID.randomUUID()}.db")
        val value = intent().copy(consentVersion = "selected_knowledge_v1",
            knowledgeJson = """{"version":"curated_knowledge_v1","library":"firefly","allowSpoilers":false}""")
        val result = kotlinx.serialization.json.Json.parseToJsonElement("""{"status":"completed","answer":"合成回答","actions":[],"sources":[{"citationId":"official_v1_1","title":"官方资料","url":"https://hsr.hoyoverse.com/zh-tw/character","publishedAt":null,"checkedAtMillis":1000}]}""") as kotlinx.serialization.json.JsonObject
        val first = open(file)
        try {
            first.cloudIntentDao().save(value)
            assertTrue(first.cloudIntentDao().claimSubmission(identity, value.clientRequestId, 2000))
            first.cloudIntentDao().accept(identity, value.clientRequestId,
                com.healthyagent.running.agent.cloud.CloudTaskResponse(UUID.randomUUID().toString(), "completed", null,
                    1000, 31000, 2000, "available", result))
        } finally { first.close() }
        val reopened = open(file)
        try {
            val restored = reopened.cloudIntentDao().find(identity, value.clientRequestId)!!
            assertEquals(value.knowledge(), restored.knowledge())
            assertEquals(value.submission(), restored.submission())
            assertEquals(result.toString(), reopened.cloudIntentDao().receipt(identity, value.clientRequestId)!!.resultJson)
            assertFalse(reopened.cloudIntentDao().claimSubmission(identity, value.clientRequestId, 3000))
        } finally { reopened.close() }
    }

    @Test fun 单条概要磁盘重开保留且同请求不能更换概要() = runTest {
        val file = File(context.noBackupFilesDir, "cloud-run-${UUID.randomUUID()}.db")
        val value = intent().copy(consentVersion = "selected_run_v1",
            runSummaryJson = """{"runId":"run-1","distanceMeters":2000,"durationSeconds":720,"averagePaceSecPerKm":null,"averageHeartRate":null}""")
        val first = open(file)
        try { first.cloudIntentDao().save(value) } finally { first.close() }
        val reopened = open(file)
        try {
            val dao = reopened.cloudIntentDao()
            assertEquals(value.submission(), dao.find(identity, value.clientRequestId)!!.submission())
            assertTrue(runCatching { dao.save(value.copy(runSummaryJson = value.runSummaryJson!!.replace("2000", "2100"))) }.isFailure)
            assertTrue(dao.claimSubmission(identity, value.clientRequestId, 2000))
            assertFalse(dao.claimSubmission(identity, value.clientRequestId, 3000))
        } finally { reopened.close() }
    }

    @Test fun 重复本机任务UUID插入失败不遗留第二条记录() = runTest {
        val dao = database.cloudIntentDao()
        val value = intent()
        dao.save(value)
        var rejected = false
        try { dao.save(value.copy(clientRequestId = UUID.randomUUID().toString())) }
        catch (_: android.database.sqlite.SQLiteConstraintException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf(value), dao.page(identity, 0, 20))
    }

    @Test fun 分页稳定且非法分页状态和时钟不写入() = runTest {
        val dao = database.cloudIntentDao()
        val values = List(3) { intent() }.sortedByDescending { it.clientRequestId }
        values.forEach { dao.save(it) }
        assertEquals(values.take(2), dao.page(identity, 0, 2))
        assertEquals(values.drop(2), dao.page(identity, 2, 2))
        suspend fun rejects(block: suspend () -> Unit) {
            var rejected = false
            try { block() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        rejects { dao.page(identity, -1, 10) }
        rejects { dao.page(identity, 0, 51) }
        rejects { dao.claimSubmission(identity, values[0].clientRequestId, -1) }
        rejects { dao.requestControl(identity, values[0].clientRequestId, "acknowledge", 2000) }
        rejects { dao.save(intent().copy(submissionAttempted = true)) }
        assertEquals(values, dao.page(identity, 0, 20))
    }

    @Test fun 一到五迁移保留旧本机任务消息行动且绝不转换为在线意图() = verifyMigration(1)

    @Test fun 二到五迁移保留旧在线意图与不可重发状态() = verifyMigration(2)

    @Test fun 三到五迁移保留旧在线回执且记忆初始为空() = verifyMigration(3)
    @Test fun 四到五迁移不扩展旧授权或自动发送() = verifyMigration(4)
    @Test fun 五到六迁移保留旧任务记忆与回执且知识默认关闭() = verifyMigration(5)
    @Test fun 六到七迁移保留全部旧数据且不扩展跑步授权() = verifyMigration(6)
    @Test fun 七到八迁移保留旧记录且不猜测会话归属() = verifyMigration(7)

    private fun verifyMigration(version: Int) = runTest {
        val file = File(context.noBackupFilesDir, "cloud-migration-${UUID.randomUUID()}.db")
        // 固定旧版建表语句，不从新 Entity 反推旧 schema。
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE agent_tasks (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, clientRequestId TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_agent_tasks_ownerId_clientRequestId ON agent_tasks(ownerId, clientRequestId)")
            old.execSQL("CREATE INDEX index_agent_tasks_ownerId_createdAtMillis_id ON agent_tasks(ownerId, createdAtMillis, id)")
            old.execSQL("CREATE TABLE agent_messages (taskId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(taskId, sequence), FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_messages_taskId ON agent_messages(taskId)")
            old.execSQL("CREATE TABLE agent_actions (taskId TEXT NOT NULL, sequence INTEGER NOT NULL, kind TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(taskId, sequence), FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_actions_taskId ON agent_actions(taskId)")
            old.execSQL("INSERT INTO agent_tasks VALUES ('legacy-blocked','local','request-old','旧任务','blocked','cloud_not_connected',1000,1000)")
            old.execSQL("INSERT INTO agent_tasks VALUES ('legacy-cancelled','local','request-cancelled','旧取消','cancelled',NULL,1000,2000)")
            old.execSQL("INSERT INTO agent_messages VALUES ('legacy-blocked',0,'user','不许自动上传',1000)")
            old.execSQL("INSERT INTO agent_actions VALUES ('legacy-blocked',0,'saved_locally',1000)")
            old.execSQL("INSERT INTO agent_messages VALUES ('legacy-cancelled',0,'user','已取消的原文',1000)")
            old.execSQL("INSERT INTO agent_actions VALUES ('legacy-cancelled',0,'saved_locally',1000)")
            old.execSQL("INSERT INTO agent_actions VALUES ('legacy-cancelled',1,'cancelled',2000)")
            if (version >= 2) {
                old.execSQL("CREATE TABLE agent_cloud_intents (clientRequestId TEXT NOT NULL PRIMARY KEY, localTaskId TEXT NOT NULL, origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, prompt TEXT NOT NULL, consentVersion TEXT NOT NULL, consentedAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, submissionAttempted INTEGER NOT NULL, pendingControl TEXT)")
                old.execSQL("CREATE UNIQUE INDEX index_agent_cloud_intents_localTaskId ON agent_cloud_intents(localTaskId)")
                old.execSQL("CREATE INDEX index_cloud_intents_identity_time ON agent_cloud_intents(origin, ownerId, deviceId, credentialRef, consentedAtMillis, clientRequestId)")
                old.execSQL("INSERT INTO agent_cloud_intents VALUES ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','https://agent.example.com','owner','device','generation_1','旧在线原文','prompt_only_v1',1000,2000,1,'withdraw')")
            }
            if (version >= 3) {
                old.execSQL("CREATE TABLE agent_cloud_receipts (clientRequestId TEXT NOT NULL PRIMARY KEY, cloudTaskId TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, acceptedAtMillis INTEGER NOT NULL, deadlineAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, resultState TEXT NOT NULL, resultJson TEXT, ackPending INTEGER NOT NULL, confirmedControl TEXT, FOREIGN KEY(clientRequestId) REFERENCES agent_cloud_intents(clientRequestId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE UNIQUE INDEX index_agent_cloud_receipts_cloudTaskId ON agent_cloud_receipts(cloudTaskId)")
                old.execSQL("INSERT INTO agent_cloud_receipts VALUES ('11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','completed',NULL,1000,9000,2000,'available','{\"summary\":\"旧回执\"}',1,NULL)")
            }
            if (version >= 4) {
                old.execSQL("CREATE TABLE agent_memories (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, content TEXT, source TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, version INTEGER NOT NULL, deletedAtMillis INTEGER)")
                old.execSQL("CREATE INDEX index_agent_memories_ownerId_updatedAtMillis_id ON agent_memories(ownerId, updatedAtMillis, id)")
                old.execSQL("INSERT INTO agent_memories VALUES ('44444444-4444-4444-8444-444444444444','local','迁移前的记忆','user_explicit',1000,1000,1,NULL)")
            }
            old.version = version
            if (version >= 5) old.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN memoryRefsJson TEXT DEFAULT NULL")
            if (version >= 6) old.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN knowledgeJson TEXT DEFAULT NULL")
            if (version >= 7) old.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN runSummaryJson TEXT DEFAULT NULL")
        }
        val migrated = open(file)
        try {
            val blocked = migrated.taskDao().detail("local", "legacy-blocked")!!
            assertEquals("blocked", blocked.task.status)
            assertEquals("cloud_not_connected", blocked.task.reason)
            assertEquals("不许自动上传", blocked.messages.single().text)
            assertEquals("saved_locally", blocked.actions.single().kind)
            val cancelled = migrated.taskDao().detail("local", "legacy-cancelled")!!
            assertEquals("cancelled", cancelled.task.status)
            assertEquals(2000L, cancelled.task.updatedAtMillis)
            assertEquals("已取消的原文", cancelled.messages.single().text)
            assertEquals(listOf("saved_locally", "cancelled"), cancelled.actions.map { it.kind })
            assertEquals(2, migrated.taskDao().count("local"))
            if (version == 1) assertEquals(emptyList<CloudTaskIntent>(), migrated.cloudIntentDao().page(identity, 0, 20))
            else {
                val oldIntent = migrated.cloudIntentDao().page(identity, 0, 20).single()
                assertEquals("旧在线原文", oldIntent.prompt)
                assertEquals("prompt_only_v1", oldIntent.consentVersion)
                assertNull(oldIntent.memoryRefsJson)
                assertNull(oldIntent.knowledgeJson)
                assertNull(oldIntent.runSummaryJson)
                assertEquals("withdraw", oldIntent.pendingControl)
                assertTrue(oldIntent.submissionAttempted)
                assertFalse(migrated.cloudIntentDao().claimSubmission(identity, oldIntent.clientRequestId, 3000))
                val receipt = migrated.cloudIntentDao().receipt(identity, oldIntent.clientRequestId)
                if (version >= 3) {
                    assertEquals("{\"summary\":\"旧回执\"}", receipt!!.resultJson)
                    assertTrue(receipt.ackPending)
                } else assertNull(receipt)
            }
            val value = intent()
            assertEquals(value, migrated.cloudIntentDao().save(value))
            assertEquals(value, migrated.cloudIntentDao().find(identity, value.clientRequestId))
            assertEquals(2, migrated.taskDao().count("local"))
            val previousMemories = migrated.memoryDao().page("local", 0, 20)
            assertTrue(migrated.conversationDao().list(identity).isEmpty())
            assertTrue(migrated.cloudIntentDao().page(identity, 0, 20).all { it.conversationId == null && it.conversationJson == null })
            assertEquals(if (version >= 4) 1 else 0, previousMemories.totalItems)
            if (version >= 4) assertEquals("迁移前的记忆", previousMemories.items.single().content)
            val memory = migrated.memoryDao().create("local", UUID.randomUUID().toString(), "偏好夜跑", 3000)
            assertEquals(listOf(memory) + previousMemories.items, migrated.memoryDao().page("local", 0, 20).items)
        } finally { migrated.close() }
    }

    private fun open(file: File) = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath)
        .addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3, AgentDatabase.MIGRATION_3_4, AgentDatabase.MIGRATION_4_5, AgentDatabase.MIGRATION_5_6, AgentDatabase.MIGRATION_6_7, AgentDatabase.MIGRATION_7_8).build()
}
