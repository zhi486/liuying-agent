package com.healthyagent.running.data.repository

import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.Split
import com.healthyagent.running.data.local.RunSessionDao
import com.healthyagent.running.data.local.RunSessionEntity
import com.healthyagent.running.data.local.SplitEntity

/**
 * 跑步记录仓库接口
 */
interface RunRepository {
    suspend fun saveSession(session: RunSession)
    suspend fun getAllSessions(): List<RunSession>
    suspend fun getSessionWithSplits(sessionId: String): RunSession?
}

/**
 * Room 实现 — 通过 DAO 完成持久化
 */
class RoomRunRepository(private val dao: RunSessionDao) : RunRepository {

    override suspend fun saveSession(session: RunSession) {
        dao.insertSession(session.toEntity())
        if (session.splits.isNotEmpty()) {
            dao.insertSplits(session.splits.map { it.toEntity(session.id) })
        }
    }

    override suspend fun getAllSessions(): List<RunSession> {
        return dao.getAllSessions().map { sessionEntity ->
            val splits = dao.getSplits(sessionEntity.id)
            sessionEntity.toDomain(splits)
        }
    }

    override suspend fun getSessionWithSplits(sessionId: String): RunSession? {
        val sessionEntity = dao.getSession(sessionId) ?: return null
        val splits = dao.getSplits(sessionId)
        return sessionEntity.toDomain(splits)
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

fun RunSessionEntity.toDomain(splits: List<SplitEntity>): RunSession = RunSession(
    id = id,
    startTimeMillis = startTimeMillis,
    endTimeMillis = endTimeMillis,
    totalDistanceMeters = totalDistanceMeters,
    totalDurationSec = totalDurationSec,
    avgHeartRate = avgHeartRate?.toInt(),
    avgCadence = avgCadence?.toInt(),
    avgPaceSecPerKm = avgPaceSecPerKm,
    splits = splits.map { it.toDomain() },
    runState = try {
        RunState.valueOf(state)
    } catch (_: IllegalArgumentException) {
        RunState.FINISHED
    },
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
