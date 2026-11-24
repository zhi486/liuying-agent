package com.healthyagent.running.core.session

import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import org.junit.Assert.*
import org.junit.Test

class FiveSecondMetricsTest {
    private fun frame(second: Long, distance: Double = second * 2.0, hr: Int? = 140) = MetricsFrame(
        timestampMillis = second * 1000, elapsedSeconds = second,
        heartRate = hr, heartRateSmoothed = 190, hrZone = HrZone.Z2,
        cadence = null, strideLengthCm = null, currentPaceSecPerKm = 999.0,
        distanceMeters = distance, splitDistanceMeters = distance, totalSteps = 0,
        isDataFresh = true, hrPaceRatio = null,
    )

    @Test fun computesPaceFromDistanceAndTimeInsteadOfInstantPace() {
        val sampler = FiveSecondMetrics()
        val points = (0L..5L).flatMap { sampler.add(frame(it)) }
        assertEquals(1, points.size)
        assertEquals(500.0, points.single().averagePaceSecPerKm!!, 0.001)
        assertEquals(140.0, points.single().averageHeartRate!!, 0.001)
        assertEquals(5, points.single().heartRateValidSeconds)
    }

    @Test fun ignoresDuplicateSecondsAndStaleHeartRate() {
        val sampler = FiveSecondMetrics()
        sampler.add(frame(0))
        sampler.add(frame(1, hr = 100))
        sampler.add(frame(1, hr = 240))
        sampler.add(frame(2, hr = 200).copy(isHeartRateFresh = false))
        sampler.add(frame(3, hr = null))
        sampler.add(frame(4, hr = 0))
        val point = sampler.add(frame(5, hr = 160)).single()
        assertEquals(130.0, point.averageHeartRate!!, 0.001)
        assertEquals(2, point.heartRateValidSeconds)
    }

    @Test fun distanceGapInvalidatesWindowWithoutFillingItWithZero() {
        val sampler = FiveSecondMetrics()
        (0L..2L).forEach { sampler.add(frame(it)) }
        sampler.add(frame(3).copy(isDataFresh = false))
        sampler.add(frame(4))
        val point = sampler.add(frame(5)).single()
        assertNull(point.averagePaceSecPerKm)
        assertEquals(3, point.paceValidSeconds)
    }

    @Test fun missingFramesAreNotInterpolatedAcrossLongGap() {
        val sampler = FiveSecondMetrics()
        sampler.add(frame(0))
        sampler.add(frame(1))
        val earlier = sampler.add(frame(100)).first()
        assertNull(earlier.averagePaceSecPerKm)
        val points = sampler.finish(100)
        assertTrue(points.isEmpty())
    }

    @Test fun flushesPartialWindowAndBreaksContinuityOnResume() {
        val sampler = FiveSecondMetrics()
        (0L..3L).forEach { sampler.add(frame(it)) }
        val first = sampler.finish(3).single()
        assertEquals(3L, first.endElapsedSeconds)
        assertEquals(500.0, first.averagePaceSecPerKm!!, 0.001)
        sampler.restart(3, 1)
        sampler.add(frame(3, 100.0))
        val next = (4L..8L).flatMap { sampler.add(frame(it, 100.0 + (it - 3) * 4.0)) }.single()
        assertEquals(1, next.segmentId)
        assertEquals(250.0, next.averagePaceSecPerKm!!, 0.001)
        assertEquals(3L, next.startElapsedSeconds)
    }

    @Test fun noMovementAndInvalidDistanceDoNotProduceInfinitePace() {
        for (distance in listOf(0.0, Double.NaN, -1.0)) {
            val sampler = FiveSecondMetrics()
            val point = (0L..5L).flatMap { sampler.add(frame(it, distance)) }.single()
            assertNull(point.averagePaceSecPerKm)
        }
    }
}
