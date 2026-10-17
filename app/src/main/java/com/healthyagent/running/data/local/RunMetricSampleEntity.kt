package com.healthyagent.running.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "run_metric_samples",
    primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class RunMetricSampleEntity(
    val sessionId: String,
    val sequence: Int,
    val startElapsedSeconds: Long,
    val endElapsedSeconds: Long,
    val segmentId: Int,
    val averagePaceSecPerKm: Double?,
    val averageHeartRate: Double?,
    val paceValidSeconds: Int,
    val heartRateValidSeconds: Int,
)
