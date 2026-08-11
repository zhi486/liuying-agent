package com.healthyagent.running.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Split 的 Room Entity — 每公里分段数据，外键关联 run_sessions
 */
@Entity(
    tableName = "splits",
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SplitEntity(
    @PrimaryKey(autoGenerate = true)
    val uid: Int = 0,
    val sessionId: String,
    val splitIndex: Int,                // 第几公里（从 1 开始）
    val distanceMeters: Double,
    val durationSec: Long,
    val avgHeartRate: Double? = null,
    val avgCadence: Double? = null,
    val avgPaceSecPerKm: Double,
)
