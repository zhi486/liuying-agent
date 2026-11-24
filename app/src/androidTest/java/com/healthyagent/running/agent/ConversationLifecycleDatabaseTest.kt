package com.healthyagent.running.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 仅使用隔离库与合成内容，不连接模型、插件或用户数据库。 */
@RunWith(AndroidJUnit4::class)
class ConversationLifecycleDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation_1")
    private lateinit var database: AgentDatabase
    private val cost = buildJsonObject {
        put("currency", "USD"); put("estimatedMicros", 120); put("inputTokens", 12); put("outputTokens", 3)
        put("usageComplete", true); put("rateStatus", "current")
    }

    @Before fun setUp() { database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build() }
    @After fun tearDown() { database.close() }

    private suspend fun seed(id: String?, owner: CloudTaskIdentity = identity, at: Long = 1000): CloudTaskIntent {
        val intent = CloudTaskIntent.confirm(owner, "合成问题正文", true, at).let { original ->
            if (id == null) original else original.copy(conversationId = id, conversationJson = buildJsonObject {
                put("version", "conversation_v1"); put("id", id); put("approved", true); put("messages", JsonArray(emptyList()))
            }.toString())
        }
        database.cloudIntentDao().save(intent)
        return intent
    }

    private suspend fun complete(intent: CloudTaskIntent, withCost: Boolean = true) {
        assertTrue(database.cloudIntentDao().claimSubmission(intent.identity, intent.clientRequestId, intent.consentedAtMillis))
        database.cloudIntentDao().accept(intent.identity, intent.clientRequestId, CloudTaskResponse(
            UUID.randomUUID().toString(), "completed", null, intent.consentedAtMillis, intent.consentedAtMillis + 30000,
            intent.consentedAtMillis, "available", buildJsonObject {
                put("status", "completed"); put("answer", "合成回答正文"); put("actions", JsonArray(emptyList()))
                if (withCost) put("cost", cost)
            }))
    }

    private fun count(table: String): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    @Test fun 归档恢复保留正文并允许只读选中且不新建空会话() = runBlocking {
        val conversation = database.conversationDao().create(identity, 1000)
        val intent = seed(conversation.id); complete(intent)
        val original = database.cloudIntentDao().receipt(identity, intent.clientRequestId)
        val dao = database.conversationLifecycleDao()
        dao.archive(identity, conversation.id, true, 2000)
        val archived = database.conversationDao().list(identity).single()
        assertEquals(2000L, archived.archivedAtMillis)
        assertFalse(archived.selected)
        assertFalse(dao.legacySelected(identity))
        var archivedRejected = false
        try { seed(conversation.id) } catch (_: IllegalArgumentException) { archivedRejected = true }
        assertTrue(archivedRejected)
        database.conversationDao().select(identity, conversation.id)
        assertTrue(database.conversationDao().find(identity, conversation.id)!!.selected)
        assertEquals(intent, database.cloudIntentDao().find(identity, intent.clientRequestId)!!.copy(submissionAttempted = false))
        assertEquals(original, database.cloudIntentDao().receipt(identity, intent.clientRequestId))
        dao.archive(identity, conversation.id, false, 3000)
        assertNull(database.conversationDao().find(identity, conversation.id)!!.archivedAtMillis)
        assertEquals(1, database.conversationDao().list(identity).size)
        assertTrue(dao.pending(identity).isEmpty())
        assertTrue(dao.retainedUsage(identity).isEmpty())
        dao.delete(identity, conversation.id, 4000)
        assertFalse(dao.legacySelected(identity))
        assertTrue(database.conversationDao().list(identity).isEmpty())
        var deletedRejected = false
        try { seed(conversation.id) } catch (_: IllegalArgumentException) { deletedRejected = true }
        assertTrue(deletedRejected)
        dao.selectLegacy(identity, true)
        assertTrue(dao.legacySelected(identity))
    }

    @Test fun 删除覆盖超过十条的全部回合和依赖但保留独立记忆修订工具凭据及本机任务() = runBlocking {
        val conversation = database.conversationDao().create(identity, 1000)
        val memory = database.memoryDao().create("local", UUID.randomUUID().toString(), "合成独立记忆", 1000)
        val intents = (0..24).map { index -> seed(conversation.id, at = 1000L + index).also { complete(it, index != 0) } }
        val neverSent = seed(conversation.id, at = 1100)
        val first = intents.first()
        database.memoryContextDependenciesDao().save(MemoryContextState(setOf(MemoryRef(memory.id, 1))).row(first.clientRequestId))
        database.memoryToolBindingDao().insert(MemoryToolBinding(first.clientRequestId, "合成哈希", "合成范围", UUID.randomUUID().toString()))
        val operation = UUID.randomUUID().toString()
        database.memoryDao().insertToolReceipt(MemoryToolReceipt(operation, "local", "合成请求哈希", "合成内容哈希",
            first.clientRequestId, "preference", memory.id, 1))
        val localId = UUID.randomUUID().toString()
        val local = database.taskDao().saveLocal("local", UUID.randomUUID().toString(), "合成本机任务", localId, 1000)
        val survivor = database.conversationDao().create(identity, 1200)
        val survivorIntent = seed(survivor.id)
        val dao = database.conversationLifecycleDao()
        val removed = dao.delete(identity, conversation.id, 2000)
        assertEquals((intents + neverSent).map { it.clientRequestId }.toSet(), removed.map { it.requestId }.toSet())
        assertEquals(25, dao.pending(identity).size)
        assertFalse(removed.single { it.requestId == neverSent.clientRequestId }.pending)
        assertNull(database.conversationDao().find(identity, conversation.id))
        assertEquals(listOf(survivor.id), database.conversationDao().list(identity).map { it.id })
        assertTrue(database.cloudIntentDao().conversationPage(identity, conversation.id).isEmpty())
        assertNull(database.cloudIntentDao().receipt(identity, first.clientRequestId))
        assertNull(database.memoryContextDependenciesDao().find(first.clientRequestId))
        assertNull(database.memoryToolBindingDao().find(first.clientRequestId))
        assertEquals(memory, database.memoryDao().find("local", memory.id)!!.record())
        assertEquals(1, database.memoryDao().history("local", memory.id, 0, 20)!!.totalItems)
        assertNotNull(database.memoryDao().toolReceipt("local", operation))
        assertEquals(local, database.taskDao().detail("local", localId))
        assertEquals(survivorIntent, database.cloudIntentDao().find(identity, survivorIntent.clientRequestId))
        val usage = dao.retainedUsage(identity)
        assertEquals(25, usage.size)
        assertNull(usage.single { it.requestId == first.clientRequestId }.costJson)
        assertTrue(usage.filter { it.requestId != first.clientRequestId }.all { Json.parseToJsonElement(it.costJson!!) == cost })
        assertEquals(setOf("origin", "ownerId", "deviceId", "credentialRef", "requestId", "atMillis", "pending"), columns("agent_conversation_cleanup"))
        assertEquals(setOf("origin", "ownerId", "deviceId", "credentialRef", "requestId", "atMillis", "costJson"), columns("agent_conversation_retained_usage"))
        assertTrue(dao.delete(identity, conversation.id, 3000).isEmpty())
        assertEquals(25, dao.retainedUsage(identity).size)
    }

    @Test fun 旧版历史按完整身份隔离且删除后新请求仍独立不继承归档() = runBlocking {
        val others = listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"),
            identity.copy(deviceId = "other"), identity.copy(credentialRef = "generation_2"))
        val original = seed(null); complete(original)
        val otherIntents = others.map { seed(null, it) }
        val dao = database.conversationLifecycleDao()
        assertTrue(dao.legacyAvailable(identity))
        dao.archive(identity, null, true, 2000)
        assertTrue(dao.legacyArchived(identity))
        assertFalse(dao.legacySelected(identity))
        others.forEach { assertTrue(dao.legacyAvailable(it)); assertFalse(dao.legacyArchived(it)) }
        assertEquals(original.clientRequestId, database.cloudIntentDao().conversationPage(identity, null).single().clientRequestId)
        dao.archive(identity, null, false, 2100)
        assertFalse(dao.legacyArchived(identity))
        dao.archive(identity, null, true, 2200)
        dao.delete(identity, null, 2300)
        assertFalse(dao.legacyAvailable(identity)); assertFalse(dao.legacyArchived(identity))
        assertFalse(dao.legacySelected(identity))
        otherIntents.forEach { assertEquals(it, database.cloudIntentDao().find(it.identity, it.clientRequestId)) }
        val next = seed(null, at = 2400)
        assertTrue(dao.legacyAvailable(identity)); assertFalse(dao.legacyArchived(identity))
        assertNull(next.conversationId); assertNull(next.conversationJson)
        assertTrue(database.conversationDao().list(identity).isEmpty())
        assertEquals(listOf(original.clientRequestId), dao.pending(identity).map { it.requestId })
        others.forEach { assertTrue(dao.pending(it).isEmpty()); assertTrue(dao.retainedUsage(it).isEmpty()) }
    }

    @Test fun 其他身份不能归档删除或完成本身份的清理任务() = runBlocking {
        val conversation = database.conversationDao().create(identity, 1000)
        val intent = seed(conversation.id); complete(intent)
        val dao = database.conversationLifecycleDao()
        val others = listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"),
            identity.copy(deviceId = "other"), identity.copy(credentialRef = "generation_2"))
        for (other in others) {
            try { dao.archive(other, conversation.id, true, 2000); fail("不得归档其他身份") } catch (_: IllegalArgumentException) { }
            try { dao.delete(other, conversation.id, 2000); fail("不得删除其他身份") } catch (_: IllegalArgumentException) { }
        }
        assertNotNull(database.conversationDao().find(identity, conversation.id))
        dao.delete(identity, conversation.id, 2000)
        for (other in others) dao.complete(other, intent.clientRequestId)
        assertEquals(1, dao.pending(identity).size)
    }

    @Test fun 正文删除失败回滚清理标记用量和会话元数据() = runBlocking {
        val conversation = database.conversationDao().create(identity, 1000)
        val intent = seed(conversation.id); complete(intent)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_delete_failure BEFORE DELETE ON agent_cloud_intents
            BEGIN SELECT RAISE(ABORT, '合成删除失败'); END""")
        var failed = false
        try { database.conversationLifecycleDao().delete(identity, conversation.id, 2000) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertNotNull(database.conversationDao().find(identity, conversation.id))
        assertNotNull(database.cloudIntentDao().find(identity, intent.clientRequestId))
        assertNotNull(database.cloudIntentDao().receipt(identity, intent.clientRequestId))
        assertEquals(0, count("agent_conversation_cleanup")); assertEquals(0, count("agent_conversation_retained_usage"))
    }

    @Test fun 待办重开及重复完成幂等且迟到保存不复活旧版请求() = runBlocking {
        database.close()
        val file = File(context.noBackupFilesDir, "conversation-lifecycle-${UUID.randomUUID()}.db")
        database = open(file)
        val intent = seed(null); complete(intent)
        val neverSent = seed(null)
        database.conversationLifecycleDao().delete(identity, null, 2000)
        database.close(); database = open(file)
        val dao = database.conversationLifecycleDao()
        assertEquals(listOf(intent.clientRequestId), dao.pending(identity).map { it.requestId })
        dao.complete(identity, intent.clientRequestId); dao.complete(identity, intent.clientRequestId)
        database.close(); database = open(file)
        assertTrue(database.conversationLifecycleDao().pending(identity).isEmpty())
        assertEquals(2, count("agent_conversation_cleanup"))
        for (stale in listOf(intent, neverSent)) {
            var rejected = false
            try { database.cloudIntentDao().save(stale) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertNull(database.cloudIntentDao().find(identity, stale.clientRequestId))
        }
        assertEquals(1, database.conversationLifecycleDao().retainedUsage(identity).size)
        assertTrue(database.conversationDao().list(identity).isEmpty())
    }

    @Test fun 批量正文删除释放占用页面且等量重写不重复增长() = runBlocking {
        database.close()
        val file = File(context.noBackupFilesDir, "conversation-space-${UUID.randomUUID()}.db")
        database = open(file)
        fun pragma(name: String) = database.openHelper.readableDatabase.query("PRAGMA $name").use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        val conversation = database.conversationDao().create(identity, 1000)
        val content = "合成空间测试".repeat(300)
        repeat(40) { index ->
            val intent = CloudTaskIntent.confirm(identity, content, true, 1000L + index).copy(
                conversationId = conversation.id, conversationJson = buildJsonObject {
                    put("version", "conversation_v1"); put("id", conversation.id); put("approved", true)
                    put("messages", JsonArray(emptyList()))
                }.toString())
            database.cloudIntentDao().save(intent)
            complete(intent)
        }
        val autoVacuum = pragma("auto_vacuum")
        val peak = pragma("page_count")
        val before = pragma("freelist_count")
        database.conversationLifecycleDao().delete(identity, conversation.id, 2000)
        val afterDelete = pragma("page_count")
        val released = pragma("freelist_count")
        val deleteSamples = "auto_vacuum=$autoVacuum；删除前 page_count=$peak, freelist_count=$before；" +
            "删除后 page_count=$afterDelete, freelist_count=$released"
        assertEquals("正文必须删除：$deleteSamples", 0, count("agent_cloud_intents"))
        assertEquals("回执必须级联删除：$deleteSamples", 0, count("agent_cloud_receipts"))
        // NONE/INCREMENTAL 可留下空闲页，FULL 可直接收回文件页；两者都必须减少实际占用页。
        val occupiedAfterDelete = afterDelete - released
        assertTrue("批量正文必须释放可复用页或收回文件页：$deleteSamples", occupiedAfterDelete < peak - before)
        val next = database.conversationDao().create(identity, 3000)
        repeat(40) { index ->
            val intent = CloudTaskIntent.confirm(identity, content, true, 3000L + index).copy(
                conversationId = next.id, conversationJson = buildJsonObject {
                    put("version", "conversation_v1"); put("id", next.id); put("approved", true)
                    put("messages", JsonArray(emptyList()))
                }.toString())
            database.cloudIntentDao().save(intent)
        }
        val afterRewrite = pragma("page_count")
        val freeAfterRewrite = pragma("freelist_count")
        val rewriteSamples = "$deleteSamples；重写后 page_count=$afterRewrite, freelist_count=$freeAfterRewrite"
        assertTrue("等量重写必须重新使用页面容量：$rewriteSamples", afterRewrite - freeAfterRewrite > occupiedAfterDelete)
        assertTrue("复用同等正文不应按原体积重复增长：$rewriteSamples", afterRewrite <= peak + 8)
        assertEquals("最小用量必须保留：$rewriteSamples", 40, database.conversationLifecycleDao().retainedUsage(identity).size)
    }

    private fun columns(table: String): Set<String> = database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use {
        buildSet { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name"))) }
    }

    private fun open(file: File) = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath)
        .addMigrations(AgentDatabase.MIGRATION_12_13, AgentDatabase.MIGRATION_13_14, AgentDatabase.MIGRATION_14_15, AgentDatabase.MIGRATION_15_16).build()

    @Test fun 十二到十三增量迁移保留会话原文用量与独立数据且归档默认关闭() = runBlocking {
        database.close()
        val file = File(context.noBackupFilesDir, "conversation-migration-${UUID.randomUUID()}.db")
        val conversation = UUID.randomUUID().toString()
        val request = UUID.randomUUID().toString()
        val local = UUID.randomUUID().toString()
        val cloud = UUID.randomUUID().toString()
        // 固定 v12 表结构，不从新增实体反推旧库，Room 打开时校验迁移后的所有表。
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE agent_tasks (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, clientRequestId TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_agent_tasks_ownerId_clientRequestId ON agent_tasks(ownerId, clientRequestId)")
            old.execSQL("CREATE INDEX index_agent_tasks_ownerId_createdAtMillis_id ON agent_tasks(ownerId, createdAtMillis, id)")
            old.execSQL("CREATE TABLE agent_messages (taskId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(taskId, sequence), FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_messages_taskId ON agent_messages(taskId)")
            old.execSQL("CREATE TABLE agent_actions (taskId TEXT NOT NULL, sequence INTEGER NOT NULL, kind TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(taskId, sequence), FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_actions_taskId ON agent_actions(taskId)")
            old.execSQL("CREATE TABLE agent_conversations (id TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, title TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, selected INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE agent_cloud_intents (clientRequestId TEXT NOT NULL PRIMARY KEY, localTaskId TEXT NOT NULL, origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, prompt TEXT NOT NULL, consentVersion TEXT NOT NULL, consentedAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, submissionAttempted INTEGER NOT NULL, pendingControl TEXT, memoryRefsJson TEXT DEFAULT NULL, knowledgeJson TEXT DEFAULT NULL, runSummaryJson TEXT DEFAULT NULL, conversationId TEXT DEFAULT NULL, conversationJson TEXT DEFAULT NULL, mcpToolsJson TEXT DEFAULT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_agent_cloud_intents_localTaskId ON agent_cloud_intents(localTaskId)")
            old.execSQL("CREATE INDEX index_cloud_intents_identity_time ON agent_cloud_intents(origin, ownerId, deviceId, credentialRef, consentedAtMillis, clientRequestId)")
            old.execSQL("CREATE TABLE agent_cloud_receipts (clientRequestId TEXT NOT NULL PRIMARY KEY, cloudTaskId TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, acceptedAtMillis INTEGER NOT NULL, deadlineAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, resultState TEXT NOT NULL, resultJson TEXT, ackPending INTEGER NOT NULL, confirmedControl TEXT, FOREIGN KEY(clientRequestId) REFERENCES agent_cloud_intents(clientRequestId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE UNIQUE INDEX index_agent_cloud_receipts_cloudTaskId ON agent_cloud_receipts(cloudTaskId)")
            old.execSQL("CREATE TABLE agent_memories (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, content TEXT, source TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, version INTEGER NOT NULL, deletedAtMillis INTEGER)")
            old.execSQL("CREATE INDEX index_agent_memories_ownerId_updatedAtMillis_id ON agent_memories(ownerId, updatedAtMillis, id)")
            old.execSQL("CREATE TABLE agent_memory_revisions (ownerId TEXT NOT NULL, memoryId TEXT NOT NULL, version INTEGER NOT NULL, content TEXT NOT NULL, changedAtMillis INTEGER NOT NULL, changeSource TEXT NOT NULL, turnId TEXT, PRIMARY KEY(ownerId, memoryId, version), FOREIGN KEY(memoryId) REFERENCES agent_memories(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_memory_revisions_memoryId ON agent_memory_revisions(memoryId)")
            old.execSQL("CREATE TABLE agent_memory_tool_receipts (operationId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, requestHash TEXT NOT NULL, contentHash TEXT NOT NULL, turnId TEXT NOT NULL, category TEXT NOT NULL, memoryId TEXT NOT NULL, memoryVersion INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE agent_memory_tool_bindings (requestId TEXT NOT NULL PRIMARY KEY, sourceHash TEXT NOT NULL, scope TEXT NOT NULL, grantGeneration TEXT NOT NULL, factsAllowed INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(requestId) REFERENCES agent_cloud_intents(clientRequestId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE TABLE agent_memory_context_dependencies (requestId TEXT NOT NULL PRIMARY KEY, referencesJson TEXT NOT NULL, exhausted INTEGER NOT NULL, FOREIGN KEY(requestId) REFERENCES agent_cloud_intents(clientRequestId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE TABLE agent_memory_deletion_markers (ownerId TEXT NOT NULL, contentHash TEXT NOT NULL, deletedAtMillis INTEGER NOT NULL, PRIMARY KEY(ownerId, contentHash))")
            old.execSQL("INSERT INTO agent_tasks VALUES ('local-synthetic','local','local-request','合成本机标题','blocked','cloud_not_connected',1000,1000)")
            old.execSQL("INSERT INTO agent_messages VALUES ('local-synthetic',0,'user','合成本机正文',1000)")
            old.execSQL("INSERT INTO agent_actions VALUES ('local-synthetic',0,'saved_locally',1000)")
            old.execSQL("INSERT INTO agent_conversations VALUES (?,?,?,?,?,'合成旧会话',1000,1000,1)", arrayOf(conversation, identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef))
            old.execSQL("INSERT INTO agent_cloud_intents (clientRequestId,localTaskId,origin,ownerId,deviceId,credentialRef,prompt,consentVersion,consentedAtMillis,updatedAtMillis,submissionAttempted,pendingControl,conversationId,conversationJson) VALUES (?,?,?,?,?,?,'合成旧问题','prompt_only_v1',1000,1000,1,NULL,?,?)",
                arrayOf(request, local, identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, conversation,
                    """{"version":"conversation_v1","id":"$conversation","approved":true,"messages":[]}"""))
            old.execSQL("INSERT INTO agent_cloud_receipts VALUES (?,?,'completed',NULL,1000,31000,1000,'available',?,1,NULL)",
                arrayOf(request, cloud, """{"status":"completed","answer":"合成旧回答","actions":[],"cost":$cost}"""))
            old.execSQL("INSERT INTO agent_memories VALUES ('synthetic-memory','local','合成旧记忆','user_explicit',1000,1000,1,NULL)")
            old.execSQL("INSERT INTO agent_memory_revisions VALUES ('local','synthetic-memory',1,'合成旧记忆',1000,'legacy',NULL)")
            old.execSQL("INSERT INTO agent_memory_tool_bindings VALUES (?,'synthetic-hash','synthetic-scope','synthetic-generation',0)", arrayOf(request))
            old.execSQL("INSERT INTO agent_memory_context_dependencies VALUES (?,'[]',0)", arrayOf(request))
            old.version = 12
        }
        database = open(file)
        val migrated = database.conversationDao().find(identity, conversation)!!
        assertNull(migrated.archivedAtMillis); assertTrue(migrated.selected)
        assertEquals("合成旧问题", database.cloudIntentDao().find(identity, request)!!.prompt)
        assertTrue(database.cloudIntentDao().receipt(identity, request)!!.resultJson!!.contains("合成旧回答"))
        assertEquals("合成旧记忆", database.memoryDao().find("local", "synthetic-memory")!!.content)
        assertNotNull(database.memoryToolBindingDao().find(request))
        assertNotNull(database.memoryContextDependenciesDao().find(request))
        assertEquals(1, database.taskDao().count("local"))
        assertFalse(database.conversationLifecycleDao().legacyAvailable(identity))
        assertTrue(database.conversationLifecycleDao().legacySelected(identity))
        assertTrue(database.conversationLifecycleDao().pending(identity).isEmpty())
        database.conversationLifecycleDao().delete(identity, conversation, 2000)
        assertEquals(cost, Json.parseToJsonElement(database.conversationLifecycleDao().retainedUsage(identity).single().costJson!!))
        assertEquals(1, database.taskDao().count("local"))
        assertEquals(1, database.memoryDao().revisionCount("local", "synthetic-memory"))
    }
}
