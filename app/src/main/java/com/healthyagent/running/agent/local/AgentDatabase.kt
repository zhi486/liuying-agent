package com.healthyagent.running.agent.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.healthyagent.running.agent.cloud.CloudTaskIntent
import com.healthyagent.running.agent.cloud.CloudTaskIntentDao
import com.healthyagent.running.agent.cloud.CloudTaskReceipt
import com.healthyagent.running.agent.cloud.AgentConversation
import com.healthyagent.running.agent.cloud.AgentConversationDao
import com.healthyagent.running.agent.cloud.ConversationCleanup
import com.healthyagent.running.agent.cloud.ConversationLifecycleDao
import com.healthyagent.running.agent.cloud.ConversationRetainedUsage
import com.healthyagent.running.agent.cloud.LegacyConversationState
import com.healthyagent.running.agent.memory.MemoryDao
import com.healthyagent.running.agent.memory.MemoryEntity
import com.healthyagent.running.agent.memory.MemoryDeletionMarker
import com.healthyagent.running.agent.memory.MemoryRevision
import com.healthyagent.running.agent.memory.MemoryContextDependencies
import com.healthyagent.running.agent.memory.MemoryContextDependenciesDao
import com.healthyagent.running.agent.memory.MemoryToolReceipt
import com.healthyagent.running.agent.memory.MemoryToolBinding
import com.healthyagent.running.agent.memory.MemoryToolBindingDao
import java.io.File
import com.healthyagent.running.agent.cloud.ConversationSummaryRecord
import com.healthyagent.running.agent.cloud.ConversationSummaryDao
import com.healthyagent.running.agent.cloud.ConversationSummaryRequest
import com.healthyagent.running.agent.cloud.ConversationSummaryRequestDao

/** 独立于运动数据库；用户任务原文不参与系统自动备份。 */
@Database(entities = [AgentTaskEntity::class, AgentMessageEntity::class, AgentActionEntity::class, CloudTaskIntent::class, CloudTaskReceipt::class, MemoryEntity::class, MemoryRevision::class, MemoryDeletionMarker::class, AgentConversation::class, MemoryToolReceipt::class, MemoryToolBinding::class, MemoryContextDependencies::class, LegacyConversationState::class, ConversationCleanup::class, ConversationRetainedUsage::class, ConversationSummaryRecord::class, ConversationSummaryRequest::class], version = 16, exportSchema = false)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun taskDao(): AgentTaskDao
    abstract fun cloudIntentDao(): CloudTaskIntentDao
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): AgentConversationDao
    abstract fun conversationLifecycleDao(): ConversationLifecycleDao
    abstract fun memoryToolBindingDao(): MemoryToolBindingDao
    abstract fun memoryContextDependenciesDao(): MemoryContextDependenciesDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun conversationSummaryRequestDao(): ConversationSummaryRequestDao

    companion object {
        fun create(context: Context): AgentDatabase = Room.databaseBuilder(
            context.applicationContext,
            AgentDatabase::class.java,
            File(context.noBackupFilesDir, "liuying_agent.db").absolutePath,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16).build()

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_summary_requests (
                    requestId TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL,
                    deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, conversationId TEXT, fingerprint TEXT NOT NULL,
                    snapshotJson TEXT, sourceIdsJson TEXT, referencesJson TEXT, createdAtMillis INTEGER NOT NULL,
                    revision INTEGER NOT NULL, attempted INTEGER NOT NULL, remoteFinished INTEGER NOT NULL,
                    cost TEXT NOT NULL, inputTokens INTEGER, outputTokens INTEGER
                )""".trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_summary_requests_origin_ownerId_deviceId_credentialRef_conversationId_fingerprint ON agent_summary_requests(origin, ownerId, deviceId, credentialRef, conversationId, fingerprint)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_conversation_summaries (
                    conversationId TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL,
                    attemptId TEXT NOT NULL, processId TEXT NOT NULL, createdAtMillis INTEGER NOT NULL,
                    expiresAtMillis INTEGER NOT NULL, sourceIdsJson TEXT NOT NULL, referencesJson TEXT NOT NULL,
                    state TEXT NOT NULL, summary TEXT,
                    FOREIGN KEY(conversationId) REFERENCES agent_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 旧行来源未知，保留 NULL；不能假定它们没有继承或手选依赖。
                database.execSQL("ALTER TABLE agent_memory_context_dependencies ADD COLUMN protectedReferencesJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE agent_conversations ADD COLUMN archivedAtMillis INTEGER DEFAULT NULL")
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_legacy_conversation_state (
                    origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL,
                    archivedAtMillis INTEGER, legacySelected INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(origin, ownerId, deviceId, credentialRef)
                )""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_conversation_cleanup (
                    origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL,
                    requestId TEXT NOT NULL, atMillis INTEGER NOT NULL, pending INTEGER NOT NULL,
                    PRIMARY KEY(origin, ownerId, deviceId, credentialRef, requestId)
                )""".trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_agent_conversation_cleanup_requestId ON agent_conversation_cleanup(requestId)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_conversation_retained_usage (
                    origin TEXT NOT NULL, ownerId TEXT NOT NULL, deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL,
                    requestId TEXT NOT NULL, atMillis INTEGER NOT NULL, costJson TEXT,
                    PRIMARY KEY(origin, ownerId, deviceId, credentialRef, requestId)
                )""".trimIndent())
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE agent_memory_tool_bindings ADD COLUMN factsAllowed INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_memory_deletion_markers (
                    ownerId TEXT NOT NULL, contentHash TEXT NOT NULL, deletedAtMillis INTEGER NOT NULL,
                    PRIMARY KEY(ownerId, contentHash)
                )""".trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN mcpToolsJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_memory_context_dependencies (
                    requestId TEXT NOT NULL PRIMARY KEY, referencesJson TEXT NOT NULL, exhausted INTEGER NOT NULL,
                    FOREIGN KEY(requestId) REFERENCES agent_cloud_intents(clientRequestId) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_memory_revisions (
                    ownerId TEXT NOT NULL, memoryId TEXT NOT NULL, version INTEGER NOT NULL,
                    content TEXT NOT NULL, changedAtMillis INTEGER NOT NULL, changeSource TEXT NOT NULL, turnId TEXT,
                    PRIMARY KEY(ownerId, memoryId, version),
                    FOREIGN KEY(memoryId) REFERENCES agent_memories(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memory_revisions_memoryId ON agent_memory_revisions(memoryId)")
                database.execSQL("""INSERT OR IGNORE INTO agent_memory_revisions
                    (ownerId, memoryId, version, content, changedAtMillis, changeSource, turnId)
                    SELECT ownerId, id, version, content, updatedAtMillis, 'legacy', NULL FROM agent_memories
                    WHERE deletedAtMillis IS NULL AND content IS NOT NULL""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_memory_tool_receipts (
                    operationId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL,
                    requestHash TEXT NOT NULL, contentHash TEXT NOT NULL, turnId TEXT NOT NULL,
                    category TEXT NOT NULL, memoryId TEXT NOT NULL, memoryVersion INTEGER NOT NULL
                )""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_memory_tool_bindings (
                    requestId TEXT NOT NULL PRIMARY KEY, sourceHash TEXT NOT NULL,
                    scope TEXT NOT NULL, grantGeneration TEXT NOT NULL,
                    FOREIGN KEY(requestId) REFERENCES agent_cloud_intents(clientRequestId) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent())
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS agent_conversations (
                    id TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL,
                    deviceId TEXT NOT NULL, credentialRef TEXT NOT NULL, title TEXT NOT NULL,
                    createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, selected INTEGER NOT NULL
                )""".trimIndent())
                database.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN conversationId TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN conversationJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN runSummaryJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN knowledgeJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE agent_cloud_intents ADD COLUMN memoryRefsJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_memories` (
                        `id` TEXT NOT NULL PRIMARY KEY, `ownerId` TEXT NOT NULL,
                        `content` TEXT, `source` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL,
                        `version` INTEGER NOT NULL, `deletedAtMillis` INTEGER
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_memories_ownerId_updatedAtMillis_id` ON `agent_memories` (`ownerId`, `updatedAtMillis`, `id`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_cloud_receipts` (
                        `clientRequestId` TEXT NOT NULL PRIMARY KEY,
                        `cloudTaskId` TEXT NOT NULL, `status` TEXT NOT NULL, `reason` TEXT,
                        `acceptedAtMillis` INTEGER NOT NULL, `deadlineAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL, `resultState` TEXT NOT NULL, `resultJson` TEXT,
                        `ackPending` INTEGER NOT NULL, `confirmedControl` TEXT,
                        FOREIGN KEY(`clientRequestId`) REFERENCES `agent_cloud_intents`(`clientRequestId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_cloud_receipts_cloudTaskId` ON `agent_cloud_receipts` (`cloudTaskId`)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 只增表，不读取或转换历史本机任务，不采用破坏性迁移兜底。
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_cloud_intents` (
                        `clientRequestId` TEXT NOT NULL PRIMARY KEY,
                        `localTaskId` TEXT NOT NULL,
                        `origin` TEXT NOT NULL,
                        `ownerId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `credentialRef` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `consentVersion` TEXT NOT NULL,
                        `consentedAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `submissionAttempted` INTEGER NOT NULL,
                        `pendingControl` TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_cloud_intents_localTaskId` ON `agent_cloud_intents` (`localTaskId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_intents_identity_time` ON `agent_cloud_intents` (`origin`, `ownerId`, `deviceId`, `credentialRef`, `consentedAtMillis`, `clientRequestId`)")
            }
        }
    }
}
