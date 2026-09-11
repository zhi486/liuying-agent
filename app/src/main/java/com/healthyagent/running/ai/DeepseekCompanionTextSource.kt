package com.healthyagent.running.ai

import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DeepseekOutcome
import com.healthyagent.running.diagnostics.NoOpDiagnosticLogger
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.BundledVoiceCatalog
import com.healthyagent.running.speech.CompanionTextSource
import com.healthyagent.running.speech.SpeechRequest
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class DeepseekCompanionTextSource(
    private val callFactory: Call.Factory = defaultHttpClient(),
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
) : CompanionTextSource {
    override suspend fun create(request: SpeechRequest): String? {
        if (!request.companionEnabled || request.trigger.ttsPriority == SpeechPriority.URGENT || request.trigger.ruleId == "data_stale") return null
        val config = request.deepseekConfig ?: return null.also { record(DeepseekOutcome.MISSING_CONFIG) }
        val core = BundledVoiceCatalog.find(request.trigger.ruleId)?.text ?: request.trigger.speakText
        val prompt = buildJsonObject {
            put("固定核心", core)
            put("coachTone", config.coachTone.name)
            if (request.trigger.ruleId == "km_split") request.analysis?.let {
                put("split_data", Json.encodeToJsonElement(it.split_data))
                put("session_summary", Json.encodeToJsonElement(it.session_summary))
            }
        }
        val body = buildJsonObject {
            put("model", "deepseek-flash")
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("stream", false)
            put("max_tokens", 100)
            put("system", SYSTEM_PROMPT)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt.toString()) }) }
        }
        val call = try {
            callFactory.newCall(Request.Builder()
                .url("https://api.deepseek.com/anthropic/v1/messages")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build())
        } catch (_: Exception) {
            record(DeepseekOutcome.MISSING_CONFIG)
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val lock = Any()
            var finished = false
            fun finish(outcome: DeepseekOutcome, text: String? = null) = synchronized(lock) {
                if (!finished && continuation.isActive) {
                    finished = true
                    record(outcome)
                    continuation.resumeWith(Result.success(text))
                }
            }
            record(DeepseekOutcome.ATTEMPTED)
            continuation.invokeOnCancellation { cause ->
                synchronized(lock) {
                    if (!finished) {
                        finished = true
                        record(if (cause is TimeoutCancellationException) DeepseekOutcome.TIMEOUT else DeepseekOutcome.CANCELLED)
                    }
                }
                call.cancel()
            }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finish(if (e is InterruptedIOException) DeepseekOutcome.TIMEOUT else DeepseekOutcome.NETWORK)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!continuation.isActive) return
                                if (!it.isSuccessful) {
                                    finish(when (it.code) {
                                        401, 403 -> DeepseekOutcome.AUTH
                                        429 -> DeepseekOutcome.RATE_LIMIT
                                        else -> DeepseekOutcome.SERVER
                                    })
                                    return
                                }
                                val text = it.body?.let(::readLimited)?.let { raw -> companionText(raw, core) }
                                finish(if (text == null) DeepseekOutcome.INVALID_RESPONSE else DeepseekOutcome.SUCCESS, text)
                            }
                        } catch (_: InterruptedIOException) {
                            finish(DeepseekOutcome.TIMEOUT)
                        } catch (_: IOException) {
                            finish(DeepseekOutcome.NETWORK)
                        } catch (_: Exception) {
                            finish(DeepseekOutcome.INVALID_RESPONSE)
                        }
                    }
                })
            } catch (_: Exception) {
                call.cancel()
                finish(DeepseekOutcome.NETWORK)
            }
        }
    }

    private fun record(outcome: DeepseekOutcome) {
        runCatching { diagnosticLogger.record(DiagnosticEvent.DeepseekCompanionResult(outcome)) }
    }

    private fun readLimited(body: ResponseBody): String? {
        val length = body.contentLength()
        if (length > MAX_RESPONSE_BYTES) return null
        val buffer = Buffer()
        val source = body.source()
        val limit = if (length >= 0) length else MAX_RESPONSE_BYTES
        while (buffer.size < limit) {
            if (source.read(buffer, limit - buffer.size) == -1L) break
        }
        if (length >= 0 && buffer.size != length) return null
        // 未知长度不再读取第 65537 字节，达到上限时保守丢弃。
        if (length < 0 && buffer.size == MAX_RESPONSE_BYTES) return null
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
    }

    private fun companionText(raw: String, core: String): String? {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root.string("stop_reason") != "end_turn") return null
        val blocks = root["content"] as? JsonArray ?: return null
        if (blocks.isEmpty()) return null
        val text = buildString {
            for (element in blocks) {
                val block = element as? JsonObject ?: return null
                if (block.string("type") != "text") return null
                append(block.string("text") ?: return null)
            }
        }
        if (text.isBlank() || text.codePointCount(0, text.length) > 50) return null
        if (text.filter(Char::isLetterOrDigit).contains(core.filter(Char::isLetterOrDigit))) return null
        if (text.codePoints().anyMatch { point ->
                Character.UnicodeScript.of(point) != Character.UnicodeScript.HAN && point.toChar() !in " ，。！？、；…—"
            }) return null
        val numericText = NON_NUMERIC_PHRASES.fold(text) { value, phrase -> value.replace(phrase, "") }
        if (NUMBER_PATTERN.containsMatchIn(numericText) || ACTION_WORDS.any(text::contains)) return null
        val endings = text.count { it in "。！？" }
        if (endings > 1 || endings == 1 && text.trim().last() !in "。！？") return null
        return text.trim()
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal companion object {
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        fun defaultHttpClient() = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(2, TimeUnit.SECONDS).build()

        // 有限词法约束不等同于理解所有自然语言；健康判断始终属于本地规则。
        private val NUMBER_PATTERN = Regex("[零〇一二三四五六七八九十百千万两亿壹贰叁肆伍陆柒捌玖拾佰仟萬億]")
        private val NON_NUMERIC_PHRASES = listOf("一起", "一直", "一路", "一样", "一切", "唯一", "这一刻")
        private val ACTION_WORDS = listOf("加速", "减速", "慢点", "快点", "休息", "喝水", "补水", "呼吸", "心率", "血压", "安全", "危险", "健康", "停下", "坚持跑", "继续跑", "再跑", "再走")
        private val SYSTEM_PROMPT = """
            你是流萤，正陪开拓者跑步。只输出一条简短、自然、温柔的中文陪伴句，最多五十字。
            固定核心是已经确定的播报前句，只供理解场景。禁止重复、改写核心或其中的真实数字。
            coachTone 表示偏好语气，GENTLE 更柔和、BALANCED 自然、DIRECT 简短直接。
            禁止新增健康判断、运动动作或行动建议，不要求加速、减速、休息、喝水或调整呼吸。
            只表达陪伴与感受，不复述数据，不输出数字、JSON、括号动作、颜文字或多条句子。
        """.trimIndent()
    }
}
