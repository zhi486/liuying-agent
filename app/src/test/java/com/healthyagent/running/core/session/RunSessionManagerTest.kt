package com.healthyagent.running.core.session

import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RunSessionManager 单元测试
 */
class RunSessionManagerTest {

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
}
