package com.healthyagent.running.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunSessionDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RunSessionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = database.runSessionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replaceSession_在同一入口替换分段和路线并保持路线顺序() = runTest {
        val session = RunSessionEntity(
            id = "run-1",
            startTimeMillis = 1_000L,
            state = "RUNNING",
        )
        dao.replaceSession(
            session = session,
            splits = listOf(split(session.id, index = 1)),
            routePoints = listOf(
                route(session.id, sequence = 0, timestampMillis = 1_100L),
                route(session.id, sequence = 1, timestampMillis = 2_100L),
            ),
        )

        dao.replaceSession(
            session = session.copy(totalDistanceMeters = 50.0),
            splits = emptyList(),
            routePoints = listOf(route(session.id, sequence = 0, timestampMillis = 3_100L)),
        )

        assertEquals(50.0, dao.getSession(session.id)?.totalDistanceMeters!!, 0.0)
        assertEquals(emptyList<SplitEntity>(), dao.getSplits(session.id))
        assertEquals(listOf(3_100L), dao.getRoutePoints(session.id).map { it.timestampMillis })
    }

    private fun split(sessionId: String, index: Int) = SplitEntity(
        sessionId = sessionId,
        splitIndex = index,
        distanceMeters = 1_000.0,
        durationSec = 360,
        avgPaceSecPerKm = 360.0,
    )

    private fun route(sessionId: String, sequence: Int, timestampMillis: Long) = RoutePointEntity(
        sessionId = sessionId,
        sequence = sequence,
        latitude = 31.2 + sequence / 10_000.0,
        longitude = 121.4 + sequence / 10_000.0,
        timestampMillis = timestampMillis,
        accuracyMeters = 4.0,
    )
}

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "route-migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration_1_2_保留旧跑步记录并创建路线表() = runTest {
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { legacy ->
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `run_sessions` (`id` TEXT NOT NULL, " +
                    "`startTimeMillis` INTEGER NOT NULL, `endTimeMillis` INTEGER, " +
                    "`totalDistanceMeters` REAL NOT NULL, `totalDurationSec` INTEGER NOT NULL, " +
                    "`avgHeartRate` REAL, `avgCadence` REAL, `avgPaceSecPerKm` REAL, " +
                    "`state` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `splits` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, `splitIndex` INTEGER NOT NULL, " +
                    "`distanceMeters` REAL NOT NULL, `durationSec` INTEGER NOT NULL, " +
                    "`avgHeartRate` REAL, `avgCadence` REAL, `avgPaceSecPerKm` REAL NOT NULL, " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `run_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_splits_sessionId` ON `splits` (`sessionId`)")
            legacy.execSQL(
                "INSERT INTO `run_sessions` (`id`, `startTimeMillis`, `endTimeMillis`, " +
                    "`totalDistanceMeters`, `totalDurationSec`, `avgHeartRate`, `avgCadence`, " +
                    "`avgPaceSecPerKm`, `state`) VALUES " +
                    "('legacy-run', 1000, NULL, 0.0, 0, NULL, NULL, NULL, 'PAUSED')",
            )
            legacy.version = 1
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            val migratedDao = migrated.runSessionDao()
            assertNotNull(migratedDao.getSession("legacy-run"))
            migratedDao.insertRoutePoints(
                listOf(
                    RoutePointEntity(
                        sessionId = "legacy-run",
                        sequence = 0,
                        latitude = 31.2,
                        longitude = 121.4,
                        timestampMillis = 1_100L,
                        accuracyMeters = 5.0,
                    ),
                ),
            )
            assertEquals(1, migratedDao.getRoutePoints("legacy-run").size)
        } finally {
            migrated.close()
        }
    }
}
