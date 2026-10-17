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
import com.healthyagent.running.agent.memory.MemoryDao
import com.healthyagent.running.agent.memory.MemoryEntity
import java.io.File

/** 独立于运动数据库；用户任务原文不参与系统自动备份。 */
@Database(entities = [AgentTaskEntity::class, AgentMessageEntity::class, AgentActionEntity::class, CloudTaskIntent::class, CloudTaskReceipt::class, MemoryEntity::class, AgentConversation::class], version = 8, exportSchema = false)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun taskDao(): AgentTaskDao
    abstract fun cloudIntentDao(): CloudTaskIntentDao
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): AgentConversationDao

    companion object {
        fun create(context: Context): AgentDatabase = Room.databaseBuilder(
            context.applicationContext,
            AgentDatabase::class.java,
            File(context.noBackupFilesDir, "liuying_agent.db").absolutePath,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()

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
