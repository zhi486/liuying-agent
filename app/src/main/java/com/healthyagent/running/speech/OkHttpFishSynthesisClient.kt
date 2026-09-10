package com.healthyagent.running.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OkHttpFishSynthesisClient(
    private val apiKey: String,
    private val referenceId: String,
    private val cacheDirectory: File,
    private val callFactory: Call.Factory = defaultCallFactory(),
    private val readCache: Boolean = true,
) : FishSynthesisClient {
    private val currentOperation = AtomicReference<SynthesisOperation?>(null)

    override suspend fun synthesize(text: String): File = withContext(Dispatchers.IO) {
        val operation = SynthesisOperation()
        val operationContext = currentCoroutineContext()
        currentOperation.set(operation)
        cacheDirectory.mkdirs()
        val cachedFile = File(cacheDirectory, "${cacheKey(text)}.mp3")
        if (readCache && cachedFile.isFile && cachedFile.length() > 0L) {
            currentOperation.compareAndSet(operation, null)
            return@withContext cachedFile
        }

        val partialFile = File(cacheDirectory, "${cachedFile.name}.${operation.partialId}.part")
        val requestBody = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("reference_id", JsonPrimitive(referenceId))
            put("format", JsonPrimitive("mp3"))
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(FISH_TTS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        var completed = false

        try {
            val call = callFactory.newCall(request)
            operation.call.set(call)
            if (!operationContext.isActive) {
                operation.cancel()
            }
            if (operation.isCancelled()) {
                call.cancel()
                throw CancellationException("Fish Audio 合成已取消")
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw statusFailure(response.code)
                }
                val body = response.body ?: throw IOException("Fish Audio 响应为空")
                FileOutputStream(partialFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            if (partialFile.length() <= 0L) {
                throw IOException("Fish Audio 响应为空")
            }
            synchronized(CACHE_PUBLISH_LOCK) {
                if (!operationContext.isActive) {
                    operation.cancel()
                }
                if (!operation.beginPublishing()) {
                    throw CancellationException("Fish Audio 合成已取消")
                }
                Files.move(
                    partialFile.toPath(),
                    cachedFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            completed = true
            cachedFile
        } catch (error: IOException) {
            if (operation.isCancelled() || !operationContext.isActive) {
                throw CancellationException("Fish Audio 合成已取消").apply { initCause(error) }
            }
            throw error
        } finally {
            if (!completed) {
                partialFile.delete()
            }
            currentOperation.compareAndSet(operation, null)
        }
    }

    override fun cancel() {
        val operation = currentOperation.get() ?: return
        if (operation.cancel()) {
            operation.call.get()?.cancel()
        }
    }

    override fun shutdown() {
        cancel()
    }

    private fun statusFailure(code: Int): Exception = when (code) {
        401, 403 -> SecurityException("Fish Audio 请求失败（HTTP 4xx）")
        in 400..499 -> IOException("Fish Audio 请求失败（HTTP 4xx）")
        in 500..599 -> IOException("Fish Audio 请求失败（HTTP 5xx）")
        else -> IOException("Fish Audio 请求返回非成功状态")
    }

    private fun cacheKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(referenceId.encodeToByteArray())
        digest.update(0.toByte())
        return digest.digest(text.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class SynthesisOperation {
        private val state = AtomicReference(SynthesisState.ACTIVE)
        val call = AtomicReference<Call?>(null)
        val partialId: String = UUID.randomUUID().toString()

        fun cancel(): Boolean = state.compareAndSet(SynthesisState.ACTIVE, SynthesisState.CANCELLED)

        fun beginPublishing(): Boolean = state.compareAndSet(SynthesisState.ACTIVE, SynthesisState.PUBLISHING)

        fun isCancelled(): Boolean = state.get() == SynthesisState.CANCELLED
    }

    private enum class SynthesisState {
        ACTIVE,
        CANCELLED,
        PUBLISHING,
    }

    companion object {
        private const val FISH_TTS_URL = "https://api.fish.audio/v1/tts"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CACHE_PUBLISH_LOCK = Any()

        private fun defaultCallFactory(): Call.Factory = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
