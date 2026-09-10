package com.healthyagent.running.data.sensor.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpsDistanceTrackerTest {
    private val tracker = GpsDistanceTracker()

    @Test
    fun `正常定位点使用 Haversine 累计距离`() {
        val first = tracker.update(fix(latitude = 0.0, longitude = 0.0, timestampMillis = 1_000L))
        val second = tracker.update(fix(latitude = 0.0, longitude = 0.001, timestampMillis = 61_000L))
        val third = tracker.update(fix(latitude = 0.0, longitude = 0.002, timestampMillis = 121_000L))

        assertEquals(0.0, first!!.totalDistanceMeters, 0.001)
        assertEquals(111.19, second!!.totalDistanceMeters, 0.2)
        assertEquals(222.39, third!!.totalDistanceMeters, 0.3)
        assertEquals(1.85, third.speedMetersPerSecond, 0.02)
    }

    @Test
    fun `精度超过四十米的定位点被忽略`() {
        tracker.update(fix(latitude = 0.0, longitude = 0.0, timestampMillis = 1_000L))

        val ignored = tracker.update(
            fix(
                latitude = 0.0,
                longitude = 0.001,
                accuracyMeters = 40.1,
                timestampMillis = 61_000L,
            )
        )
        val recovered = tracker.update(
            fix(latitude = 0.0, longitude = 0.002, timestampMillis = 121_000L)
        )

        assertNull(ignored)
        assertEquals(222.39, recovered!!.totalDistanceMeters, 0.3)
    }

    @Test
    fun `不可能速度的 GPS 跳点被忽略且不污染下一点`() {
        tracker.update(fix(latitude = 31.0, longitude = 121.0, timestampMillis = 1_000L))

        val jump = tracker.update(
            fix(latitude = 32.0, longitude = 122.0, timestampMillis = 2_000L)
        )
        val recovered = tracker.update(
            fix(latitude = 31.0001, longitude = 121.0, timestampMillis = 3_000L)
        )

        assertNull(jump)
        assertNotNull(recovered)
        assertEquals(11.12, recovered!!.totalDistanceMeters, 0.2)
        assertEquals(5.56, recovered.speedMetersPerSecond, 0.2)
    }

    @Test
    fun `非递增时间戳被忽略`() {
        tracker.update(fix(latitude = 0.0, longitude = 0.0, timestampMillis = 2_000L))

        assertNull(
            tracker.update(fix(latitude = 0.0, longitude = 0.00001, timestampMillis = 2_000L))
        )
        assertNull(
            tracker.update(fix(latitude = 0.0, longitude = 0.00001, timestampMillis = 1_000L))
        )
    }

    @Test
    fun `设备报告异常速度时定位点被忽略`() {
        tracker.update(fix(latitude = 0.0, longitude = 0.0, timestampMillis = 1_000L))

        val ignored = tracker.update(
            fix(
                latitude = 0.0,
                longitude = 0.00001,
                timestampMillis = 2_000L,
                reportedSpeedMetersPerSecond = 15.0,
            )
        )

        assertNull(ignored)
    }

    private fun fix(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double = 5.0,
        timestampMillis: Long,
        reportedSpeedMetersPerSecond: Double? = null,
    ) = GpsFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        timestampMillis = timestampMillis,
        reportedSpeedMetersPerSecond = reportedSpeedMetersPerSecond,
    )
}
