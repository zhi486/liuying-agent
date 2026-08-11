package com.healthyagent.running.core.math

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 跑步指标计算器 — 纯函数集合，所有函数均为无副作用
 */
object MetricsCalculator {

    /**
     * 配速计算：距离增量 / 时间增量 → 秒/公里
     */
    fun currentPace(deltaDistanceMeters: Double, deltaTimeSec: Double): Double? {
        if (deltaTimeSec <= 0 || deltaDistanceMeters <= 0) return null
        return deltaTimeSec / (deltaDistanceMeters / 1000.0)
    }

    /**
     * 步幅估算：距离（米） / 步数 → 厘米
     */
    fun strideLength(distanceMeters: Double, steps: Long, deltaSteps: Long): Float? {
        if (deltaSteps <= 0 || distanceMeters <= 0) return null
        // 用增量步数计算当前步幅（更准确反映当前状态）
        val cm = (distanceMeters * 100) / steps.toDouble()
        return cm.toFloat()
    }

    /**
     * 步幅估算：基于增量距离和增量步数
     */
    fun strideLengthFromDelta(deltaDistanceMeters: Double, deltaSteps: Long): Float? {
        if (deltaSteps <= 0 || deltaDistanceMeters <= 0) return null
        return ((deltaDistanceMeters * 100) / deltaSteps).toFloat()
    }

    /**
     * 心效比：心率 / 配速(秒/公里) → 越低越好
     * 表示每公里消耗的心跳次数，效率指标
     */
    fun hrPaceRatio(heartRate: Int, paceSecPerKm: Double): Double {
        require(paceSecPerKm > 0) { "配速必须 > 0" }
        return heartRate / paceSecPerKm
    }

    /**
     * 简单滑动平均（用于心率平滑）
     * @param values 最近 N 个值
     * @return 平均值，空列表返回 null
     */
    fun movingAverage(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        return (values.sumOf { it.toLong() } / values.size).toInt()
    }

    /**
     * 心率变化率计算
     * @return Δbpm，null 表示数据不足
     */
    fun hrChangeRate(recentFrames: List<Pair<Long, Int>>, windowSec: Long = 30): Double? {
        if (recentFrames.size < 2) return null
        val oldest = recentFrames.first()
        val newest = recentFrames.last()
        val deltaSec = (newest.first - oldest.first) / 1000.0
        if (deltaSec <= 0) return null
        return (newest.second - oldest.second) / deltaSec
    }

    /**
     * 配速格式化：秒/公里 → 中文字符串
     */
    fun formatPace(paceSecPerKm: Double): String {
        val minutes = (paceSecPerKm / 60).toInt()
        val seconds = (paceSecPerKm % 60).toInt()
        return "${minutes}分${seconds.toString().padStart(2, '0')}秒"
    }
}
