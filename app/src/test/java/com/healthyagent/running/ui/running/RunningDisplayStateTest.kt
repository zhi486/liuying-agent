package com.healthyagent.running.ui.running

import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RunningDisplayStateTest {

    @Test
    fun `指标帧会转换为展示就绪文本`() {
        val frame = MetricsFrame(
            timestampMillis = 1_000L,
            elapsedSeconds = 3_661L,
            heartRate = 148,
            heartRateSmoothed = 146,
            hrZone = HrZone.Z3,
            cadence = 172,
            strideLengthCm = 101.6f,
            currentPaceSecPerKm = 362.0,
            distanceMeters = 3_204.0,
            splitDistanceMeters = 204.0,
            totalSteps = 5_000L,
            isDataFresh = false,
            hrPaceRatio = null,
        )

        val display = RunningDisplayState.from(frame)

        assertEquals("146", display.heartRateText)
        assertEquals("6分02秒", display.paceText)
        assertEquals("172", display.cadenceText)
        assertEquals("102", display.strideLengthText)
        assertEquals("3.20", display.distanceText)
        assertEquals("01:01:01", display.elapsedText)
        assertEquals("有氧", display.zoneLabel)
        assertEquals(HrZone.Z3, display.hrZone)
        assertFalse(display.isDataFresh!!)
    }

    @Test
    fun `缺失传感器指标时使用占位文本`() {
        val frame = MetricsFrame(
            timestampMillis = 0L,
            elapsedSeconds = 0L,
            heartRate = null,
            heartRateSmoothed = null,
            hrZone = HrZone.Z1,
            cadence = null,
            strideLengthCm = null,
            currentPaceSecPerKm = null,
            distanceMeters = 0.0,
            splitDistanceMeters = 0.0,
            totalSteps = 0L,
            isDataFresh = true,
            hrPaceRatio = null,
        )

        val display = RunningDisplayState.from(frame)

        assertEquals("--", display.heartRateText)
        assertEquals("--", display.paceText)
        assertEquals("--", display.cadenceText)
        assertEquals("--", display.strideLengthText)
        assertEquals("0.00", display.distanceText)
        assertEquals("00:00:00", display.elapsedText)
    }
}
