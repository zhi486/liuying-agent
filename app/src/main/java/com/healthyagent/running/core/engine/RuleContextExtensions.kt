package com.healthyagent.running.core.engine

import com.healthyagent.running.core.math.HrZoneCalculator
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext

/**
 * RuleContext 辅助查询扩展函数
 *
 * 将常见的窗口查询逻辑（连续条件、趋势计算、区间判断）抽取为此处的扩展函数，
 * 避免 11 条规则中重复的遍历和计算代码。
 */

/** 当前帧是否在指定心率区间 */
fun RuleContext.currentHrZone(): HrZone? = recentFrames.lastOrNull()?.hrZone

/** 当前平滑心率值 */
fun RuleContext.currentSmoothedHr(): Int? = recentFrames.lastOrNull()?.heartRateSmoothed

/** 当前步频 */
fun RuleContext.currentCadence(): Int? = recentFrames.lastOrNull()?.cadence

/**
 * 连续满足条件的帧数（从最新一帧往前数）
 *
 * @param seconds 检查的时间窗口（秒），实际取最近 N 帧
 * @param predicate 每帧的判断条件
 * @return 连续满足条件的帧数
 */
fun RuleContext.consecutiveCondition(seconds: Int, predicate: (MetricsFrame) -> Boolean): Int {
    val frames = recentFrames.takeLast(seconds)
    var count = 0
    for (i in frames.indices.reversed()) {
        if (predicate(frames[i])) count++ else break
    }
    return count
}

/**
 * 是否连续 N 秒在指定心率区间
 */
fun RuleContext.consecutiveInZone(zone: HrZone, seconds: Int): Boolean {
    return consecutiveCondition(seconds) { it.hrZone == zone } >= seconds
}

/**
 * 心率变化趋势（bpm/秒），基于最近 N 秒的平滑心率
 *
 * @return Δbpm/s，正值表示上升；数据不足返回 null
 */
fun RuleContext.hrTrend(seconds: Int): Double? {
    val frames = recentFrames.takeLast(seconds + 1)
    if (frames.size < 2) return null
    val oldest = frames.first()
    val newest = frames.last()
    val oldHr = oldest.heartRateSmoothed ?: return null
    val newHr = newest.heartRateSmoothed ?: return null
    val deltaSec = (newest.timestampMillis - oldest.timestampMillis) / 1000.0
    if (deltaSec <= 0) return null
    return (newHr - oldHr) / deltaSec
}

/**
 * 心率在最近 [seconds] 秒内的总变化量（bpm）
 *
 * @return 总 Δbpm，数据不足返回 null
 */
fun RuleContext.hrDelta(seconds: Int): Double? {
    val trend = hrTrend(seconds) ?: return null
    return trend * seconds
}

/**
 * 步频变化趋势（spm/秒），基于最近 N 秒
 *
 * @return Δspm/s，数据不足返回 null
 */
fun RuleContext.cadenceTrend(seconds: Int): Double? {
    val frames = recentFrames.takeLast(seconds + 1)
    if (frames.size < 2) return null
    val oldest = frames.first()
    val newest = frames.last()
    val oldCad = oldest.cadence ?: return null
    val newCad = newest.cadence ?: return null
    val deltaSec = (newest.timestampMillis - oldest.timestampMillis) / 1000.0
    if (deltaSec <= 0) return null
    return (newCad - oldCad) / deltaSec
}

/**
 * 步频在最近 [seconds] 秒内的百分比变化
 *
 * @return 变化百分比（如 -0.15 表示下降 15%），数据不足返回 null
 */
fun RuleContext.cadenceChangePercent(seconds: Int): Double? {
    val frames = recentFrames.takeLast(seconds + 1)
    if (frames.size < 2) return null
    val oldest = frames.first().cadence ?: return null
    val newest = frames.last().cadence ?: return null
    if (oldest == 0) return null
    return (newest - oldest).toDouble() / oldest
}

/**
 * 当前配速偏离目标配速的百分比
 *
 * @return 偏离百分比（正值 = 慢于目标），无目标配速或配速数据返回 null
 */
fun RuleContext.paceDeviationPercent(targetPaceSecPerKm: Double?): Double? {
    if (targetPaceSecPerKm == null) return null
    val pace = recentFrames.lastOrNull()?.currentPaceSecPerKm ?: return null
    return (pace - targetPaceSecPerKm) / targetPaceSecPerKm
}

/**
 * 当前步幅（厘米）
 */
fun RuleContext.currentStrideLengthCm(): Float? = recentFrames.lastOrNull()?.strideLengthCm

/**
 * 当前心效比（心率/配速）
 */
fun RuleContext.currentHrPaceRatio(): Double? = recentFrames.lastOrNull()?.hrPaceRatio

/**
 * Z2 心率区间下限 bpm
 */
fun RuleContext.z2LowerBound(maxHr: Int): Int {
    return HrZoneCalculator.zoneRange(HrZone.Z2, maxHr).first
}
