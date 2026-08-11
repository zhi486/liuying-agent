package com.healthyagent.running.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RunSession 的 Room Entity — 持久化跑步会话概要
 */
@Entity(tableName = "run_sessions")
data class RunSessionEntity(
    @PrimaryKey
    val id: String,                     // UUID
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalDurationSec: Long = 0,
    val avgHeartRate: Double? = null,   // Double? 兼容 Room（Int? 需 TypeConverter）
    val avgCadence: Double? = null,
    val avgPaceSecPerKm: Double? = null,
    val state: String = "IDLE",         // RunState 名称
)
