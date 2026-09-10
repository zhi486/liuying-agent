package com.healthyagent.running.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BundledVoiceCatalogTest {
    @Test
    fun `消费者按规则身份取得对应固定文字和资源`() {
        val expected = listOf(
            BundledVoice("hr_z5_danger", "心率过高，请立即放慢", "hr_z5_danger.mp3"),
            BundledVoice("hr_rapid_rise", "心率上升很快，控制节奏", "hr_rapid_rise.mp3"),
            BundledVoice("hr_too_low", "心率偏低，可以适当加快", "hr_too_low.mp3"),
            BundledVoice("cadence_drop", "步频下降了，保持节奏", "cadence_drop.mp3"),
            BundledVoice("cadence_too_high", "步频过高，试着增大步幅", "cadence_too_high.mp3"),
            BundledVoice("pace_deviation", "配速低于目标，请加快", "pace_deviation.mp3"),
            BundledVoice("start_too_fast", "起跑太快了，保存体力", "start_too_fast.mp3"),
            BundledVoice("efficiency_abnormal", "这一公里效率下降，试着提高步频", "efficiency_abnormal.mp3"),
            BundledVoice("data_stale", "手环数据中断，请检查", "data_stale.mp3"),
            BundledVoice("goal_achieved", "已达成目标，干得漂亮！", "goal_achieved.mp3"),
            BundledVoice("session_started", "流萤已开始记录，出发吧。", "session_started.mp3"),
            BundledVoice("session_paused", "跑步已暂停。", "session_paused.mp3"),
            BundledVoice("session_resumed", "跑步已恢复。", "session_resumed.mp3"),
            BundledVoice("session_finished", "跑步已结束。", "session_finished.mp3"),
        )

        assertEquals(expected, BundledVoiceCatalog.entries)
        expected.forEach { voice ->
            assertEquals(voice, BundledVoiceCatalog.find(voice.ruleId))
        }
    }

    @Test
    fun `未知身份和固定文字都不能近似匹配资源`() {
        assertNull(BundledVoiceCatalog.find("unknown_rule"))
        assertNull(BundledVoiceCatalog.find("心率过高，请立即放慢"))
        assertNull(BundledVoiceCatalog.find("hr_z5"))
    }
}
