package com.healthyagent.running.data.repository

import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.Split
import com.healthyagent.running.data.local.RoutePointEntity
import com.healthyagent.running.data.local.RunSessionDao
import com.healthyagent.running.data.local.RunSessionEntity
import com.healthyagent.running.data.local.SplitEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRunRepositoryTest {
    @Test
    fun `历史分页使用 limit offset 和 count 且不加载 splits`() = runTest {
        val dao = RecordingRunSessionDao(
            page = listOf(
                RunSessionEntity(
                    id = "run-2",
                    startTimeMillis = 2_000L,
                    endTimeMillis = 3_000L,
                    totalDistanceMeters = 2_500.0,
                    totalDurationSec = 900,
                    avgHeartRate = 146.0,
                    avgCadence = 174.0,
                    avgPaceSecPerKm = 360.0,
                    state = "FINISHED",
                ),
            ),
            total = 21,
        )
        val repository = RoomRunRepository(dao)

        val result = repository.getHistoryPage(offset = 10, limit = 10)

        assertEquals(10, dao.lastOffset)
        assertEquals(10, dao.lastLimit)
        assertEquals(1, dao.countCalls)
        assertEquals(0, dao.splitQueryCalls)
        assertEquals(0, dao.routeQueryCalls)
        assertEquals(21, result.totalItems)
        assertEquals("run-2", result.items.single().id)
        assertTrue(result.items.single().splits.isEmpty())
        assertTrue(result.items.single().routePoints.isEmpty())
    }

    @Test
    fun `最终保存和 checkpoint 都原子替换 splits 与 route points`() = runTest {
        val dao = RecordingRunSessionDao()
        val repository = RoomRunRepository(dao)
        val session = RunSession(
            id = "run-active",
            startTimeMillis = 1_000L,
            totalDistanceMeters = 1_250.0,
            totalDurationSec = 420,
            splits = listOf(
                Split(
                    index = 1,
                    distanceMeters = 1_000.0,
                    durationSec = 350,
                    avgHeartRate = 145,
                    avgCadence = 172,
                    avgPaceSecPerKm = 350.0,
                    hrZoneDistribution = null,
                ),
            ),
            routePoints = listOf(
                RoutePoint(31.2, 121.4, 1_100L, 4.5, 12.0),
                RoutePoint(31.2001, 121.4001, 2_100L, 5.0, null),
            ),
            runState = RunState.RUNNING,
        )

        repository.checkpointSession(session)
        repository.saveSession(session.copy(runState = RunState.FINISHED, endTimeMillis = 9_000L))

        assertEquals(2, dao.upsertedSessions.size)
        assertEquals(listOf("run-active", "run-active"), dao.deletedSplitSessionIds)
        assertEquals(listOf("run-active", "run-active"), dao.deletedRouteSessionIds)
        assertEquals(2, dao.insertedSplitBatches.size)
        assertEquals(2, dao.insertedRouteBatches.size)
        val persistedRoute = dao.insertedRouteBatches.first()
        assertEquals(listOf(0, 1), persistedRoute.map { it.sequence })
        assertEquals(31.2, persistedRoute.first().latitude, 0.0)
        assertEquals(12.0, persistedRoute.first().altitudeMeters!!, 0.0)
    }

    @Test
    fun `恢复 active session 与读取单次 session 都还原路线顺序和属性`() = runTest {
        val entity = RunSessionEntity(
            id = "run-active",
            startTimeMillis = 1_000L,
            totalDistanceMeters = 100.0,
            totalDurationSec = 30,
            state = "PAUSED",
        )
        val routes = listOf(
            RoutePointEntity("run-active", 0, 31.0, 121.0, 1_100L, 3.0, null),
            RoutePointEntity("run-active", 1, 31.1, 121.1, 2_100L, 4.0, 10.0),
        )
        val dao = RecordingRunSessionDao(
            active = entity,
            session = entity,
            routePoints = routes,
        )
        val repository = RoomRunRepository(dao)

        val restored = repository.getActiveSession()
        val detail = repository.getSessionWithSplits("run-active")

        assertEquals(RunState.PAUSED, restored?.runState)
        assertEquals(routes.map { it.timestampMillis }, restored?.routePoints?.map { it.timestampMillis })
        assertEquals(10.0, detail?.routePoints?.last()?.altitudeMeters!!, 0.0)
        assertEquals(2, dao.routeQueryCalls)
    }
}

private class RecordingRunSessionDao(
    private val page: List<RunSessionEntity> = emptyList(),
    private val total: Int = 0,
    private val active: RunSessionEntity? = null,
    private val session: RunSessionEntity? = null,
    private val routePoints: List<RoutePointEntity> = emptyList(),
) : RunSessionDao {
    var lastOffset: Int? = null
    var lastLimit: Int? = null
    var countCalls = 0
    var splitQueryCalls = 0
    var routeQueryCalls = 0
    val upsertedSessions = mutableListOf<RunSessionEntity>()
    val insertedSplitBatches = mutableListOf<List<SplitEntity>>()
    val insertedRouteBatches = mutableListOf<List<RoutePointEntity>>()
    val deletedSplitSessionIds = mutableListOf<String>()
    val deletedRouteSessionIds = mutableListOf<String>()

    override suspend fun upsertSession(session: RunSessionEntity) {
        upsertedSessions += session
    }

    override suspend fun insertSplits(splits: List<SplitEntity>) {
        insertedSplitBatches += splits
    }

    override suspend fun insertRoutePoints(routePoints: List<RoutePointEntity>) {
        insertedRouteBatches += routePoints
    }

    override suspend fun deleteSplits(sessionId: String) {
        deletedSplitSessionIds += sessionId
    }

    override suspend fun deleteRoutePoints(sessionId: String) {
        deletedRouteSessionIds += sessionId
    }

    override suspend fun getAllSessions(): List<RunSessionEntity> = emptyList()

    override suspend fun getFinishedSessionsPage(offset: Int, limit: Int): List<RunSessionEntity> {
        lastOffset = offset
        lastLimit = limit
        return page
    }

    override suspend fun countFinishedSessions(): Int {
        countCalls++
        return total
    }

    override suspend fun getActiveSession(): RunSessionEntity? = active

    override suspend fun getSession(sessionId: String): RunSessionEntity? = session

    override suspend fun getSplits(sessionId: String): List<SplitEntity> {
        splitQueryCalls++
        return emptyList()
    }

    override suspend fun getRoutePoints(sessionId: String): List<RoutePointEntity> {
        routeQueryCalls++
        return routePoints
    }

    override suspend fun deleteSession(sessionId: String) = Unit
}
