package com.healthyagent.running.core.engine

import com.healthyagent.running.core.engine.rules.HrAlertGate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrAlertGateTest {
    private val clock = TestTimeSource()
    private val gate = HrAlertGate(clock)

    private fun sample(hr: Int? = 162, fresh: Boolean = true, interval: Int = 120) =
        gate.shouldAlert(hr, fresh, 162, interval)

    private fun firstAlert(interval: Int = 120) {
        repeat(10) {
            assertFalse(sample(interval = interval))
            clock += 1.seconds
        }
        assertTrue(sample(interval = interval))
    }

    @Test
    fun `首次在连续10000毫秒而非9999毫秒触发`() {
        repeat(10) {
            assertFalse(sample())
            if (it < 9) clock += 1.seconds
        }
        clock += 999.milliseconds
        assertFalse(sample())
        clock += 1.milliseconds
        assertTrue(sample())
        repeat(10) { assertFalse(sample()) }
    }

    @Test
    fun `重复帧不会代替流逝时间`() {
        repeat(100) { assertFalse(sample()) }
    }

    @Test
    fun `持续超标在默认120秒冷却边界再次触发`() {
        firstAlert()
        repeat(119) {
            clock += 1.seconds
            assertFalse(sample())
        }
        clock += 999.milliseconds
        assertFalse(sample())
        clock += 1.milliseconds
        assertTrue(sample())
    }

    @Test
    fun `支持30秒与300秒专用间隔`() {
        for (interval in listOf(30, 300)) {
            gate.reset()
            firstAlert(interval)
            repeat(interval - 1) {
                clock += 1.seconds
                assertFalse(sample(interval = interval))
            }
            clock += 1.seconds
            assertTrue(sample(interval = interval))
        }
    }

    @Test
    fun `2000毫秒评估间隔保持连续而2001毫秒重新开始`() {
        assertFalse(sample())
        repeat(4) {
            clock += 2.seconds
            assertFalse(sample())
        }
        clock += 2.seconds
        assertTrue(sample())

        gate.reset()
        repeat(9) {
            assertFalse(sample())
            clock += 1.seconds
        }
        clock += 1001.milliseconds
        firstAlert()
    }

    @Test
    fun `低值空值或心率陈旧都打断连续十秒`() {
        for ((hr, fresh) in listOf(161 to true, null to true, 162 to false)) {
            gate.reset()
            repeat(9) {
                assertFalse(sample())
                clock += 1.seconds
            }
            assertFalse(sample(hr, fresh))
            firstAlert()
        }
    }

    @Test
    fun `短暂恢复不会清除冷却且到期必须仍连续超标`() {
        firstAlert()
        clock += 1.seconds
        assertFalse(sample(150))
        repeat(110) {
            clock += 1.seconds
            assertFalse(sample())
        }
        assertFalse(sample(150))
        // 距前次事件已111秒；还剩9秒，到期时新一轮超标仅8秒。
        clock += 1.seconds
        repeat(10) {
            assertFalse(sample())
            clock += 1.seconds
        }
        assertTrue(sample())
    }

    @Test
    fun `暂停清连续计时但不清冷却`() {
        firstAlert()
        gate.onPause()
        repeat(10) {
            assertFalse(sample())
            clock += 1.seconds
        }
        assertFalse(sample())
        gate.onPause()
        clock += 120.seconds
        firstAlert()
    }

    @Test
    fun `新会话重置冷却并重新等待十秒`() {
        firstAlert()
        gate.reset()
        firstAlert()
    }
}
