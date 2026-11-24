package com.healthyagent.running.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.CloudTaskIntent
import com.healthyagent.running.agent.cloud.ConversationSummaryRecord
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryContextDependencies
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 仅迁移独立合成文件库；固定旧结构，不读取真实数据库或根据新实体反推旧库。 */
@RunWith(AndroidJUnit4::class)
class MemoryContextMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val requestId = UUID.randomUUID().toString()
    private val exhaustedRequestId = UUID.randomUUID().toString()
    private val memoryId = UUID.randomUUID().toString()
    private val references = """[{"id":"$memoryId","version":1}]"""
    private val result = """{"status":"completed","answer":"合成旧回答","actions":[]}"""

    private fun open(file: File) = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath)
        .addMigrations(AgentDatabase.MIGRATION_13_14, AgentDatabase.MIGRATION_14_15, AgentDatabase.MIGRATION_15_16).build()

    private fun createVersion13(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            // 固定 v13 全表结构，保证 Room 会校验真实 13→14 增量迁移。
            old.execSQL("CREATE TABLE agent_tasks (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, clientRequestId TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_agent_tasks_ownerId_clientRequestId ON agent_tasks(ownerId, clientRequestId)")
            old.execSQL("CREATE INDEX index_agent_tasks_ownerId_createdAtMillis_id ON agent_tasks(ownerId, createdAtMillis, id)")
            old.execSQL("CREATE TABLE agent_messages (taskId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(taskId, sequence), FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_messages_taskId ON agent_messages(taskId)")
            old.execSQL("CREATE TABLE agent_actions (taskId TEXT NOT NULL, sequence INTEGER NOT NULL, kind TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(taskId, sequence), FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_agent_actions_taskId ON agent_actions(taskId)")
            old.execSQL("CREATE TABLE agent_conversations (id TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, title TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, selected INTEGER NOT NULL, archivedAtMillis INTEGER DEFAULT NULL)")
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
            old.execSQL("CREATE TABLE agent_legacy_conversation_state (origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, archivedAtMillis INTEGER, legacySelected INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(origin, ownerId, deviceId, credentialRef))")
            old.execSQL("CREATE TABLE agent_conversation_cleanup (origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, requestId TEXT NOT NULL, atMillis INTEGER NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(origin, ownerId, deviceId, credentialRef, requestId))")
            old.execSQL("CREATE INDEX index_agent_conversation_cleanup_requestId ON agent_conversation_cleanup(requestId)")
            old.execSQL("CREATE TABLE agent_conversation_retained_usage (origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, requestId TEXT NOT NULL, atMillis INTEGER NOT NULL, costJson TEXT, PRIMARY KEY(origin, ownerId, deviceId, credentialRef, requestId))")
            for (request in listOf(requestId, exhaustedRequestId)) {
                old.execSQL("INSERT INTO agent_cloud_intents (clientRequestId,localTaskId,origin,ownerId,deviceId,credentialRef,prompt,consentVersion,consentedAtMillis,updatedAtMillis,submissionAttempted) VALUES (?,?,?,?,?,?,'合成旧问题','prompt_only_v1',1000,1000,1)",
                    arrayOf(request, UUID.randomUUID().toString(), identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef))
            }
            old.execSQL("INSERT INTO agent_cloud_receipts VALUES (?,?,'completed',NULL,1000,31000,1000,'available',?,1,NULL)",
                arrayOf(requestId, UUID.randomUUID().toString(), result))
            old.execSQL("INSERT INTO agent_memories VALUES (?,'local','合成旧记忆','user_explicit',1000,1000,1,NULL)", arrayOf(memoryId))
            old.execSQL("INSERT INTO agent_memory_revisions VALUES ('local',?,1,'合成旧记忆',1000,'legacy',NULL)", arrayOf(memoryId))
            old.execSQL("INSERT INTO agent_memory_context_dependencies VALUES (?,?,0)", arrayOf(requestId, references))
            old.execSQL("INSERT INTO agent_memory_context_dependencies VALUES (?,'[]',1)", arrayOf(exhaustedRequestId))
            old.version = 13
        }
    }

    private suspend fun assertLegacyData(database: AgentDatabase) {
        assertEquals("合成旧问题", database.cloudIntentDao().find(identity, requestId)!!.prompt)
        assertEquals(result, database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
        assertEquals("合成旧记忆", database.memoryDao().find("local", memoryId)!!.content)
        assertEquals("合成旧记忆", database.memoryDao().history("local", memoryId, 0, 20)!!.items.single().content)
        val tracked = database.memoryContextDependenciesDao().find(requestId)!!
        assertEquals(references, tracked.referencesJson)
        assertFalse(tracked.exhausted)
        assertNull(tracked.protectedReferencesJson)
        val exhausted = database.memoryContextDependenciesDao().find(exhaustedRequestId)!!
        assertEquals("[]", exhausted.referencesJson)
        assertTrue(exhausted.exhausted)
        assertNull(exhausted.protectedReferencesJson)
    }

    @Test fun 十三到十六保留可空保护引用与旧数据且重开级联删除有效() = verifyMigration(13)

    @Test fun 十四到十六新增空摘要表及账本且重开保留旧数据() = verifyMigration(14)

    @Test fun 十五到十六保留旧数据且新增空请求账本() = verifyMigration(15)

    private fun verifyMigration(fromVersion: Int) = runBlocking {
        val file = File(context.noBackupFilesDir, "memory-context-migration-${UUID.randomUUID()}.db")
        val oldSummaries = if (fromVersion == 15) listOf("pending", "completed").map { state ->
            ConversationSummaryRecord(UUID.randomUUID().toString(), "a".repeat(64),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1000, 9000,
                "[\"$requestId\"]", references, state, if (state == "completed") "合成旧摘要" else null)
        } else emptyList()
        suspend fun assertOldSummaries(database: AgentDatabase) {
            for (row in oldSummaries) assertEquals(row, database.conversationSummaryDao().find(row.conversationId))
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_summary_requests").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
        createVersion13(file)
        if (fromVersion >= 14) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("ALTER TABLE agent_memory_context_dependencies ADD COLUMN protectedReferencesJson TEXT DEFAULT NULL")
                old.version = 14
                if (fromVersion == 15) {
                    old.execSQL("CREATE TABLE agent_conversation_summaries (conversationId TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, attemptId TEXT NOT NULL, processId TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, expiresAtMillis INTEGER NOT NULL, sourceIdsJson TEXT NOT NULL, referencesJson TEXT NOT NULL, state TEXT NOT NULL, summary TEXT, FOREIGN KEY(conversationId) REFERENCES agent_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    for (row in oldSummaries) {
                        old.execSQL("INSERT INTO agent_conversations VALUES (?,?,?,?,?,'合成旧会话',1000,1000,0,NULL)",
                            arrayOf(row.conversationId, identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef))
                        old.execSQL("INSERT INTO agent_conversation_summaries VALUES (?,?,?,?,?,?,?,?,?,?)",
                            arrayOf(row.conversationId, row.fingerprint, row.attemptId, row.processId, row.createdAtMillis,
                                row.expiresAtMillis, row.sourceIdsJson, row.referencesJson, row.state, row.summary))
                    }
                    old.version = 15
                }
            }
        }
        val newIntent = CloudTaskIntent.confirm(identity, "合成新问题", true, 2000)
        var database = open(file)
        try {
            assertLegacyData(database)
            assertOldSummaries(database)
            assertEquals(16, database.openHelper.readableDatabase.version)
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_summary_requests").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_conversation_summaries").use {
                assertTrue(it.moveToFirst()); assertEquals(oldSummaries.size, it.getInt(0))
            }
            database.openHelper.readableDatabase.query("PRAGMA table_info(agent_memory_context_dependencies)").use { columns ->
                var found = false
                while (columns.moveToNext()) {
                    if (columns.getString(columns.getColumnIndexOrThrow("name")) == "protectedReferencesJson") {
                        found = true
                        assertEquals("TEXT", columns.getString(columns.getColumnIndexOrThrow("type")))
                        assertEquals(0, columns.getInt(columns.getColumnIndexOrThrow("notnull")))
                        assertEquals("NULL", columns.getString(columns.getColumnIndexOrThrow("dflt_value")))
                    }
                }
                assertTrue("迁移应增加可空且默认 NULL 的保护引用列", found)
            }
            database.cloudIntentDao().save(newIntent)
            database.memoryContextDependenciesDao().save(MemoryContextDependencies(newIntent.clientRequestId, "[]", false,
                protectedReferencesJson = "[]"))
            database.close()
            database = open(file)
            assertLegacyData(database)
            assertOldSummaries(database)
            val newRow = database.memoryContextDependenciesDao().find(newIntent.clientRequestId)!!
            assertEquals("[]", newRow.referencesJson)
            assertFalse(newRow.exhausted)
            assertEquals("[]", newRow.protectedReferencesJson)
            database.openHelper.writableDatabase.execSQL("DELETE FROM agent_cloud_intents WHERE clientRequestId IN (?,?)",
                arrayOf(requestId, newIntent.clientRequestId))
            assertNull(database.memoryContextDependenciesDao().find(requestId))
            assertNull(database.memoryContextDependenciesDao().find(newIntent.clientRequestId))
            assertNull(database.cloudIntentDao().receipt(identity, requestId))
            assertNotNull(database.memoryContextDependenciesDao().find(exhaustedRequestId))
            assertEquals("合成旧记忆", database.memoryDao().find("local", memoryId)!!.content)
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { database.close() }
    }
}
