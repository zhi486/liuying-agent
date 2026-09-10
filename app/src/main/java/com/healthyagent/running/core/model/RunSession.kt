package com.healthyagent.running.core.model

/**
 * 每公里分段数据
 */
data class Split(
    val index: Int,                 // 第几公里（从 1 开始）
    val distanceMeters: Double,     // 通常是 1000 的倍数
    val durationSec: Long,          // 该分段用时（秒）
    val avgHeartRate: Int?,         // 该段平均心率
    val avgCadence: Int?,           // 该段平均步频
    val avgPaceSecPerKm: Double,    // 该段平均配速（秒/公里）
    val hrZoneDistribution: Map<HrZone, Double>?,    // 心率区间时间占比
)

/**
 * 跑步会话摘要
 */
data class RunSession(
    val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalDurationSec: Long = 0,
    val avgHeartRate: Int? = null,
    val avgCadence: Int? = null,
    val avgPaceSecPerKm: Double? = null,
    val splits: List<Split> = emptyList(),
    val routePoints: List<RoutePoint> = emptyList(),
    val runState: RunState = RunState.IDLE,
)
