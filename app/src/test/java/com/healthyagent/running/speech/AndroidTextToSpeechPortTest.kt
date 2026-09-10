package com.healthyagent.running.speech

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AndroidTextToSpeechPortTest {

    @Test
    fun `初始化成功时按中文可用性、语言和语速顺序配置`() = runTest {
        val factory = FakePlatformFactory()
        val port = AndroidTextToSpeechPort(factory)

        factory.completeInitialization(TextToSpeech.SUCCESS)

        assertTrue(port.awaitInitialization().isSuccess)
        assertEquals(
            listOf(
                "isLanguageAvailable:${Locale.SIMPLIFIED_CHINESE}",
                "setLanguage:${Locale.SIMPLIFIED_CHINESE}",
                "setSpeechRate:1.0",
            ),
            factory.platform.calls,
        )
    }

    @Test
    fun `中文语言包不可用时初始化失败且不设置语言或语速`() = runTest {
        val factory = FakePlatformFactory(
            platform = FakePlatform(languageAvailability = TextToSpeech.LANG_MISSING_DATA),
        )
        val port = AndroidTextToSpeechPort(factory)

        factory.completeInitialization(TextToSpeech.SUCCESS)

        assertFalse(port.awaitInitialization().isSuccess)
        assertEquals(
            listOf("isLanguageAvailable:${Locale.SIMPLIFIED_CHINESE}"),
            factory.platform.calls,
        )
    }

    @Test
    fun `设置中文被拒绝时初始化失败且不设置语速`() = runTest {
        val factory = FakePlatformFactory(
            platform = FakePlatform(languageResult = TextToSpeech.LANG_NOT_SUPPORTED),
        )
        val port = AndroidTextToSpeechPort(factory)

        factory.completeInitialization(TextToSpeech.SUCCESS)

        assertFalse(port.awaitInitialization().isSuccess)
        assertEquals(
            listOf(
                "isLanguageAvailable:${Locale.SIMPLIFIED_CHINESE}",
                "setLanguage:${Locale.SIMPLIFIED_CHINESE}",
            ),
            factory.platform.calls,
        )
    }

    @Test
    fun `设置一倍语速失败时初始化失败`() = runTest {
        val factory = FakePlatformFactory(platform = FakePlatform(speechRateResult = TextToSpeech.ERROR))
        val port = AndroidTextToSpeechPort(factory)

        factory.completeInitialization(TextToSpeech.SUCCESS)

        assertFalse(port.awaitInitialization().isSuccess)
        assertEquals(
            listOf(
                "isLanguageAvailable:${Locale.SIMPLIFIED_CHINESE}",
                "setLanguage:${Locale.SIMPLIFIED_CHINESE}",
                "setSpeechRate:1.0",
            ),
            factory.platform.calls,
        )
    }

    @Test
    fun `平台初始化 Error 原样冒泡而不被规范化为 failure`() = runTest {
        val platformError = AssertionError("平台错误")
        val factory = FakePlatformFactory(
            platform = FakePlatform(languageAvailabilityError = platformError),
        )
        val port = AndroidTextToSpeechPort(factory)

        factory.completeInitialization(TextToSpeech.SUCCESS)

        val thrown = try {
            port.awaitInitialization()
            null
        } catch (error: AssertionError) {
            error
        }

        assertEquals(platformError, thrown)
    }

    @Test
    fun `助手语音 profile 使用 speech content type 和 assistant usage`() {
        val factory = FakePlatformFactory()
        val port = AndroidTextToSpeechPort(factory)

        assertTrue(port.setAudioProfile(SpeechAudioProfile.ASSISTANT_SPEECH).isSuccess)

        assertEquals(
            listOf(
                AudioAttributes.CONTENT_TYPE_SPEECH,
                AudioAttributes.USAGE_ASSISTANT,
            ),
            factory.platform.audioAttributes,
        )
    }

    @Test
    fun `平台回调映射为端口 listener 且 speak 透传接受结果`() {
        val factory = FakePlatformFactory(platform = FakePlatform(speakResult = false))
        val port = AndroidTextToSpeechPort(factory)
        val callbacks = mutableListOf<String>()
        port.setListener(object : TextToSpeechPort.Listener {
            override fun onStart(utteranceId: String) {
                callbacks += "start:$utteranceId"
            }

            override fun onDone(utteranceId: String) {
                callbacks += "done:$utteranceId"
            }

            override fun onError(utteranceId: String, errorCode: Int?) {
                callbacks += "error:$utteranceId:$errorCode"
            }

            override fun onStop(utteranceId: String) {
                callbacks += "stop:$utteranceId"
            }
        })

        assertFalse(port.speak("测试", "u-1"))
        factory.platform.listener!!.onStart("u-1")
        factory.platform.listener!!.onDone("u-1")
        factory.platform.listener!!.onError("u-1", 7)
        factory.platform.listener!!.onStop("u-1")

        assertEquals(
            listOf("start:u-1", "done:u-1", "error:u-1:7", "stop:u-1"),
            callbacks,
        )
        assertEquals(listOf("测试" to "u-1"), factory.platform.spoken)
    }

    private class FakePlatformFactory(
        val platform: FakePlatform = FakePlatform(),
    ) : AndroidTextToSpeechPlatformFactory {
        private var onInitialization: ((Int) -> Unit)? = null

        override fun create(onInitialization: (Int) -> Unit): AndroidTextToSpeechPlatform {
            this.onInitialization = onInitialization
            return platform
        }

        fun completeInitialization(status: Int) {
            onInitialization!!.invoke(status)
        }
    }

    private class FakePlatform(
        private val languageAvailability: Int = TextToSpeech.LANG_AVAILABLE,
        private val languageResult: Int = TextToSpeech.LANG_AVAILABLE,
        private val speechRateResult: Int = TextToSpeech.SUCCESS,
        private val speakResult: Boolean = true,
        private val languageAvailabilityError: Error? = null,
    ) : AndroidTextToSpeechPlatform {
        val calls = mutableListOf<String>()
        val audioAttributes = mutableListOf<Int>()
        val spoken = mutableListOf<Pair<String, String>>()
        var listener: AndroidTextToSpeechProgressListener? = null

        override fun isLanguageAvailable(locale: Locale): Int {
            calls += "isLanguageAvailable:$locale"
            languageAvailabilityError?.let { throw it }
            return languageAvailability
        }

        override fun setLanguage(locale: Locale): Int {
            calls += "setLanguage:$locale"
            return languageResult
        }

        override fun setSpeechRate(rate: Float): Int {
            calls += "setSpeechRate:$rate"
            return speechRateResult
        }

        override fun setAudioAttributes(contentType: Int, usage: Int): Int {
            audioAttributes += contentType
            audioAttributes += usage
            return TextToSpeech.SUCCESS
        }

        override fun setProgressListener(listener: AndroidTextToSpeechProgressListener) {
            this.listener = listener
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text to utteranceId
            return speakResult
        }

        override fun stop() = Unit

        override fun shutdown() = Unit
    }
}
