package com.healthyagent.running.speech

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusControllerTest {

    @Test
    fun `每次 request 均以助手 speech 属性请求 transient may duck 焦点`() {
        val platform = FakeAudioFocusPlatform(granted = true)
        val controller = AndroidAudioFocusController(platform)

        val lease = controller.request()

        assertTrue(lease.granted)
        assertEquals(
            listOf(
                FocusRequest(
                    contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                    usage = AudioAttributes.USAGE_ASSISTANT,
                    focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                ),
            ),
            platform.requests,
        )
    }

    @Test
    fun `lease 仅释放自己的 request 且重复 release 无副作用`() {
        val platform = FakeAudioFocusPlatform(granted = true)
        val controller = AndroidAudioFocusController(platform)
        val first = controller.request()
        val second = controller.request()

        first.release()
        first.release()
        second.release()
        second.release()

        assertEquals(listOf(0, 1), platform.abandonedRequestIndexes)
    }

    @Test
    fun `被拒绝的焦点仍返回可安全释放的 lease`() {
        val platform = FakeAudioFocusPlatform(granted = false)
        val lease = AndroidAudioFocusController(platform).request()

        assertFalse(lease.granted)
        lease.release()
        lease.release()

        assertEquals(listOf(0), platform.abandonedRequestIndexes)
    }

    private data class FocusRequest(
        val contentType: Int,
        val usage: Int,
        val focusGain: Int,
    )

    private class FakeAudioFocusPlatform(
        private val granted: Boolean,
    ) : AndroidAudioFocusPlatform {
        val requests = mutableListOf<FocusRequest>()
        val abandonedRequestIndexes = mutableListOf<Int>()
        private val tokens = mutableListOf<FakeRequest>()

        override fun requestFocus(contentType: Int, usage: Int, focusGain: Int): AndroidAudioFocusRequest {
            requests += FocusRequest(contentType, usage, focusGain)
            return FakeRequest(tokens.size, granted).also(tokens::add)
        }

        override fun abandonFocus(request: AndroidAudioFocusRequest) {
            abandonedRequestIndexes += (request as FakeRequest).index
        }

        private data class FakeRequest(
            val index: Int,
            override val granted: Boolean,
        ) : AndroidAudioFocusRequest
    }
}
