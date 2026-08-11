package com.healthyagent.running.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Room DAO — 跑步会话的 CRUD 操作
 */
@Dao
interface RunSessionDao {

    @Insert
    suspend fun insertSession(session: RunSessionEntity)

    @Update
    suspend fun updateSession(session: RunSessionEntity)

    @Insert
    suspend fun insertSplits(splits: List<SplitEntity>)

    @Query("SELECT * FROM run_sessions ORDER BY startTimeMillis DESC")
    suspend fun getAllSessions(): List<RunSessionEntity>

    @Query("SELECT * FROM run_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): RunSessionEntity?

    @Query("SELECT * FROM splits WHERE sessionId = :sessionId ORDER BY splitIndex ASC")
    suspend fun getSplits(sessionId: String): List<SplitEntity>

    @Query("DELETE FROM run_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
