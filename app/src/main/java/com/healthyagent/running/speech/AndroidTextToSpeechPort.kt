package com.healthyagent.running.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

class AndroidTextToSpeechPort internal constructor(
    factory: AndroidTextToSpeechPlatformFactory,
) : TextToSpeechPort {
    private val initializationStatus = CompletableDeferred<Int>()
    private val platform = factory.create { status -> initializationStatus.complete(status) }

    constructor(context: Context) : this(
        AndroidTextToSpeechPlatformFactory { onInitialization ->
            AndroidTextToSpeechPlatformAdapter(context, onInitialization)
        },
    )

    override suspend fun awaitInitialization(): Result<Unit> = try {
        Result.success(
            initializePlatform(),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun initializePlatform() {
        check(initializationStatus.await() == TextToSpeech.SUCCESS) {
            "TextToSpeech 初始化失败"
        }
        check(platform.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE) {
            "中文 TTS 语言数据不可用"
        }
        val languageResult = platform.setLanguage(Locale.SIMPLIFIED_CHINESE)
        check(
            languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED &&
                languageResult != TextToSpeech.ERROR,
        ) {
            "中文 TTS 语言设置失败"
        }
        check(platform.setSpeechRate(1.0f) == TextToSpeech.SUCCESS) {
            "TTS 语速设置失败"
        }
    }

    override fun setAudioProfile(profile: SpeechAudioProfile): Result<Unit> = try {
        when (profile) {
            SpeechAudioProfile.ASSISTANT_SPEECH -> check(
                platform.setAudioAttributes(
                    contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
                    usage = AudioAttributes.USAGE_ASSISTANT,
                ) == TextToSpeech.SUCCESS,
            ) {
                "TTS 音频属性设置失败"
            }
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    override fun setListener(listener: TextToSpeechPort.Listener) {
        platform.setProgressListener(object : AndroidTextToSpeechProgressListener {
            override fun onStart(utteranceId: String) = listener.onStart(utteranceId)

            override fun onDone(utteranceId: String) = listener.onDone(utteranceId)

            override fun onError(utteranceId: String, errorCode: Int?) =
                listener.onError(utteranceId, errorCode)

            override fun onStop(utteranceId: String) = listener.onStop(utteranceId)
        })
    }

    override fun speak(text: String, utteranceId: String): Boolean = platform.speak(text, utteranceId)

    override fun stop() = platform.stop()

    override fun shutdown() = platform.shutdown()
}

internal fun interface AndroidTextToSpeechPlatformFactory {
    fun create(onInitialization: (Int) -> Unit): AndroidTextToSpeechPlatform
}

internal interface AndroidTextToSpeechPlatform {
    fun isLanguageAvailable(locale: Locale): Int
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float): Int
    fun setAudioAttributes(contentType: Int, usage: Int): Int
    fun setProgressListener(listener: AndroidTextToSpeechProgressListener)
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}

internal interface AndroidTextToSpeechProgressListener {
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, errorCode: Int?)
    fun onStop(utteranceId: String)
}

private class AndroidTextToSpeechPlatformAdapter(
    context: Context,
    onInitialization: (Int) -> Unit,
) : AndroidTextToSpeechPlatform {
    private val textToSpeech = TextToSpeech(context, onInitialization)

    override fun isLanguageAvailable(locale: Locale): Int = textToSpeech.isLanguageAvailable(locale)

    override fun setLanguage(locale: Locale): Int = textToSpeech.setLanguage(locale)

    override fun setSpeechRate(rate: Float): Int = textToSpeech.setSpeechRate(rate)

    override fun setAudioAttributes(contentType: Int, usage: Int): Int =
        textToSpeech.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(contentType)
                .setUsage(usage)
                .build(),
        )

    override fun setProgressListener(listener: AndroidTextToSpeechProgressListener) {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let(listener::onStart)
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(listener::onDone)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { listener.onError(it, null) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { listener.onError(it, errorCode) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let(listener::onStop)
            }
        })
    }

    override fun speak(text: String, utteranceId: String): Boolean =
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS

    override fun stop() {
        textToSpeech.stop()
    }

    override fun shutdown() {
        textToSpeech.shutdown()
    }
}
