package com.healthyagent.running.agent.voice

import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

fun interface VoiceRecorder { suspend fun record(stop: AtomicBoolean): ByteArray }
fun interface VoiceTranscriber { suspend fun transcribe(audio: ByteArray): String }
fun interface VoiceStream {
    suspend fun recognize(stop: AtomicBoolean, ready: suspend () -> Unit,
        preview: suspend (String) -> Unit, finishing: suspend () -> Unit): String
}
fun interface PcmStreamer { suspend fun record(stop: AtomicBoolean, chunk: suspend (ByteArray) -> Unit) }

/** 仅在主线程操作会话；录音和网络由适配器转到 IO，不持久化录音或识别正文。 */
class VoiceInputService(
    private val stream: VoiceStream,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    // 保留原短语音适配器及既有测试入口；生产依赖改为流式，不自动回退供应商。
    constructor(recorder: VoiceRecorder, transcriber: VoiceTranscriber,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) : this(
        VoiceStream { stop, ready, _, finishing ->
            ready()
            val audio = withTimeout(35_000) { recorder.record(stop) }
            try {
                require(audio.size in 3200..960000 && audio.size % 2 == 0)
                finishing(); withTimeout(30_000) { transcriber.transcribe(audio) }
            } finally { audio.fill(0) }
        }, scope)
    private var id: String? = null
    private var state = "idle"
    private var text: String? = null
    private var preview: String? = null
    private var error: String? = null
    private var stop = AtomicBoolean(false)
    private var job: Job? = null
    private val permission = MutableStateFlow<String?>(null)
    val permissionRequested = permission.asStateFlow()

    fun handle(name: String, body: JsonObject): CommandOutcome {
        validate(name, body)
        val requested = body.getValue("sessionId").jsonPrimitive.content
        if (name == "voice.begin") {
            if (id != null) return CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "请先结束上一次语音输入")
            id = requested; state = "permission"; text = null; preview = null; error = null
            stop = AtomicBoolean(false); permission.value = requested
        } else if (id != requested) {
            return CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "语音输入已结束，请重新开始")
        } else when (name) {
            "voice.finish" -> if (state == "recording") { state = "transcribing"; stop.set(true) }
            "voice.cancel" -> cancel()
        }
        return CommandOutcome.AcceptedWithData(buildJsonObject {
            put("sessionId", requested); put("state", state)
            put("text", text?.let(::JsonPrimitive) ?: JsonNull)
            put("preview", preview?.let(::JsonPrimitive) ?: JsonNull)
            put("error", error?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    fun permissionResult(sessionId: String, granted: Boolean) {
        if (id != sessionId || state != "permission") return
        permission.value = null
        if (!granted) { state = "failed"; error = "未获得麦克风权限，可继续文字输入"; return }
        state = "connecting"
        val flag = stop
        job = scope.launch {
            try {
                val result = withTimeout(55_000) {
                    stream.recognize(flag,
                        ready = { ensureActive(); if (id == sessionId) state = "recording" },
                        preview = { value ->
                            ensureActive(); require(value.length <= 2000)
                            if (id == sessionId) preview = value.ifEmpty { null }
                        },
                        finishing = { ensureActive(); if (id == sessionId) state = "transcribing" },
                    )
                }.trim()
                ensureActive()
                require(result.isNotEmpty() && result.length <= 2000)
                if (id == sessionId) { text = result; state = "completed" }
            } catch (cancelled: CancellationException) {
                if (id == sessionId && cancelled is TimeoutCancellationException) {
                    state = "failed"; error = "语音输入超时，请重试；不会自动重发"
                }
            } catch (failure: VoiceConnectionFailure) {
                if (id == sessionId) {
                    state = "failed"; error = failure.userMessage
                }
            } catch (_: Exception) {
                if (id == sessionId) {
                    state = "failed"; error = "语音识别未完成，请检查网络、在线配置与麦克风；不会自动重发"
                }
            } finally { if (id == sessionId) preview = null }
        }
    }

    fun cancel() {
        id = null; state = "idle"; text = null; preview = null; error = null; permission.value = null
        stop.set(true); job?.cancel(); job = null
    }

    companion object {
        fun validate(name: String, body: JsonObject) {
            require(name in setOf("voice.begin", "voice.status", "voice.finish", "voice.cancel"))
            require(body.keys == if (name == "voice.begin") setOf("sessionId", "approved") else setOf("sessionId"))
            val id = body["sessionId"] as? JsonPrimitive ?: error("语音会话无效")
            require(id.isString && Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id.content))
            if (name == "voice.begin") require(body["approved"] == JsonPrimitive(true))
        }
    }
}
