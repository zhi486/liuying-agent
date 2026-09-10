package com.healthyagent.running.data.sensor.ble

import kotlin.math.roundToInt

/** B5 已确认的显示单位：整数米、秒/公里；配速 0 表示无有效值。 */
internal data class KeepB5DisplayMetrics(
    val distanceMeters: Int = 0,
    val paceSecPerKm: Int = 0,
) {
    init {
        require(distanceMeters >= 0)
        require(paceSecPerKm >= 0)
    }

    companion object {
        fun fromMeasurements(distanceMeters: Double, paceSecPerKm: Double?): KeepB5DisplayMetrics =
            KeepB5DisplayMetrics(
                distanceMeters = distanceMeters.takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() }
                    ?.toInt() ?: 0,
                // 与手机 Web UI 一致，配速四舍五入到整秒；距离不提前四舍五入到公里。
                paceSecPerKm = paceSecPerKm?.takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() }
                    ?.roundToInt() ?: 0,
            )
    }
}
