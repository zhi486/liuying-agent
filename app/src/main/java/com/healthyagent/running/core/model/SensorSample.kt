package com.healthyagent.running.core.model

/**
 * 传感器原始样本（统一格式，屏蔽不同数据源差异）
 */
data class SensorSample(
    val type: SampleType,
    val value: Double,
    val timestampMillis: Long,
    val source: String = "unknown"
)

enum class SampleType {
    HEART_RATE,     // bpm
    CADENCE,        // spm (步/分)
    DISTANCE,       // 累计距离（米）
    STEPS,          // 累计步数
    SPEED,          // 速度 m/s
    BATTERY,        // 电量百分比
    STRIDE_LENGTH   // 步幅（厘米）
}
