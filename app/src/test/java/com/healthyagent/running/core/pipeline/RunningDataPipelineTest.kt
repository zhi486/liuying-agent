package com.healthyagent.running.core.pipeline

import app.cash.turbine.test
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunningDataPipelineTest {
    @Test fun `质量标记区分从未收到与首次出帧时已过期的零米及心率`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()
        pipeline.process(samples, ticks).test {
            ticks.emit(0)
            val waiting = awaitItem()
            assertFalse(waiting.hasDistanceSample)
            assertFalse(waiting.hasHeartRateSample)
            emit(samples, SampleType.DISTANCE, 0.0)
            emit(samples, SampleType.HEART_RATE, 140.0)
            runCurrent()
            nowMillis = 5_000
            ticks.emit(nowMillis)
            val expired = awaitItem()
            assertTrue(expired.hasDistanceSample)
            assertTrue(expired.hasHeartRateSample)
            assertFalse(expired.isDataFresh)
            assertFalse(expired.isHeartRateFresh)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `非法样本不建立质量基线且不能阻止较早时间戳的有效样本`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()
        pipeline.process(samples, ticks).test {
            listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach {
                samples.emit(sample(10_000, SampleType.DISTANCE, it))
            }
            listOf(Double.NaN, 0.0, 301.0, 140.5).forEach {
                samples.emit(sample(10_000, SampleType.HEART_RATE, it))
            }
            runCurrent()
            ticks.emit(0)
            val invalid = awaitItem()
            assertFalse(invalid.hasDistanceSample)
            assertFalse(invalid.hasHeartRateSample)
            assertNull(invalid.heartRate)
            assertEquals(0.0, invalid.distanceMeters, 0.0)
            emit(samples, SampleType.DISTANCE, 0.0)
            emit(samples, SampleType.HEART_RATE, 140.0)
            runCurrent()
            ticks.emit(0)
            val valid = awaitItem()
            assertTrue(valid.isDataFresh)
            assertTrue(valid.isHeartRateFresh)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private var nowMillis = 0L
    private val pipeline = RunningDataPipeline(
        initialConfig = RunConfig(llmEnabled = false),
        monotonicClockMillis = { nowMillis },
        wallClockMillis = { EPOCH_MILLIS + nowMillis },
    )

    @Test
    fun `全传感器断流时仍逐 tick 出帧并在五秒边界过期`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()

        pipeline.process(samples, ticks).test {
            emit(samples, SampleType.DISTANCE, 10.0)
            emit(samples, SampleType.HEART_RATE, 140.0)
            emit(samples, SampleType.CADENCE, 172.0)
            runCurrent()

            val frames = buildList {
                repeat(6) { second ->
                    nowMillis = second * 1_000L
                    ticks.emit(nowMillis)
                    add(awaitItem())
                }
            }

            assertEquals((1L..6L).toList(), frames.map { it.elapsedSeconds })
            assertTrue(frames[4].isDataFresh)
            assertTrue(frames[4].isHeartRateFresh)
            assertEquals(172, frames[4].cadence)

            val expired = frames.last()
            assertFalse(expired.isDataFresh)
            assertFalse(expired.isHeartRateFresh)
            assertNull(expired.heartRate)
            assertNull(expired.heartRateSmoothed)
            assertNull(expired.cadence)
            assertNull(expired.currentPaceSecPerKm)
            assertEquals(10.0, expired.distanceMeters, 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `心率持续上报不能掩盖 GPS 断流`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()

        pipeline.process(samples, ticks).test {
            emit(samples, SampleType.DISTANCE, 0.0)
            emit(samples, SampleType.HEART_RATE, 140.0)
            runCurrent()
            ticks.emit(0L)
            awaitItem()

            var frame = awaitGpsGapWithFreshHeartRate(samples, ticks)

            assertFalse(frame.isDataFresh)
            assertTrue(frame.isHeartRateFresh)
            assertEquals(145, frame.heartRate)
            assertNull(frame.currentPaceSecPerKm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GPS 持续上报时手环断流会单独清空心率`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()

        pipeline.process(samples, ticks).test {
            emit(samples, SampleType.HEART_RATE, 150.0)
            var frame = awaitItemAfterDistance(samples, ticks, second = 0, distance = 0.0)

            (1L..5L).forEach { second ->
                frame = awaitItemAfterDistance(
                    samples = samples,
                    ticks = ticks,
                    second = second,
                    distance = second * 3.0,
                )
            }

            assertTrue(frame.isDataFresh)
            assertFalse(frame.isHeartRateFresh)
            assertNull(frame.heartRate)
            assertNull(frame.heartRateSmoothed)
            assertEquals(HrZone.Z1, frame.hrZone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `步频在五秒边界由新鲜变为过期`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()

        pipeline.process(samples, ticks).test {
            emit(samples, SampleType.CADENCE, 168.0)
            runCurrent()

            ticks.emit(0L)
            assertEquals(168, awaitItem().cadence)

            nowMillis = 4_999L
            ticks.emit(nowMillis)
            assertEquals(168, awaitItem().cadence)

            nowMillis = 5_000L
            ticks.emit(nowMillis)
            assertNull(awaitItem().cadence)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GPS 断流恢复的第一点只重建配速窗口`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()

        pipeline.process(samples, ticks).test {
            assertNull(awaitItemAfterDistance(samples, ticks, 0, 0.0).currentPaceSecPerKm)
            assertEquals(
                333.33,
                awaitItemAfterDistance(samples, ticks, 1, 3.0).currentPaceSecPerKm!!,
                0.1,
            )

            nowMillis = 6_000L
            ticks.emit(nowMillis)
            assertFalse(awaitItem().isDataFresh)

            val firstRecovered = awaitItemAfterDistance(samples, ticks, 7, 6.0)
            assertTrue(firstRecovered.isDataFresh)
            assertNull(firstRecovered.currentPaceSecPerKm)

            val secondRecovered = awaitItemAfterDistance(samples, ticks, 8, 9.0)
            assertEquals(333.33, secondRecovered.currentPaceSecPerKm!!, 0.1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `同一管道的新 process 不继承上一轮窗口`() = runTest {
        val firstSamples = sampleFlow()
        val firstTicks = tickFlow()
        pipeline.process(firstSamples, firstTicks).test {
            assertNull(awaitItemAfterDistance(firstSamples, firstTicks, 0, 0.0).currentPaceSecPerKm)
            assertEquals(
                333.33,
                awaitItemAfterDistance(firstSamples, firstTicks, 1, 3.0).currentPaceSecPerKm!!,
                0.1,
            )
            cancelAndIgnoreRemainingEvents()
        }

        val secondSamples = sampleFlow()
        val secondTicks = tickFlow()
        pipeline.process(secondSamples, secondTicks).test {
            val firstFrame = awaitItemAfterDistance(secondSamples, secondTicks, 10, 100.0)
            assertEquals(1L, firstFrame.elapsedSeconds)
            assertNull(firstFrame.currentPaceSecPerKm)
            assertEquals(EPOCH_MILLIS + 10_000L, firstFrame.timestampMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `迟到的同类源样本不会覆盖已接收的新值`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()

        pipeline.process(samples, ticks).test {
            nowMillis = 1_000L
            samples.emit(sample(2_000L, SampleType.DISTANCE, 20.0))
            samples.emit(sample(1_000L, SampleType.DISTANCE, 5.0))
            runCurrent()
            ticks.emit(nowMillis)

            assertEquals(20.0, awaitItem().distanceMeters, 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `有序心率回调收到样本 collector 采集的单调接收时刻`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()
        val receipts = mutableListOf<Pair<SensorSample, Long>>()
        val receiptTimes = ArrayDeque(listOf(1_234L, 2_345L, 3_456L))
        val callbackPipeline = RunningDataPipeline(
            initialConfig = RunConfig(llmEnabled = false),
            monotonicClockMillis = { receiptTimes.removeFirst() },
            wallClockMillis = { EPOCH_MILLIS },
        )

        callbackPipeline.process(samples, ticks) { sample, receivedAtMillis ->
            receipts += sample to receivedAtMillis
        }.test {
            samples.emit(sample(9_998L, SampleType.HEART_RATE, 140.0))
            samples.emit(sample(9_999L, SampleType.HEART_RATE, 150.0))
            samples.emit(sample(9_997L, SampleType.HEART_RATE, 160.0))
            runCurrent()

            assertEquals(
                listOf(
                    sample(9_998L, SampleType.HEART_RATE, 140.0) to 1_234L,
                    sample(9_999L, SampleType.HEART_RATE, 150.0) to 2_345L,
                ),
                receipts,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `距离和步频样本不调用心率回调`() = runTest {
        val samples = sampleFlow()
        val ticks = tickFlow()
        val callbacks = mutableListOf<SensorSample>()

        pipeline.process(samples, ticks) { sample, _ -> callbacks += sample }.test {
            samples.emit(sample(1L, SampleType.DISTANCE, 10.0))
            samples.emit(sample(2L, SampleType.CADENCE, 170.0))
            runCurrent()

            assertTrue(callbacks.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `心率回调不改变管道产生的指标帧`() = runTest {
        val callbackSamples = sampleFlow()
        val callbackTicks = tickFlow()
        val plainSamples = sampleFlow()
        val plainTicks = tickFlow()
        val callbackFrames = mutableListOf<MetricsFrame>()
        val plainFrames = mutableListOf<MetricsFrame>()
        val callbackPipeline = RunningDataPipeline(
            initialConfig = RunConfig(llmEnabled = false),
            monotonicClockMillis = { nowMillis },
            wallClockMillis = { EPOCH_MILLIS + nowMillis },
        )
        val plainPipeline = RunningDataPipeline(
            initialConfig = RunConfig(llmEnabled = false),
            monotonicClockMillis = { nowMillis },
            wallClockMillis = { EPOCH_MILLIS + nowMillis },
        )

        val callbackCollection = async {
            callbackFrames += callbackPipeline.process(callbackSamples, callbackTicks) { _, _ -> }.take(1).toList()
        }
        val plainCollection = async {
            plainFrames += plainPipeline.process(plainSamples, plainTicks).take(1).toList()
        }
        runCurrent()

        nowMillis = 0L
        val heartRate = sample(10L, SampleType.HEART_RATE, 150.0)
        callbackSamples.emit(heartRate)
        plainSamples.emit(heartRate)
        runCurrent()
        callbackTicks.emit(nowMillis)
        plainTicks.emit(nowMillis)

        callbackCollection.await()
        plainCollection.await()
        assertEquals(plainFrames, callbackFrames)
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<MetricsFrame>
        .awaitGpsGapWithFreshHeartRate(
            samples: MutableSharedFlow<SensorSample>,
            ticks: MutableSharedFlow<Long>,
        ): MetricsFrame {
        var frame: MetricsFrame? = null
        (1L..5L).forEach { second ->
            nowMillis = second * 1_000L
            emit(samples, SampleType.HEART_RATE, 140.0 + second)
            yield()
            ticks.emit(nowMillis)
            frame = awaitItem()
        }
        return requireNotNull(frame)
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<MetricsFrame>
        .awaitItemAfterDistance(
            samples: MutableSharedFlow<SensorSample>,
            ticks: MutableSharedFlow<Long>,
            second: Long,
            distance: Double,
        ): MetricsFrame {
        nowMillis = second * 1_000L
        emit(samples, SampleType.DISTANCE, distance)
        yield()
        ticks.emit(nowMillis)
        return awaitItem()
    }

    private suspend fun emit(
        samples: MutableSharedFlow<SensorSample>,
        type: SampleType,
        value: Double,
    ) {
        samples.emit(sample(nowMillis, type, value))
    }

    private fun sample(timestampMillis: Long, type: SampleType, value: Double) = SensorSample(
        type = type,
        value = value,
        timestampMillis = timestampMillis,
        source = "test",
    )

    private fun sampleFlow() = MutableSharedFlow<SensorSample>(extraBufferCapacity = 16)
    private fun tickFlow() = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    private companion object {
        const val EPOCH_MILLIS = 1_700_000_000_000L
    }
}
