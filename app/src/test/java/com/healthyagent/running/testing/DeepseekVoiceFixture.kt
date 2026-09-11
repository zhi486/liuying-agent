package com.healthyagent.running.testing

import com.healthyagent.running.ai.DeepseekCompanionTextSource
import com.healthyagent.running.speech.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.*
import kotlin.time.TestTimeSource
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.Timeout

/** 真总线、文字客户端、整句策略和焦点播放器；网络与媒体端口完全在内存模拟。 */
internal class DeepseekVoiceFixture(dispatcher: CoroutineDispatcher) {
    val clock = TestTimeSource()
    val logger = FakeDiagnosticLogger()
    val calls = mutableListOf<TextCall>()
    val fishTexts = mutableListOf<String>()
    val fishClients = mutableListOf<FishClient>()
    val localRules = mutableListOf<String>()
    val ports = mutableListOf<MediaPort>()
    val startedFiles = mutableListOf<File>()
    val onlineFile = File("test-online.mp3")
    val localFile = File("test-bundled.mp3")
    var fishConfigured = true
    var fishFails = false
    var holdFish = false
    var focusRequests = 0
    var focusReleases = 0
    var beforeMediaStart: suspend () -> Unit = {}

    private val source = DeepseekCompanionTextSource(Call.Factory { TextCall(it).also(calls::add) }, logger)
    private val focus = object : AudioFocusController {
        override fun request(): AudioFocusLease {
            focusRequests++
            return object : AudioFocusLease {
                override val granted = true
                override fun release() { focusReleases++ }
            }
        }
    }
    val engine = VoicePolicyTtsEngine(
        bundledStore = object : BundledVoiceStore {
            override suspend fun open(ruleId: String): File {
                localRules += ruleId
                return localFile
            }
        },
        onlineFactory = { if (fishConfigured) FishClient().also(fishClients::add) else null },
        companionTextSource = source,
        playerFactory = { PreparedVoicePlayer({ MediaPort().also(ports::add) }, focus, logger) },
        diagnosticLogger = logger,
        timeSource = clock,
    )
    val bus = SpeechBus(engine, SpeechPolicy(true, 0), diagnosticLogger = logger, dispatcher = dispatcher, timeSource = clock)

    inner class FishClient : FishSynthesisClient {
        val gate = CompletableDeferred<File>()
        var cancelCalls = 0
        var shutdownCalls = 0
        override suspend fun synthesize(text: String): File {
            fishTexts += text
            if (fishFails) error("模拟合成失败")
            return if (holdFish) gate.await() else onlineFile
        }
        override fun cancel() { cancelCalls++ }
        override fun shutdown() { shutdownCalls++ }
    }

    inner class MediaPort : AudioPlayerPort {
        lateinit var listener: AudioPlayerPort.Listener
        private lateinit var preparedFile: File
        override suspend fun prepare(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener): Result<Unit> {
            preparedFile = file
            this.listener = listener
            return Result.success(Unit)
        }
        override suspend fun startPrepared(startIfValid: (() -> Unit) -> Boolean): Result<Unit> {
            beforeMediaStart()
            startIfValid { startedFiles += preparedFile }
            return Result.success(Unit)
        }
        override fun start(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener): Result<Unit> =
            error("测试要求先准备再启动")
        override fun stop() = Unit
        override fun release() = Unit
    }

    class TextCall(private val request: Request) : Call {
        private lateinit var callback: Callback
        private var cancelled = false
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("测试禁止同步请求")
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = TextCall(request)
        fun prompt(): JsonObject {
            val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            return Json.parseToJsonElement(payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        }
        fun respond(text: String = "能陪着你，我很开心。", code: Int = 200): TrackingBody {
            val body = TrackingBody(buildJsonObject {
                put("stop_reason", "end_turn")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
            }.toString())
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("模拟响应").body(body).build())
            return body
        }
    }

    class TrackingBody(text: String) : ResponseBody() {
        private val buffer = Buffer().writeUtf8(text)
        private val length = buffer.size
        var closed = false
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun source(): BufferedSource = buffer
        override fun close() { closed = true }
    }
}
