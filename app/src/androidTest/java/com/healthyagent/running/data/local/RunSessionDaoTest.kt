package com.healthyagent.running.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.data.repository.RoomRunRepository
import com.healthyagent.running.coordinator.CommandOutcome
import com.healthyagent.running.coordinator.readHistorySeries
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun 五秒序列替换不重复且删除所属测试记录时级联清理() = runTest {
        val session = RunSessionEntity(id = "samples-test", startTimeMillis = 0, state = "RUNNING")
        val first = RunMetricSampleEntity(session.id, 0, 0, 5, 0, 360.0, 145.5, 5, 4)
        val second = RunMetricSampleEntity(session.id, 1, 5, 8, 1, null, null, 0, 0)
        dao.replaceSession(session, emptyList(), emptyList(), listOf(first, second))
        assertEquals(listOf(first, second), dao.getMetricSamples(session.id))
        dao.replaceSession(session, emptyList(), emptyList(), listOf(first))
        assertEquals(listOf(first), dao.getMetricSamples(session.id))
        dao.deleteSession(session.id)
        assertEquals(emptyList<RunMetricSampleEntity>(), dao.getMetricSamples(session.id))
    }

    @Test
    fun 非法重复样本导致事务回滚且保留原记录() = runTest {
        val session = RunSessionEntity(id = "rollback-test", startTimeMillis = 0, state = "RUNNING")
        val first = RunMetricSampleEntity(session.id, 0, 0, 5, 0, 360.0, 145.0, 5, 5)
        dao.replaceSession(session, emptyList(), emptyList(), listOf(first))
        var failed = false
        try {
            dao.replaceSession(session.copy(totalDurationSec = 99), emptyList(), emptyList(), listOf(first, first))
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            failed = true
        }
        assertEquals(true, failed)
        assertEquals(0L, dao.getSession(session.id)!!.totalDurationSec)
        assertEquals(listOf(first), dao.getMetricSamples(session.id))
    }

    @Test
    fun 五秒采集经过暂停缺失落盘再分页读取仍保持真实断点() = runTest {
        val manager = RunSessionManager()
        manager.startSession(RunConfig(targetDistanceMeters = 5000.0))
        fun frame(second: Long) = MetricsFrame(
            timestampMillis = second * 1000, elapsedSeconds = second,
            heartRate = 140, heartRateSmoothed = 190, hrZone = HrZone.Z2,
            cadence = null, strideLengthCm = null, currentPaceSecPerKm = 999.0,
            distanceMeters = second * 4.0, splitDistanceMeters = second * 4.0, totalSteps = 0,
            isDataFresh = second != 10L, hrPaceRatio = null,
        )
        (0L..7L).forEach { manager.onFrame(frame(it)) }
        manager.pause()
        manager.resume()
        (7L..17L).forEach { manager.onFrame(frame(it)) }
        val finished = manager.finishSession()!!
        val repository = RoomRunRepository(dao)
        repository.saveSession(finished)
        val first = readHistorySeries(finished.id, 0, 2, repository::getSessionWithSplits) as CommandOutcome.AcceptedWithData
        val second = readHistorySeries(finished.id, 2, 2, repository::getSessionWithSplits) as CommandOutcome.AcceptedWithData
        assertEquals("4", first.data.getValue("totalItems").jsonPrimitive.content)
        val points = second.data.getValue("samples").jsonArray
        assertEquals(JsonNull, points[0].jsonObject["averagePaceSecPerKm"])
        assertEquals("1", points[0].jsonObject.getValue("segmentId").jsonPrimitive.content)
        assertEquals("250.0", points[1].jsonObject.getValue("averagePaceSecPerKm").jsonPrimitive.content)
        assertEquals("140.0", points[1].jsonObject.getValue("averageHeartRate").jsonPrimitive.content)
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
    fun migration_1_3_保留旧跑步记录并创建路线与采样表() = verifyMigration(1)

    @Test
    fun migration_2_3_保留已有路线且不伪造历史采样() = verifyMigration(2)

    private fun verifyMigration(version: Int) = runTest {
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
            if (version == 2) {
                legacy.execSQL(
                    "CREATE TABLE `route_points` (`sessionId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                        "`accuracyMeters` REAL NOT NULL, `altitudeMeters` REAL, PRIMARY KEY(`sessionId`, `sequence`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `run_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                legacy.execSQL("CREATE INDEX `index_route_points_sessionId` ON `route_points` (`sessionId`)")
                legacy.execSQL("INSERT INTO `route_points` VALUES ('legacy-run', 1, 31.3, 121.5, 2100, 4.0, NULL)")
            }
            legacy.version = version
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        try {
            val migratedDao = migrated.runSessionDao()
            assertNotNull(migratedDao.getSession("legacy-run"))
            if (version == 2) {
                assertEquals(2100L, migratedDao.getRoutePoints("legacy-run").single().timestampMillis)
            }
            assertEquals(emptyList<RunMetricSampleEntity>(), migratedDao.getMetricSamples("legacy-run"))
            val sample = RunMetricSampleEntity("legacy-run", 0, 0, 5, 0, null, 140.5, 0, 4)
            migratedDao.insertMetricSamples(listOf(sample))
            assertEquals(listOf(sample), migratedDao.getMetricSamples("legacy-run"))
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
            assertEquals(if (version == 2) 2 else 1, migratedDao.getRoutePoints("legacy-run").size)
        } finally {
            migrated.close()
        }
    }
}
