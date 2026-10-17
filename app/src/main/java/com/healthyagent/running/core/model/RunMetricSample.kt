package com.healthyagent.running.core.model

/** 五秒活动时间窗口；缺失值保留为空，覆盖秒数不包含暂停或断流。 */
data class RunMetricSample(
    val startElapsedSeconds: Long,
    val endElapsedSeconds: Long,
    val segmentId: Int,
    val averagePaceSecPerKm: Double?,
    val averageHeartRate: Double?,
    val paceValidSeconds: Int,
    val heartRateValidSeconds: Int,
)
