package com.healthyagent.running.data.repository

import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.Split
import com.healthyagent.running.data.local.RoutePointEntity
import com.healthyagent.running.data.local.RunSessionDao
import com.healthyagent.running.data.local.RunSessionEntity
import com.healthyagent.running.data.local.SplitEntity

/**
 * 跑步记录仓库接口
 */
interface RunRepository {
    suspend fun saveSession(session: RunSession)
    suspend fun checkpointSession(session: RunSession)
    suspend fun getActiveSession(): RunSession?
    suspend fun getAllSessions(): List<RunSession>
    suspend fun getHistoryPage(offset: Int, limit: Int): RunHistoryPage
    suspend fun getSessionWithSplits(sessionId: String): RunSession?
}

data class RunHistoryPage(
    val totalItems: Int,
    val items: List<RunSession>,
)

/**
 * Room 实现 — 通过 DAO 完成持久化
 */
class RoomRunRepository(private val dao: RunSessionDao) : RunRepository {

    override suspend fun saveSession(session: RunSession) {
        dao.replaceSession(
            session = session.toEntity(),
            splits = session.splits.map { it.toEntity(session.id) },
            routePoints = session.routePoints.mapIndexed { sequence, point ->
                point.toEntity(sessionId = session.id, sequence = sequence)
            },
        )
    }

    override suspend fun checkpointSession(session: RunSession) {
        dao.replaceSession(
            session = session.toEntity(),
            splits = session.splits.map { it.toEntity(session.id) },
            routePoints = session.routePoints.mapIndexed { sequence, point ->
                point.toEntity(sessionId = session.id, sequence = sequence)
            },
        )
    }

    override suspend fun getActiveSession(): RunSession? {
        val sessionEntity = dao.getActiveSession() ?: return null
        return sessionEntity.toDomain(
            splits = dao.getSplits(sessionEntity.id),
            routePoints = dao.getRoutePoints(sessionEntity.id),
        )
    }

    override suspend fun getAllSessions(): List<RunSession> {
        return dao.getAllSessions().map { sessionEntity ->
            val splits = dao.getSplits(sessionEntity.id)
            val routePoints = dao.getRoutePoints(sessionEntity.id)
            sessionEntity.toDomain(splits = splits, routePoints = routePoints)
        }
    }

    override suspend fun getHistoryPage(offset: Int, limit: Int): RunHistoryPage {
        require(offset >= 0) { "历史分页 offset 不能为负数" }
        require(limit > 0) { "历史分页 limit 必须大于零" }

        val totalItems = dao.countFinishedSessions()
        val items = dao.getFinishedSessionsPage(offset = offset, limit = limit)
            .map { it.toDomain(splits = emptyList(), routePoints = emptyList()) }
        return RunHistoryPage(totalItems = totalItems, items = items)
    }

    override suspend fun getSessionWithSplits(sessionId: String): RunSession? {
        val sessionEntity = dao.getSession(sessionId) ?: return null
        val splits = dao.getSplits(sessionId)
        val routePoints = dao.getRoutePoints(sessionId)
        return sessionEntity.toDomain(splits = splits, routePoints = routePoints)
    }
}

// ── Mapper 扩展函数 ──

fun RunSession.toEntity(): RunSessionEntity = RunSessionEntity(
    id = id,
    startTimeMillis = startTimeMillis,
    endTimeMillis = endTimeMillis,
    totalDistanceMeters = totalDistanceMeters,
    totalDurationSec = totalDurationSec,
    avgHeartRate = avgHeartRate?.toDouble(),
    avgCadence = avgCadence?.toDouble(),
    avgPaceSecPerKm = avgPaceSecPerKm,
    state = runState.name,
)

fun Split.toEntity(sessionId: String): SplitEntity = SplitEntity(
    sessionId = sessionId,
    splitIndex = index,
    distanceMeters = distanceMeters,
    durationSec = durationSec,
    avgHeartRate = avgHeartRate?.toDouble(),
    avgCadence = avgCadence?.toDouble(),
    avgPaceSecPerKm = avgPaceSecPerKm,
)

fun RoutePoint.toEntity(sessionId: String, sequence: Int): RoutePointEntity = RoutePointEntity(
    sessionId = sessionId,
    sequence = sequence,
    latitude = latitude,
    longitude = longitude,
    timestampMillis = timestampMillis,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
)

fun RunSessionEntity.toDomain(
    splits: List<SplitEntity>,
    routePoints: List<RoutePointEntity> = emptyList(),
): RunSession = RunSession(
    id = id,
    startTimeMillis = startTimeMillis,
    endTimeMillis = endTimeMillis,
    totalDistanceMeters = totalDistanceMeters,
    totalDurationSec = totalDurationSec,
    avgHeartRate = avgHeartRate?.toInt(),
    avgCadence = avgCadence?.toInt(),
    avgPaceSecPerKm = avgPaceSecPerKm,
    splits = splits.map { it.toDomain() },
    routePoints = routePoints.map { it.toDomain() },
    runState = try {
        RunState.valueOf(state)
    } catch (_: IllegalArgumentException) {
        RunState.FINISHED
    },
)

fun RoutePointEntity.toDomain(): RoutePoint = RoutePoint(
    latitude = latitude,
    longitude = longitude,
    timestampMillis = timestampMillis,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
)

fun SplitEntity.toDomain(): Split = Split(
    index = splitIndex,
    distanceMeters = distanceMeters,
    durationSec = durationSec,
    avgHeartRate = avgHeartRate?.toInt(),
    avgCadence = avgCadence?.toInt(),
    avgPaceSecPerKm = avgPaceSecPerKm,
    hrZoneDistribution = null, // Room 不直接存储 Map，MVP 阶段省略
)
