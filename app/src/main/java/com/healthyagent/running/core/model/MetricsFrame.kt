package com.healthyagent.running.core.model

/**
 * 每秒产出一帧的聚合指标，是规则引擎和 UI 的唯一输入
 */
data class MetricsFrame(
    val timestampMillis: Long,
    val elapsedSeconds: Long,

    // 心率
    val heartRate: Int?,
    val heartRateSmoothed: Int?,    // 5s 滑动平均
    val hrZone: HrZone,

    // 步频
    val cadence: Int?,              // spm

    // 步幅
    val strideLengthCm: Float?,     // 估算步幅

    // 配速
    val currentPaceSecPerKm: Double?,   // 秒/公里

    // 距离
    val distanceMeters: Double,
    val splitDistanceMeters: Double,    // 当前分段累计距离

    // 步数
    val totalSteps: Long,

    // 数据质量
    val isDataFresh: Boolean,           // 5s 内有新样本为 true

    // 派生指标
    val hrPaceRatio: Double?,           // 心效比（心率/配速），用于效率检测

    // 心率质量与总数据质量分离：手机 GPS 正常时，手环心率仍可能陈旧。
    val isHeartRateFresh: Boolean = isDataFresh,
)

/**
 * 心率五区间（基于最大心率百分比）
 */
enum class HrZone(val label: String, val rangeMinPercent: Int, val rangeMaxPercent: Int) {
    Z1("热身", 50, 60),
    Z2("燃脂", 60, 70),
    Z3("有氧", 70, 80),
    Z4("阈值", 80, 90),
    Z5("极限", 90, 100);

    companion object {
        fun fromHr(hr: Int, maxHr: Int): HrZone {
            val pct = (hr.toDouble() / maxHr * 100).toInt()
            return entries.last { pct >= it.rangeMinPercent }
        }
    }
}
