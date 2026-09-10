package com.healthyagent.running.data.sensor.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5WorkoutSessionStateTest {
    @Test
    fun `暂停后不应继续发送训练更新`() {
        val state = KeepB5WorkoutSessionState()

        state.start(10L)
        assertEquals(11L, state.advanceUpdate())
        state.pause(11L)

        assertFalse(state.isActive)
        assertNull(state.advanceUpdate())
    }

    @Test
    fun `恢复后应从冻结时长的下一秒继续而不能把暂停墙钟算入训练`() {
        val state = KeepB5WorkoutSessionState()

        state.start(40L)
        assertEquals(41L, state.advanceUpdate())
        state.pause(41L)
        state.resume(900L)

        assertTrue(state.isActive)
        assertEquals(42L, state.advanceUpdate())
    }

    @Test
    fun `恢复时较小的 elapsedSeconds 不应使训练时长回退`() {
        val state = KeepB5WorkoutSessionState()

        state.start(80L)
        assertEquals(81L, state.advanceUpdate())
        state.pause(81L)
        state.resume(7L)

        assertEquals(82L, state.advanceUpdate())
    }

    @Test
    fun `停止后的下一次新开始应从第一秒重新计时`() {
        val state = KeepB5WorkoutSessionState()

        state.start(120L)
        assertEquals(121L, state.advanceUpdate())
        state.stop()
        state.start(0L)

        assertTrue(state.isActive)
        assertEquals(1L, state.advanceUpdate())
    }

    @Test
    fun `错过多个更新间隔时应单调跳过而不能突发补发每一秒`() {
        val state = KeepB5WorkoutSessionState()

        state.start(0L)

        assertEquals(1L, state.advanceUpdate())
        assertEquals(6L, state.advanceUpdate(skippedIntervals = 4L))
        assertEquals(7L, state.advanceUpdate())
    }
}
