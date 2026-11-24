package com.healthyagent.running.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库 — 管理跑步会话 + 分段数据
 */
@Database(
    entities = [RunSessionEntity::class, SplitEntity::class, RoutePointEntity::class, RunMetricSampleEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun runSessionDao(): RunSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "healthy_agent.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `run_metric_samples` (
                        `sessionId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `startElapsedSeconds` INTEGER NOT NULL,
                        `endElapsedSeconds` INTEGER NOT NULL,
                        `segmentId` INTEGER NOT NULL,
                        `averagePaceSecPerKm` REAL,
                        `averageHeartRate` REAL,
                        `paceValidSeconds` INTEGER NOT NULL,
                        `heartRateValidSeconds` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`, `sequence`),
                        FOREIGN KEY(`sessionId`) REFERENCES `run_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_run_metric_samples_sessionId` ON `run_metric_samples` (`sessionId`)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `route_points` (
                        `sessionId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `accuracyMeters` REAL NOT NULL,
                        `altitudeMeters` REAL,
                        PRIMARY KEY(`sessionId`, `sequence`),
                        FOREIGN KEY(`sessionId`) REFERENCES `run_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_points_sessionId` " +
                        "ON `route_points` (`sessionId`)",
                )
            }
        }
    }
}
