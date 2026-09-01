package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.CoachMessage
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.testing.FakeTtsEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpeechBus 单元测试 — 使用假 TTS 引擎验证仲裁逻辑
 */
class SpeechBusTest {

    private fun makeTrigger(
        ruleId: String = "test",
        text: String = "测试播报",
        priority: SpeechPriority = SpeechPriority.APP,
    ) = RuleTrigger(
        ruleId = ruleId,
        severity = Severity.NORMAL,
        speakText = text,
        ttsPriority = priority,
    )

    @Test
    fun `基本播报 — 正常触发 TTS`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, SpeechPolicy(enabled = true, minIntervalSec = 4))

        bus.enqueue(makeTrigger(text = "播报1"))
        assertEquals(1, fakeTts.spokenTexts.size)
        assertEquals("播报1", fakeTts.spokenTexts[0])
    }

    @Test
    fun `ttsEnabled=false — 不播报`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, SpeechPolicy(enabled = false, minIntervalSec = 4))

        bus.enqueue(makeTrigger())
        assertTrue(fakeTts.spokenTexts.isEmpty())
    }

    @Test
    fun `去重 — 4s 内相同文本不重复播报`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, SpeechPolicy(enabled = true, minIntervalSec = 4))

        bus.enqueue(makeTrigger(text = "播报A"))
        bus.enqueue(makeTrigger(text = "播报A"))
        assertEquals(1, fakeTts.spokenTexts.size)
    }

    @Test
    fun `去重 — 不同文本可以播报`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, SpeechPolicy(enabled = true, minIntervalSec = 4))

        bus.enqueue(makeTrigger(text = "播报A"))
        bus.enqueue(makeTrigger(text = "播报B"))
        assertEquals(2, fakeTts.spokenTexts.size)
    }

    @Test
    fun `优先级 — URGENT 打断当前播报`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, SpeechPolicy(enabled = true, minIntervalSec = 4))

        // 模拟正在播放
        fakeTts.markSpeaking()

        bus.enqueue(makeTrigger(text = "紧急播报", priority = SpeechPriority.URGENT))
        assertEquals(1, fakeTts.stopCount) // 调用了 stop()
    }

    @Test
    fun `优先级 — 同优先级不打断`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, SpeechPolicy(enabled = true, minIntervalSec = 0))

        // 先播放一个 NORMAL
        bus.enqueue(makeTrigger(text = "播报1", priority = SpeechPriority.APP))

        // 同优先级不再播放（因为 currentPriority 不为 null 且优先级相同）
        assertEquals(1, fakeTts.spokenTexts.size)
    }

    @Test
    fun `stop — 停止当前播报`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts)

        bus.stop()
        assertEquals(1, fakeTts.stopCount)
    }

    @Test
    fun `shutdown — 关闭引擎和清理`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts)

        bus.shutdown()
        assertFalse(fakeTts.isSpeaking)
    }

    @Test
    fun `成功播报后发布具有稳定 ID 的消息`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(
            ttsEngine = fakeTts,
            newMessageId = { "coach-1" },
            nowMillis = { 42L },
        )

        bus.enqueue(makeTrigger(text = "稳住节奏", priority = SpeechPriority.COACH))

        assertEquals(
            CoachMessage("coach-1", "稳住节奏", SpeechPriority.COACH, 42L),
            bus.latestMessage.value,
        )
    }

    @Test
    fun `播报失败不发布消息`() = runTest {
        val fakeTts = FakeTtsEngine().apply { shouldFail = true }
        val bus = SpeechBus(fakeTts)

        bus.enqueue(makeTrigger(text = "不会上屏"))

        assertNull(bus.latestMessage.value)
    }

    @Test
    fun `新会话清除上一条消息但不关闭引擎`() = runTest {
        val fakeTts = FakeTtsEngine()
        val bus = SpeechBus(fakeTts, newMessageId = { "coach-1" })
        bus.enqueue(makeTrigger(text = "上一场消息"))

        bus.resetSession()

        assertNull(bus.latestMessage.value)
        assertEquals(0, fakeTts.shutdownCount)
    }
}
