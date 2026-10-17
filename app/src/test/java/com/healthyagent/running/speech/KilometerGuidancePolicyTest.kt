package com.healthyagent.running.speech

import com.healthyagent.running.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class KilometerGuidancePolicyTest {
    private val config = RunConfig(targetPaceSecPerKm = 360.0, hrAlertThresholdBpm = 165)
    private fun frames(hr: Int = 130, pace: Double = 360.0) = (370L..400L).map { second ->
        MetricsFrame(second * 1_000, second, hr, hr, if (hr >= 160) HrZone.Z4 else HrZone.Z2,
            170, null, pace, 1_000.0, 1_000.0, 1_700, true, null)
    }

    @Test fun `持续高心率优先于慢配速和目标缺失`() {
        assertEquals(KilometerGuidance.HIGH_HEART_RATE, KilometerGuidancePolicy.select(frames(170, 450.0), config))
        assertEquals(KilometerGuidance.HIGH_HEART_RATE, KilometerGuidancePolicy.select(frames(170), config.copy(targetPaceSecPerKm = null)))
    }

    @Test fun `配速持续偏离目标才给建议`() {
        assertEquals(KilometerGuidance.FAST_PACE, KilometerGuidancePolicy.select(frames(pace = 300.0), config))
        assertEquals(KilometerGuidance.BELOW_TARGET, KilometerGuidancePolicy.select(frames(pace = 420.0), config))
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(frames(), config))
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(frames(pace = 420.0).takeLast(10), config))
    }

    @Test fun `没有目标陈旧数据缺失数据和高强度不催速`() {
        val slow = frames(pace = 450.0)
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(slow, config.copy(targetPaceSecPerKm = null)))
        for (unreliable in listOf(
            slow.map { it.copy(isHeartRateFresh = false) },
            slow.map { it.copy(isDataFresh = false) },
            slow.map { it.copy(hasDistanceSample = false) },
            slow.map { it.copy(heartRateSmoothed = null) },
            slow.map { it.copy(currentPaceSecPerKm = Double.NaN) },
            slow.map { it.copy(hrZone = HrZone.Z4) },
            slow.map { it.copy(elapsedSeconds = it.elapsedSeconds - 300) },
        )) assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(unreliable, config))
    }

    @Test fun `心率单点尖峰断档重复时间不伪造持续高心率`() {
        val normal = frames()
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(normal.dropLast(1) + normal.last().copy(heartRateSmoothed = 180), config))
        val high = frames(180).takeLast(11)
        assertEquals(KilometerGuidance.HIGH_HEART_RATE, KilometerGuidancePolicy.select(high, config))
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(high.dropLast(1) + high.last().copy(elapsedSeconds = 410), config))
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(List(31) { high.last() }, config))
        assertEquals(KilometerGuidance.NONE, KilometerGuidancePolicy.select(high.map { it.copy(isHeartRateFresh = false) }, config))
    }

    @Test fun `同帧紧急规则不被慢配速覆盖`() {
        assertEquals(KilometerGuidance.CAUTION, KilometerGuidancePolicy.select(frames(pace = 450.0), config, urgent = true))
    }
}
