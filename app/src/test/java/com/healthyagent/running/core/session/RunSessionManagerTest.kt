package com.healthyagent.running.core.session

import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RunSessionManager 单元测试
 */
class RunSessionManagerTest {

    @Test
    fun `五秒序列在暂停结束时保留短窗口且恢复后断线`() {
        val manager = RunSessionManager()
        manager.startSession(RunConfig(targetDistanceMeters = 5000.0))
        (0L..7L).forEach { manager.onFrame(makeFrame(it)) }
        manager.pause()
        assertEquals(listOf(5L, 7L), manager.currentSession!!.metricSamples.map { it.endElapsedSeconds })
        manager.onFrame(makeFrame(99))
        manager.resume()
        (7L..10L).forEach { manager.onFrame(makeFrame(it)) }
        val saved = manager.finishSession()!!
        assertEquals(listOf(0, 0, 1), saved.metricSamples.map { it.segmentId })
        assertEquals(400.0, saved.metricSamples.last().averagePaceSecPerKm!!, 0.01)
        assertEquals(3, saved.metricSamples.size)
    }

    @Test
    fun `恢复检查点保留旧序列且不跨进程计算距离`() {
        val first = RunSessionManager()
        first.startSession(RunConfig(targetDistanceMeters = 5000.0))
        (0L..5L).forEach { first.onFrame(makeFrame(it)) }
        val restored = RunSessionManager()
        restored.restoreSession(first.currentSession!!)
        (6L..10L).forEach { restored.onFrame(makeFrame(it)) }
        val points = restored.finishSession()!!.metricSamples
        assertEquals(2, points.size)
        assertEquals(1, points.last().segmentId)
        assertNull(points.last().averagePaceSecPerKm)
        restored.startSession(RunConfig(targetDistanceMeters = 5000.0))
        assertTrue(restored.currentSession!!.metricSamples.isEmpty())
    }

    private val config = RunConfig(targetDistanceMeters = 5000.0)

    private fun makeFrame(
        elapsedSec: Long,
        hr: Int? = 140,
        cadence: Int? = 170,
        pace: Double? = 360.0,
        distance: Double = elapsedSec * 2.5,
        timestamp: Long = elapsedSec * 1000,
    ): MetricsFrame = MetricsFrame(
        timestampMillis = timestamp,
        elapsedSeconds = elapsedSec,
        heartRate = hr,
        heartRateSmoothed = hr,
        hrZone = HrZone.Z2,
        cadence = cadence,
        strideLengthCm = null,
        currentPaceSecPerKm = pace,
        distanceMeters = distance,
        splitDistanceMeters = distance,
        totalSteps = 0,
        isDataFresh = true,
        hrPaceRatio = null,
    )

    @Test
    fun `startSession — 创建新会话 ID 不为空`() {
        val manager = RunSessionManager()
        val session = manager.startSession(config)
        assertNotNull(session.id)
        assertEquals(RunState.RUNNING, manager.currentState)
    }

    @Test
    fun `onFrame — 不跨公里时返回 null`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        val frame = makeFrame(1, distance = 500.0)
        assertNull(manager.onFrame(frame))
    }

    @Test
    fun `onFrame — 跨过 1 公里返回 Split`() {
        val manager = RunSessionManager()
        manager.startSession(config)

        // 模拟 1km 的帧序列
        for (i in 1..400) {
            val dist = i * 2.5 // 每帧约 2.5m
            val frame = makeFrame(i.toLong(), distance = dist)
            val split = manager.onFrame(frame)
            if (dist >= 1000.0 && split != null) {
                assertEquals(1, split.index)
                return // 验证通过
            }
        }
        // 不应该走到这里（配速 ~2.5m/s，400 帧足够覆盖 1km）
    }

    @Test
    fun `onFrame — 累积多个分段`() {
        val manager = RunSessionManager()
        manager.startSession(config)

        var splitCount = 0
        for (i in 1..1600) {
            val dist = i * 2.5
            val frame = makeFrame(i.toLong(), distance = dist)
            val split = manager.onFrame(frame)
            if (split != null) splitCount++
        }
        assertEquals(4, splitCount) // 4 公里 = 4 个 split
    }

    @Test
    fun `finishSession — 返回完整会话含平均指标`() {
        val manager = RunSessionManager()
        manager.startSession(config)

        // 模拟一定距离的帧
        for (i in 1..100) {
            manager.onFrame(makeFrame(i.toLong(), hr = 150, cadence = 175, distance = i * 2.5))
        }

        val session = manager.finishSession()
        assertNotNull(session)
        assertEquals(RunState.FINISHED, session!!.runState)
        assertNotNull(session.endTimeMillis)
        assertNotNull(session.avgHeartRate)
        assertNotNull(session.avgPaceSecPerKm)
    }

    @Test
    fun `pause — 暂停后 onFrame 返回 null`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        manager.pause()
        assertEquals(RunState.PAUSED, manager.currentState)
        assertNull(manager.onFrame(makeFrame(1)))
    }

    @Test
    fun `暂停和继续同步当前会话状态`() {
        val manager = RunSessionManager()
        manager.startSession(config)

        manager.pause()
        assertEquals(RunState.PAUSED, manager.currentState)
        assertEquals(RunState.PAUSED, manager.currentSession?.runState)

        manager.resume()
        assertEquals(RunState.RUNNING, manager.currentState)
        assertEquals(RunState.RUNNING, manager.currentSession?.runState)
    }

    @Test
    fun `resume — 恢复后 onFrame 正常工作`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        manager.pause()
        manager.resume()
        assertEquals(RunState.RUNNING, manager.currentState)
        assertNull(manager.onFrame(makeFrame(1, distance = 500.0)))
    }

    @Test
    fun `reset — 清空所有状态`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        manager.onFrame(makeFrame(1, distance = 500.0))
        manager.reset()

        assertEquals(RunState.IDLE, manager.currentState)
        assertNull(manager.currentSessionId)
    }

    @Test
    fun `空帧序列 — 不崩溃`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        val session = manager.finishSession()
        assertNotNull(session)
    }

    @Test
    fun `restoreSession — 恢复会话身份和累计指标`() {
        val manager = RunSessionManager()
        val checkpoint = RunSession(
            id = "active-run",
            startTimeMillis = 1000L,
            totalDistanceMeters = 1250.0,
            totalDurationSec = 500,
            avgHeartRate = 145,
            avgCadence = 172,
            runState = RunState.PAUSED,
        )

        manager.restoreSession(checkpoint)

        assertEquals(RunState.PAUSED, manager.currentState)
        assertEquals(checkpoint, manager.currentSession)
    }

    @Test
    fun `路线点只在跑步中累计`() {
        val manager = RunSessionManager()
        val point = RoutePoint(31.0, 121.0, 1_000L, 5.0)
        manager.startSession(config)

        manager.onRoutePoint(point)
        manager.pause()
        manager.onRoutePoint(point.copy(timestampMillis = 2_000L))

        assertEquals(listOf(point), manager.currentSession?.routePoints)
    }

    @Test
    fun `手机单跑没有心率时平均心率保持为空`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        manager.onFrame(makeFrame(elapsedSec = 1, hr = null, cadence = null))

        val session = manager.finishSession()

        assertNull(session?.avgHeartRate)
        assertNull(session?.avgCadence)
    }

    @Test
    fun `分段时长使用活动 elapsed 而不是跨暂停的墙上时间`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        manager.onFrame(makeFrame(elapsedSec = 1, distance = 0.0, timestamp = 1_000L))
        manager.onFrame(makeFrame(elapsedSec = 10, distance = 900.0, timestamp = 10_000L))

        manager.pause()
        manager.resume()
        val split = manager.onFrame(
            makeFrame(
                elapsedSec = 11,
                distance = 1_000.0,
                timestamp = 3_611_000L,
            ),
        )

        assertNotNull(split)
        assertEquals(11L, split?.durationSec)
    }

    @Test
    fun `后续分段只计算上一公里后的活动 elapsed 差值`() {
        val manager = RunSessionManager()
        manager.startSession(config)
        val firstSplit = manager.onFrame(makeFrame(elapsedSec = 400, distance = 1_000.0))

        manager.pause()
        manager.resume()
        val secondSplit = manager.onFrame(
            makeFrame(
                elapsedSec = 805,
                distance = 2_000.0,
                timestamp = 9_000_000L,
            ),
        )

        assertEquals(400L, firstSplit?.durationSec)
        assertEquals(405L, secondSplit?.durationSec)
    }

    @Test
    fun `advanceElapsedTo 只在跑步中向前推进且不会倒退`() {
        val manager = RunSessionManager()
        manager.startSession(config)

        manager.advanceElapsedTo(10L)
        manager.advanceElapsedTo(7L)
        assertEquals(10L, manager.currentSession?.totalDurationSec)

        manager.pause()
        manager.advanceElapsedTo(20L)
        assertEquals(10L, manager.currentSession?.totalDurationSec)

        manager.resume()
        manager.advanceElapsedTo(12L)
        assertEquals(12L, manager.currentSession?.totalDurationSec)
    }
}
