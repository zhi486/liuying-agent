package com.healthyagent.running.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class KilometerSpeechFormatterTest {
    @Test
    fun `整分钟使用中文数字和整字`() {
        assertEquals(
            "第一公里，用时六分整，配速每公里六分整。",
            KilometerSpeechFormatter.format(1, 360, 360.0),
        )
    }

    @Test
    fun `非整分钟的个位秒补零`() {
        assertEquals(
            "第十二公里，用时六分零五秒，配速每公里六分零五秒。",
            KilometerSpeechFormatter.format(12, 365, 365.0),
        )
    }

    @Test
    fun `公里序号正确处理十十一和二十`() {
        val cases = listOf(
            10 to "第十公里，用时六分整，配速每公里六分整。",
            11 to "第十一公里，用时六分整，配速每公里六分整。",
            20 to "第二十公里，用时六分整，配速每公里六分整。",
        )

        cases.forEach { (index, expected) ->
            assertEquals(expected, KilometerSpeechFormatter.format(index, 360, 360.0))
        }
    }

    @Test
    fun `不足一分钟时不播报零分`() {
        assertEquals(
            "第二公里，用时五十九秒，配速每公里五十九秒。",
            KilometerSpeechFormatter.format(2, 59, 59.0),
        )
    }

    @Test
    fun `配速小数秒沿用统计口径截断`() {
        assertEquals(
            "第二公里，用时六分零五秒，配速每公里六分零五秒。",
            KilometerSpeechFormatter.format(2, 365, 365.9),
        )
    }

    @Test
    fun `无效配速省略配速子句但保留真实公里和用时`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)
            .forEach { pace ->
                assertEquals(
                    "第三公里，用时六分四十七秒。",
                    KilometerSpeechFormatter.format(3, 407, pace),
                )
            }
    }
}
