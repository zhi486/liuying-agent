package com.healthyagent.running.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Room DAO — 跑步会话的 CRUD 操作
 */
@Dao
interface RunSessionDao {

    @Upsert
    suspend fun upsertSession(session: RunSessionEntity)

    @Insert
    suspend fun insertSplits(splits: List<SplitEntity>)

    @Insert
    suspend fun insertRoutePoints(routePoints: List<RoutePointEntity>)

    @Query("DELETE FROM splits WHERE sessionId = :sessionId")
    suspend fun deleteSplits(sessionId: String)

    @Query("DELETE FROM route_points WHERE sessionId = :sessionId")
    suspend fun deleteRoutePoints(sessionId: String)

    @Transaction
    suspend fun replaceSession(
        session: RunSessionEntity,
        splits: List<SplitEntity>,
        routePoints: List<RoutePointEntity>,
    ) {
        upsertSession(session)
        deleteSplits(session.id)
        deleteRoutePoints(session.id)
        if (splits.isNotEmpty()) insertSplits(splits)
        if (routePoints.isNotEmpty()) insertRoutePoints(routePoints)
    }

    @Query("SELECT * FROM run_sessions WHERE state = 'FINISHED' ORDER BY startTimeMillis DESC")
    suspend fun getAllSessions(): List<RunSessionEntity>

    @Query(
        "SELECT * FROM run_sessions " +
            "WHERE state = 'FINISHED' " +
            "ORDER BY startTimeMillis DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun getFinishedSessionsPage(offset: Int, limit: Int): List<RunSessionEntity>

    @Query("SELECT COUNT(*) FROM run_sessions WHERE state = 'FINISHED'")
    suspend fun countFinishedSessions(): Int

    @Query("SELECT * FROM run_sessions WHERE state IN ('RUNNING', 'PAUSED') ORDER BY startTimeMillis DESC LIMIT 1")
    suspend fun getActiveSession(): RunSessionEntity?

    @Query("SELECT * FROM run_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): RunSessionEntity?

    @Query("SELECT * FROM splits WHERE sessionId = :sessionId ORDER BY splitIndex ASC")
    suspend fun getSplits(sessionId: String): List<SplitEntity>

    @Query("SELECT * FROM route_points WHERE sessionId = :sessionId ORDER BY sequence ASC")
    suspend fun getRoutePoints(sessionId: String): List<RoutePointEntity>

    @Query("DELETE FROM run_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
