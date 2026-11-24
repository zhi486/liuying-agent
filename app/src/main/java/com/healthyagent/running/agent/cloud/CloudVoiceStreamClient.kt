package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.voice.PcmStreamer
import com.healthyagent.running.agent.voice.VoiceStream
import com.healthyagent.running.agent.voice.VoiceConnectionFailure
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** 回调在调用方协程执行；录音帧不进入网页、不持久化、不重连重发。 */
class CloudVoiceStreamClient(
    private val configuration: () -> CloudTaskIdentity?,
    private val credentials: CloudCredentialSource,
    private val recorder: PcmStreamer,
    private val sockets: WebSocket.Factory = CloudTaskHttpClient.clientBuilder().build(),
) : VoiceStream {
    override suspend fun recognize(stop: AtomicBoolean, ready: suspend () -> Unit,
        preview: suspend (String) -> Unit, finishing: suspend () -> Unit): String = coroutineScope {
        val (identity, bearer) = withContext(Dispatchers.IO) {
            val value = configuration() ?: error("在线配置不可用")
            value to credentials.read(value)
        }
        val request = Request.Builder().url("${identity.origin}/v1/agent/voice/stream")
            .header("Authorization", "Bearer ${bearer.value}")
            .header("X-Voice-Consent", "fun-asr-v1")
            .tag(AtomicBoolean::class.java, AtomicBoolean(false)).build()
        val messages = Channel<String>(32)
        val socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 16384 || messages.trySend(text).isFailure) {
                    messages.close(IOException("语音响应过快或过长")); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messages.close(IOException("语音协议无效")); webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                response?.close(); messages.close(VoiceConnectionFailure(status))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, "结束"); messages.close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        })
        try {
            val first = withTimeout(15_000) { Json.parseToJsonElement(messages.receive()).jsonObject }
            require(first.keys == setOf("type") && first["type"] == JsonPrimitive("ready"))
            ready()
            val finishSent = AtomicBoolean(false)
            val results = async {
                var count = 0
                for (raw in messages) {
                    check(++count <= 1000)
                    val value = Json.parseToJsonElement(raw).jsonObject
                    require(value.keys == setOf("type", "text"))
                    val text = value["text"] as? JsonPrimitive ?: error("识别结果无效")
                    require(text.isString && text.content.length <= 2000)
                    when (value["type"]?.jsonPrimitive?.content) {
                        "preview" -> preview(text.content)
                        "completed" -> {
                            require(finishSent.get() && text.content.isNotBlank())
                            return@async text.content
                        }
                        else -> error("识别未完成")
                    }
                }
                error("语音连接提前结束")
            }
            recorder.record(stop) { frame ->
                ensureActive()
                require(frame.size in 2..4096 && frame.size % 2 == 0)
                check(socket.queueSize() <= 64000 && socket.send(frame.toByteString()))
            }
            ensureActive(); finishing(); finishSent.set(true)
            check(socket.send("{\"type\":\"finish\"}"))
            withTimeout(15_000) { results.await() }
        } finally { socket.cancel(); messages.cancel() }
    }
}
