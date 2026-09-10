package com.healthyagent.running.coordinator

import app.cash.turbine.test
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PausedHeartRateMonitorTest {
    @Test
    fun `仅接受合法心率并计算区间`() = runTest {
        val samples = MutableSharedFlow<SensorSample>()
        val ticks = MutableSharedFlow<Long>()
        PausedHeartRateMonitor(monotonicClockMillis = { 1_000L })
            .observe(
                samples = samples,
                maxHeartRate = 200,
                seed = PausedHeartRateSeed(null, null, null),
                ticks = ticks,
            )
            .test {
                samples.emit(sample(SampleType.DISTANCE, 999.0, 1L))
                samples.emit(sample(SampleType.HEART_RATE, 150.0, 2L))

                assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun `相同合法心率重报仍发布并从新接收时刻续期`() = runTest {
        var nowMillis = 0L
        val samples = MutableSharedFlow<SensorSample>()
        val ticks = MutableSharedFlow<Long>()
        PausedHeartRateMonitor(monotonicClockMillis = { nowMillis })
            .observe(samples, 200, PausedHeartRateSeed(null, null, null), ticks)
            .test {
                samples.emit(sample(SampleType.HEART_RATE, 150.0, 1L))
                assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())

                nowMillis = 4_000L
                samples.emit(sample(SampleType.HEART_RATE, 150.0, 2L))
                assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())

                nowMillis = 5_000L
                ticks.emit(nowMillis)
                expectNoEvents()

                nowMillis = 9_000L
                ticks.emit(nowMillis)
                assertEquals(PausedHeartRateState(null, null), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun `心率合法边界一与三百均发布`() = runTest {
        var nowMillis = 0L
        val samples = MutableSharedFlow<SensorSample>()
        PausedHeartRateMonitor(monotonicClockMillis = { nowMillis })
            .observe(samples, 200, PausedHeartRateSeed(null, null, null), MutableSharedFlow())
            .test {
                samples.emit(sample(SampleType.HEART_RATE, 1.0, 1L))
                assertEquals(PausedHeartRateState(1, HrZone.Z1), awaitItem())

                nowMillis = 1L
                samples.emit(sample(SampleType.HEART_RATE, 300.0, 2L))
                assertEquals(PausedHeartRateState(300, HrZone.Z5), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun `冻结保留原接收时刻并在五秒边界清空`() = runTest {
        var nowMillis = 0L
        val monitor = PausedHeartRateMonitor(monotonicClockMillis = { nowMillis })
        monitor.beginRunning(7L)
        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 150.0, 1L), 0L)

        nowMillis = 4_999L
        val seed = monitor.freezeForPause(7L, maxHeartRate = 200)
        assertEquals(PausedHeartRateSeed(150, HrZone.Z3, 0L), seed)

        val ticks = MutableSharedFlow<Long>()
        monitor.observe(emptyFlow(), 200, seed, ticks).test {
            assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())

            nowMillis = 5_000L
            ticks.emit(5_000L)
            assertEquals(PausedHeartRateState(null, null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `默认时钟在五秒边界将新鲜 seed 清空`() = runTest {
        PausedHeartRateMonitor(monotonicClockMillis = { testScheduler.currentTime })
            .observe(
                samples = emptyFlow(),
                maxHeartRate = 200,
                seed = PausedHeartRateSeed(150, HrZone.Z3, 0L),
            )
            .test {
                assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())
                advanceTimeBy(4_999L)
                expectNoEvents()
                advanceTimeBy(1L)
                assertEquals(PausedHeartRateState(null, null), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun `完整但已过期的 seed 首项直接为空且旧 generation 不能复活`() = runTest {
        var nowMillis = 0L
        val monitor = PausedHeartRateMonitor(monotonicClockMillis = { nowMillis })
        monitor.beginRunning(7L)
        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 150.0, 1L), 0L)

        nowMillis = 5_000L
        val expiredSeed = monitor.freezeForPause(7L, 200)
        val ticks = MutableSharedFlow<Long>()
        monitor.observe(emptyFlow(), 200, expiredSeed, ticks).test {
            assertEquals(PausedHeartRateState(null, null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 160.0, 2L), 5_000L)
        assertEquals(expiredSeed, monitor.freezeForPause(7L, 200))
        monitor.beginRunning(8L)
        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 170.0, 3L), 5_000L)
        assertEquals(PausedHeartRateSeed(null, null, null), monitor.freezeForPause(8L, 200))
    }

    @Test
    fun `invalidate 后旧 generation 的迟到回调不能进入 seed`() {
        val monitor = PausedHeartRateMonitor()
        monitor.beginRunning(7L)
        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 150.0, 1L), 0L)
        monitor.invalidateRunning()
        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 160.0, 2L), 1L)

        assertEquals(PausedHeartRateSeed(null, null, null), monitor.freezeForPause(7L, 200))
    }

    @Test
    fun `无效心率不续期且畸形 seed 不发布`() = runTest {
        var nowMillis = 0L
        val monitor = PausedHeartRateMonitor(monotonicClockMillis = { nowMillis })
        monitor.beginRunning(7L)
        monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, 150.0, 1L), 0L)
        nowMillis = 4_999L
        listOf(0.0, 301.0, Double.NaN, Double.POSITIVE_INFINITY, 150.5).forEach { value ->
            monitor.recordRunningSample(7L, sample(SampleType.HEART_RATE, value, 2L), nowMillis)
        }
        val seed = monitor.freezeForPause(7L, 200)
        assertEquals(PausedHeartRateSeed(150, HrZone.Z3, 0L), seed)

        val samples = MutableSharedFlow<SensorSample>()
        val ticks = MutableSharedFlow<Long>()
        monitor.observe(samples, 200, seed, ticks).test {
            assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())
            listOf(0.0, 301.0, Double.NaN, Double.POSITIVE_INFINITY, 150.5).forEach { value ->
                samples.emit(sample(SampleType.HEART_RATE, value, 3L))
            }
            expectNoEvents()
            nowMillis = 5_000L
            ticks.emit(5_000L)
            assertEquals(PausedHeartRateState(null, null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        listOf(
            PausedHeartRateSeed(150, null, 0L),
            PausedHeartRateSeed(null, HrZone.Z3, 0L),
            PausedHeartRateSeed(0, HrZone.Z1, 0L),
            PausedHeartRateSeed(150, HrZone.Z1, 0L),
            PausedHeartRateSeed(150, HrZone.Z3, -1L),
            PausedHeartRateSeed(150, HrZone.Z3, 5_001L),
        ).forEach { malformed ->
            PausedHeartRateMonitor(monotonicClockMillis = { 5_000L })
                .observe(emptyFlow(), 200, malformed, MutableSharedFlow())
                .test {
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
        }
    }

    @Test
    fun `新接收的合法心率忽略设备时间戳并以本地接收时刻续期`() = runTest {
        var nowMillis = 1_000L
        val samples = MutableSharedFlow<SensorSample>()
        val ticks = MutableSharedFlow<Long>()
        PausedHeartRateMonitor(monotonicClockMillis = { nowMillis })
            .observe(samples, 200, PausedHeartRateSeed(null, null, null), ticks)
            .test {
                samples.emit(sample(SampleType.HEART_RATE, 150.0, -999_999L))
                assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())

                nowMillis = 5_999L
                ticks.emit(nowMillis)
                expectNoEvents()

                nowMillis = 6_000L
                ticks.emit(nowMillis)
                assertEquals(PausedHeartRateState(null, null), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun `全空或畸形 seed 的 tick 保持静默`() = runTest {
        listOf(
            PausedHeartRateSeed(null, null, null),
            PausedHeartRateSeed(150, HrZone.Z1, 0L),
            PausedHeartRateSeed(150, HrZone.Z3, 1L),
        ).forEach { seed ->
            val ticks = MutableSharedFlow<Long>()
            PausedHeartRateMonitor(monotonicClockMillis = { 0L })
                .observe(emptyFlow(), 200, seed, ticks)
                .test {
                    ticks.emit(0L)
                    expectNoEvents()
                    ticks.emit(5_000L)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
        }
    }

    @Test
    fun `取消 observer 后继续发送 tick 不残留 collector 或事件`() = runTest {
        val samples = MutableSharedFlow<SensorSample>()
        val ticks = MutableSharedFlow<Long>()
        PausedHeartRateMonitor(monotonicClockMillis = { 0L })
            .observe(samples, 200, PausedHeartRateSeed(null, null, null), ticks)
            .test {
                samples.emit(sample(SampleType.HEART_RATE, 150.0, 1L))
                assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

        runCurrent()
        assertEquals(0, samples.subscriptionCount.value)
        assertEquals(0, ticks.subscriptionCount.value)
        assertTrue(samples.tryEmit(sample(SampleType.HEART_RATE, 160.0, 2L)))
        assertTrue(ticks.tryEmit(5_000L))
        runCurrent()
        assertEquals(0, samples.subscriptionCount.value)
        assertEquals(0, ticks.subscriptionCount.value)
    }

    private fun sample(type: SampleType, value: Double, timestampMillis: Long) = SensorSample(
        type = type,
        value = value,
        timestampMillis = timestampMillis,
        source = "test",
    )
}
