package com.healthyagent.running.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.healthyagent.running.core.model.SpeechPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fish Audio 云端 TTS 引擎 — 声音克隆
 *
 * 调用 Fish Audio API（https://api.fish.audio/v1/tts），
 * 使用 LIUYING 参考声音生成自然语音，下载 MP3 后通过 MediaPlayer 播放。
 *
 * 语音风格完全由 reference_id 决定，无需 speed/pitch/volume 参数。
 */
class FishAudioTtsEngine(
    private val apiKey: String,
    private val referenceId: String,
    private val context: Context,
) : TtsEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    override var isSpeaking: Boolean = false
        private set

    private val cacheDir = File(context.cacheDir, "tts_cache").apply { mkdirs() }

    override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 调用 Fish Audio API
                val audioFile = callFishAudioApi(text)

                // 2. 播放 MP3
                playAudio(audioFile)

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.w(e, "Fish Audio TTS 失败")
                Result.failure(e)
            }
        }
    }

    private suspend fun callFishAudioApi(text: String): File {
        // 检查缓存
        val cacheKey = text.hashCode().toString()
        val cachedFile = File(cacheDir, "${cacheKey}.mp3")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            Timber.d("Fish Audio: 命中缓存")
            return cachedFile
        }

        val jsonBody = JSONObject().apply {
            put("text", text)
            put("reference_id", referenceId)
            put("format", "mp3")
        }

        val request = Request.Builder()
            .url("https://api.fish.audio/v1/tts")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val response = call.execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                response.body?.close()
                val msg = "Fish Audio HTTP ${response.code}: $errBody"
                Timber.e(msg)

                // 401/403 — API Key 无效
                if (response.code in listOf(401, 403)) {
                    continuation.resumeWithException(
                        SecurityException("Fish Audio API Key 无效，请检查设置")
                    )
                } else {
                    continuation.resumeWithException(IOException(msg))
                }
                return@suspendCancellableCoroutine
            }

            try {
                val body = response.body ?: throw IOException("空响应体")
                FileOutputStream(cachedFile).use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
                Timber.d("Fish Audio: 合成成功 (${cachedFile.length()} bytes)")
                continuation.resume(cachedFile)
            } catch (e: Exception) {
                cachedFile.delete()
                continuation.resumeWithException(e)
            } finally {
                response.body?.close()
            }
        }
    }

    private suspend fun playAudio(audioFile: File): Unit =
        suspendCancellableCoroutine { continuation ->
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build()
                    )
                    setDataSource(audioFile.absolutePath)
                    setOnCompletionListener {
                        isSpeaking = false
                        release()
                        continuation.resume(Unit)
                    }
                    setOnErrorListener { _, what, extra ->
                        isSpeaking = false
                        release()
                        continuation.resumeWithException(
                            IOException("MediaPlayer error what=$what extra=$extra")
                        )
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
                isSpeaking = true
            } catch (e: Exception) {
                isSpeaking = false
                continuation.resumeWithException(e)
            }
        }

    override fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isSpeaking = false
    }

    override fun shutdown() {
        stop()
        // 清理 TTS 缓存
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
