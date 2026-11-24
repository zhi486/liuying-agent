package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.voice.VoiceTranscriber
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** 录音只发到已登记的 HTTPS origin，Bearer 不进入网页；没有重试和重定向。 */
class CloudVoiceHttpClient(
    private val configuration: () -> CloudTaskIdentity?,
    private val credentials: CloudCredentialSource,
    private val calls: Call.Factory = CloudTaskHttpClient.clientBuilder().build(),
) : VoiceTranscriber {
    override suspend fun transcribe(audio: ByteArray): String = withContext(Dispatchers.IO) {
        require(audio.size in 3200..960000 && audio.size % 2 == 0)
        val identity = configuration() ?: error("在线配置不可用")
        val bearer = credentials.read(identity)
        val request = Request.Builder().url("${identity.origin}/v1/agent/voice")
            .header("Authorization", "Bearer ${bearer.value}").header("X-Voice-Consent", "baidu-asr-v1")
            .header("Cache-Control", "no-store").tag(AtomicBoolean::class.java, AtomicBoolean(false))
            .post(object : RequestBody() {
                override fun contentType() = "audio/pcm;rate=16000".toMediaType()
                override fun contentLength() = audio.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(audio) }
            }).build()
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(IOException("语音服务暂不可用")) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            check(it.code == 200)
                            val body = checkNotNull(it.body)
                            check(body.contentType()?.let { type -> type.type == "application" && type.subtype == "json" } == true)
                            val bytes = body.byteStream().use { stream -> stream.readCloudBytes(16384) }
                            val value = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()).jsonObject
                            require(value.keys == setOf("text"))
                            val result = value["text"] as? JsonPrimitive ?: error("识别结果无效")
                            require(result.isString && result.content.isNotBlank() && result.content.length <= 2000)
                            result.content
                        }
                        continuation.resume(text)
                    } catch (_: Exception) { continuation.resumeWithException(IOException("语音服务暂不可用")) }
                }
            })
        }
    }
}
