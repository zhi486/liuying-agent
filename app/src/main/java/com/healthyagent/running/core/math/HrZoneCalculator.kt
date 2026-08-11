package com.healthyagent.running.core.math

import com.healthyagent.running.core.model.HrZone

/**
 * 心率区间计算器 — 纯函数
 */
object HrZoneCalculator {

    /**
     * 根据心率值确定当前区间
     */
    fun zone(heartRate: Int, maxHeartRate: Int): HrZone {
        require(maxHeartRate > 0) { "最大心率必须 > 0" }
        val pct = (heartRate.toDouble() / maxHeartRate * 100).toInt()
        return when {
            pct < HrZone.Z1.rangeMinPercent -> HrZone.Z1
            pct >= HrZone.Z5.rangeMinPercent -> HrZone.Z5
            else -> HrZone.entries.last { pct >= it.rangeMinPercent }
        }
    }

    /**
     * 计算目标心率区间范围
     * @return 该区间的 [下限, 上限] bpm
     */
    fun zoneRange(zone: HrZone, maxHeartRate: Int): IntRange {
        val lower = (maxHeartRate * zone.rangeMinPercent / 100)
        val upper = (maxHeartRate * zone.rangeMaxPercent / 100)
        return lower..upper
    }

    /**
     * 判断心率是否在目标区间内
     */
    fun isInZone(heartRate: Int, targetZone: HrZone, maxHeartRate: Int): Boolean {
        val range = zoneRange(targetZone, maxHeartRate)
        return heartRate in range
    }
}
