package com.healthyagent.running.core.model

/** 已通过 GPS 质量过滤、可用于本地轨迹和 Health Connect 的路线点。 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Double,
    val altitudeMeters: Double? = null,
)
