package com.healthyagent.running.ai

import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DeepseekOutcome
import com.healthyagent.running.diagnostics.CompanionRejection
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
        val kilometer = request.trigger.ruleId == "km_split"
        val prompt = buildJsonObject {
            put("固定核心", core)
            put("coachTone", config.coachTone.name)
            if (kilometer) {
                put("本地核定建议", request.kilometerGuidance.text)
                put("建议类型", request.kilometerGuidance.name)
            }
            if (request.trigger.ruleId == "km_split") request.analysis?.let {
                put("split_data", Json.encodeToJsonElement(it.split_data))
                put("session_summary", Json.encodeToJsonElement(it.session_summary))
            }
        }
        val body = buildJsonObject {
            put("model", "deepseek-flash")
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("stream", false)
            put("max_tokens", if (kilometer) 240 else 100)
            put("system", if (kilometer) KILOMETER_PROMPT else SYSTEM_PROMPT)
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
            fun finish(outcome: DeepseekOutcome, text: String? = null, rejection: CompanionRejection? = null) = synchronized(lock) {
                if (!finished && continuation.isActive) {
                    finished = true
                    record(outcome, rejection)
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
                                var rejection = CompanionRejection.FORMAT
                                val text = it.body?.let(::readLimited)?.let { raw -> companionText(raw, core, kilometer, request.kilometerGuidance.text) { reason -> rejection = reason } }
                                finish(if (text == null) DeepseekOutcome.INVALID_RESPONSE else DeepseekOutcome.SUCCESS, text, if (text == null) rejection else null)
                            }
                        } catch (_: InterruptedIOException) {
                            finish(DeepseekOutcome.TIMEOUT)
                        } catch (_: IOException) {
                            finish(DeepseekOutcome.NETWORK)
                        } catch (_: Exception) {
                            finish(DeepseekOutcome.INVALID_RESPONSE, rejection = CompanionRejection.FORMAT)
                        }
                    }
                })
            } catch (_: Exception) {
                call.cancel()
                finish(DeepseekOutcome.NETWORK)
            }
        }
    }

    private fun record(outcome: DeepseekOutcome, rejection: CompanionRejection? = null) {
        runCatching { diagnosticLogger.record(DiagnosticEvent.DeepseekCompanionResult(outcome, rejection)) }
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

    private fun companionText(raw: String, core: String, kilometer: Boolean, guidance: String, rejected: (CompanionRejection) -> Unit): String? {
        fun reject(reason: CompanionRejection): String? { rejected(reason); return null }
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root.string("stop_reason") != "end_turn") return reject(CompanionRejection.INCOMPLETE)
        val blocks = root["content"] as? JsonArray ?: return null
        if (blocks.isEmpty()) return null
        var text = buildString {
            for (element in blocks) {
                val block = element as? JsonObject ?: return null
                if (block.string("type") != "text") return reject(CompanionRejection.NON_TEXT)
                append(block.string("text") ?: return null)
            }
        }
        val narration = text.trim()
        if (kilometer) {
            // 只核验模型返回的整段，不在这里补写核心或运动指令。
            val requiredPrefix = core + guidance
            if (!narration.startsWith(requiredPrefix)) return reject(CompanionRejection.FORMAT)
            text = narration.removePrefix(requiredPrefix).trim()
        }
        if (text.isBlank() || text.codePointCount(0, text.length) > 50) return reject(CompanionRejection.LENGTH)
        if (text.codePoints().anyMatch { point ->
                Character.UnicodeScript.of(point) != Character.UnicodeScript.HAN && point.toChar() !in " ，。！？、；…—"
            }) return reject(CompanionRejection.CHARACTERS)
        if (text.isBlank() || text.filter(Char::isLetterOrDigit).contains(core.filter(Char::isLetterOrDigit))) return reject(CompanionRejection.REPEATED_CORE)
        if (kilometer) {
            // 只识别重新播报的分句或指标数值，不把自然提及“配速”当作重复。
            val clauses = core.split(Regex("[，。！？；]"))
                .map { it.filter(Char::isLetterOrDigit) }.filter(String::isNotEmpty).toSet()
            if (text.split(Regex("[，。！？；]")).any { it.filter(Char::isLetterOrDigit) in clauses } ||
                REPEATED_METRIC.containsMatchIn(text.replace(" ", ""))) return reject(CompanionRejection.REPEATED_CORE)
        } else {
            val numericText = text.replace(" ", "")
            val knownQuantities = NUMBER_PATTERN.findAll(core.replace(" ", "")).map { it.value }.toSet()
            if (NUMBER_PATTERN.findAll(numericText).any { it.value !in knownQuantities }) return reject(CompanionRejection.NUMBERS)
        }
        if (ACTION_WORDS.any(text::contains)) return reject(CompanionRejection.ACTION)
        if (kilometer && KILOMETER_ACTION_WORDS.any(text::contains)) return reject(CompanionRejection.ACTION)
        return if (kilometer) narration else text.trim()
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal companion object {
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        fun defaultHttpClient() = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            // 公里鼓励允许较长生成时间；普通提示仍由语音策略限制为两秒。
            .callTimeout(6, TimeUnit.SECONDS).build()

        // 有限词法约束不等同于理解所有自然语言；健康判断始终属于本地规则。
        private const val CHINESE_NUMBER = "[零〇一二三四五六七八九十百千万两亿壹贰叁肆伍陆柒捌玖拾佰仟萬億半几数]+"
        private val REPEATED_METRIC = Regex("(?:用时|配速)(?:为|是)?(?:每公里)?$CHINESE_NUMBER(?:小时|钟头|分钟|分|秒)")
        // 只拒绝核心未出现的数量表达；这不是对整句语义的真实性证明。
        // 复合时长整体匹配，避免把“六分三十秒”误当成核心中的“六分”和“三十秒”。
        private val NUMBER_PATTERN = Regex(
            "$CHINESE_NUMBER(?:小时|钟头)(?:${CHINESE_NUMBER}分(?:钟)?(?:${CHINESE_NUMBER}秒)?)?(?:半)?" +
                "|$CHINESE_NUMBER(?:分(?:钟)?)(?:${CHINESE_NUMBER}秒)?(?:半)?" +
                "|$CHINESE_NUMBER(?:点$CHINESE_NUMBER)?(?:余|多)?(?:公里|千米|米|秒|千卡|大卡|卡路里|公斤|千克|斤|圈|趟|倍)" +
                "|(?:百分之|千分之)$CHINESE_NUMBER|(?<!每)${CHINESE_NUMBER}步",
        )
        private val ACTION_WORDS = listOf("加速", "减速", "慢点", "快点", "休息", "喝水", "补水", "呼吸", "心率", "血压", "安全", "危险", "健康", "停下", "坚持跑", "继续跑", "再跑", "再走")
        private val KILOMETER_ACTION_WORDS = listOf("放慢", "加快", "提速", "冲刺", "放心跑", "不用停")
        private val KILOMETER_PROMPT = """
            你是陪开拓者跑步的流萤。输出可直接朗读的完整公里播报，不是仅输出后半句。
            整段必须依次包含：固定核心原文一次、本地核定建议原文一次（为空则省略）、自然连贯的陪伴话语。
            核心与建议的数字、单位、顺序及标点必须逐字保持，这是手机已经核验的事实和允许建议。不要加标题、引号或JSON。
            陪伴部分可多句、总共最多五十字，根据本地建议安排情绪，不要重复公里／用时／配速播报。
            无本地建议时不自行判断运动强度；有放慢建议时不得催促加速。不得新增休息、补水、呼吸等动作建议或健康判断。
            coachTone为语气偏好，GENTLE温柔、BALANCED自然、DIRECT简短。摘要仅供理解，不要新增或改写运动数据。
            只输出中文和中文标点；必须返回整段。App将直接交给语音合成，不会再拼接核心。
        """.trimIndent()
        private val SYSTEM_PROMPT = """
            你是流萤，正陪开拓者跑步。只输出一段简短、自然、温柔的中文陪伴话语，总共最多五十字，可以包含多句。
            App会在你的回答前自动加上固定核心，你只输出后面的陪伴话语，不要从“第几公里、用时、配速”开始重新播报，也不要复制核心中的任何播报分句。
            陪伴话语中可以自然提及核心已有的数量与单位，但不能改写数值或新增数据。
            coachTone 表示偏好语气，GENTLE 更柔和、BALANCED 自然、DIRECT 简短直接。
            禁止新增健康判断、运动动作或行动建议，不要求加速、减速、休息、喝水或调整呼吸。
            以陪伴与感受为主，引用数量时沿用固定核心的中文写法，不输出JSON、括号动作或颜文字。
            可以自然使用“每一步”“一点点进步”“又一次并肩”等非数值表达。
        """.trimIndent()
    }
}
