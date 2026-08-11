package com.healthyagent.running.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.healthyagent.running.core.model.SpeechPriority
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android TTS 引擎 — 包装 android.speech.tts.TextToSpeech
 *
 * 使用 QUEUE_FLUSH 模式（SpeechBus 是唯一的排队者），
 * speak() 以 suspend 方式等待播放完成。
 */
class AndroidTtsEngine(context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var ready = false

    @Volatile
    override var isSpeaking: Boolean = false
        private set

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setSpeechRate(1.0f)
                val langResult = tts?.let {
                    if (it.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE) {
                        it.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    } else {
                        TextToSpeech.LANG_MISSING_DATA
                    }
                }
                if (langResult == TextToSpeech.LANG_MISSING_DATA) {
                    Timber.w("中文 TTS 语言数据不可用")
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        Timber.w("TTS 播放错误: utteranceId=$utteranceId")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        isSpeaking = false
                        Timber.w("TTS 播放错误: utteranceId=$utteranceId, errorCode=$errorCode")
                    }
                })
            } else {
                Timber.e("TextToSpeech 初始化失败: status=$status")
            }
        }
    }

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        val engine = tts ?: return Result.failure(IllegalStateException("TTS 未初始化"))
        if (!ready) return Result.failure(IllegalStateException("TTS 引擎未就绪"))

        return suspendCancellableCoroutine { continuation ->
            try {
                val utteranceId = "tts_${System.currentTimeMillis()}"
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.SUCCESS) {
                    isSpeaking = true
                    continuation.resume(Result.success(Unit))
                } else {
                    isSpeaking = false
                    continuation.resume(Result.failure(RuntimeException("TTS speak() 返回失败: $result")))
                }
            } catch (e: Exception) {
                isSpeaking = false
                continuation.resume(Result.failure(e))
            }
        }
    }

    override fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isSpeaking = false
        ready = false
    }
}
