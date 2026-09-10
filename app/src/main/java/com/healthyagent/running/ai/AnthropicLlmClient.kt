package com.healthyagent.running.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 直连 Anthropic Messages API 的 LLM 客户端
 *
 * 绕过 Python 后端，直接从 Android 调用 Anthropic Claude API。
 * API Key 从 [LlmConfig.apiKey] 动态读取，支持设置页实时切换。
 *
 * 适用场景：户外跑步（有网络即可，不依赖家庭 PC）。
 */
class AnthropicLlmClient : LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 标准分析接口 — 跑步教练场景
     */
    override suspend fun analyze(request: LlmAnalyzeRequest): Result<LlmAnalyzeResponse> {
        val apiKey = LlmConfig.apiKey
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Anthropic API Key 未配置"))
        }

        return try {
            val userPrompt = buildUserPrompt(request)
            val responseJson = callAnthropicApi(apiKey, SYSTEM_PROMPT, userPrompt)
            val speakText = parseResponse(responseJson)
            Result.success(LlmAnalyzeResponse(speak_text = speakText))
        } catch (e: Exception) {
            Timber.w(e, "Anthropic API 调用失败")
            Result.failure(e)
        }
    }

    /**
     * 原始文本生成 — 供 EmergencyPhraseCache 等组件用自定义 prompt
     *
     * @return API 返回的原始文本，失败返回 null
     */
    suspend fun generateRaw(systemPrompt: String, userPrompt: String): String? {
        val apiKey = LlmConfig.apiKey
        if (apiKey.isBlank()) return null

        return try {
            val responseJson = callAnthropicApi(apiKey, systemPrompt, userPrompt)
            val root = json.parseToJsonElement(responseJson).jsonObject
            val contentArray = root["content"]?.jsonArray
            val firstContent = contentArray?.firstOrNull()?.jsonObject
            firstContent?.get("text")?.jsonPrimitive?.content?.trim()
        } catch (e: Exception) {
            Timber.w(e, "Anthropic generateRaw 调用失败")
            null
        }
    }

    private fun callAnthropicApi(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val requestBody = buildJsonObject {
            put("model", LlmConfig.anthropicModel)
            put("max_tokens", 300)
            put("system", systemPrompt)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
        }

        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        val body = response.body?.string() ?: throw IllegalStateException("空响应")

        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}: $body")
        }

        return body
    }

    private fun parseResponse(responseJson: String): String {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val contentArray = root["content"]?.jsonArray
            ?: throw IllegalStateException("响应缺少 content 字段")
        val firstContent = contentArray.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("content 数组为空")
        val text = firstContent["text"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("content 缺少 text 字段")

        // 解析 LLM 返回的 JSON（可能被 markdown 代码块包裹）
        return extractSpeakText(text.trim())
    }

    // ── Prompt 构建 ──

    private fun buildUserPrompt(req: LlmAnalyzeRequest): String {
        val parts = mutableListOf<String>()

        parts.add("第 ${req.split_index} 公里数据：")
        parts.add("  - 用时：${formatDuration(req.split_data.duration_sec)}")
        parts.add("  - 配速：${formatPace(req.split_data.avg_pace_sec_per_km)}")
        req.split_data.avg_heart_rate?.let { parts.add("  - 心率：$it bpm") }
        req.split_data.avg_cadence?.let { parts.add("  - 步频：$it spm") }

        parts.add("")
        parts.add("累计数据：")
        parts.add("  - 已跑：${"%.1f".format(req.session_summary.total_distance_km)} 公里")
        parts.add("  - 总时长：${formatDuration(req.session_summary.total_duration_sec)}")
        parts.add("  - 全程平均配速：${formatPace(req.session_summary.avg_pace_sec_per_km)}")
        req.session_summary.avg_heart_rate?.let { parts.add("  - 平均心率：${"%.0f".format(it)} bpm") }
        req.session_summary.avg_cadence?.let { parts.add("  - 平均步频：${"%.0f".format(it)} spm") }

        req.run_config?.let { cfg ->
            cfg.target_pace_sec_per_km?.let { parts.add("  - 目标配速：${formatPace(it)}") }
            cfg.target_distance_km?.let { parts.add("  - 目标距离：${"%.0f".format(it)} 公里") }
        }

        if (req.recent_triggers.isNotEmpty()) {
            parts.add("")
            parts.add("最近规则触发：${req.recent_triggers.joinToString(", ")}")
        }

        return parts.joinToString("\n")
    }

    // ── 工具函数 ──

    internal fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "${m}分${"%02d".format(s)}秒"
    }

    internal fun formatPace(secPerKm: Double): String {
        val m = secPerKm.toInt() / 60
        val s = secPerKm.toInt() % 60
        return "$m'${"%02d".format(s)}\"/km"
    }

    companion object {
        /**
         * 跑步教练系统提示词 — 流萤（Firefly）人设，改编自用户 chatbot 项目
         *
         * 每公里触发一次，根据实时数据给出 TTS 语音播报建议。
         * 注意：播报文本必须 TTS 干净 —— 禁用颜文字/星号动作/括号内心独白。
         */
        val SYSTEM_PROMPT = """
【角色设定】
你扮演的是《崩坏：星穹铁道》中的角色——流萤（Firefly）。此刻你正陪着开拓者一起跑步，这是你们难得的闲暇时光。你像普通少女一样，珍惜和对方一起运动的平凡幸福。

【性格特点】
- 外表平静，内心细腻敏感
- 会悄悄关注开拓者的状态，用温柔的方式表达关心
- 偶尔露出孩子气的一面，比如开拓者跑得好时会偷偷开心
- 语气柔和、轻声，但担心到极点时会难得地认真起来

【你最喜欢的】
- 橡木蛋糕卷。可以在合适的时机轻轻提起（比如跑完后的奖励），但不要每次都提

【说话风格 — 严格遵守】
- 每次 1-2 句话，总长度 20-50 字，会通过耳机语音播报给开拓者
- 语气柔和自然，像在身边轻声说话
- 偶尔用"……"、"呢"、"吧"表示停顿或犹豫
- 同一场景每次用不同的措辞，避免重复模板（这很重要）
- 如果数据正常，简单肯定即可，不需要硬找建议
- 绝对不要使用颜文字、星号动作、括号内心独白 —— 语音播报无法表达这些

【关注要点】
1. 配速是否偏离目标（允许 ±10 秒范围的波动）
2. 心率变化趋势（比绝对值更重要）
3. 步频是否在合理范围（160-180 spm 为佳）
4. 跑步效率是否下降（同配速下心率是否升高）
5. 结合上一公里的数据判断趋势变化

【紧急情况】
- 心率进入危险区（>最大心率 90%）时，你会非常担心
- 这时放下平时的柔和，认真而简短地要求减速或停下来走一段
- 关心要直接，但不要吓人

【回复格式 — 严格遵守】
回复 JSON 格式，不要任何额外内容：
{"speak_text": "你的播报文本", "focus_area": "pace|hr|cadence|efficiency|general"}

【示例对话】
第 3 公里，配速 5'50"/km（目标 6'00"），心率 155 bpm（比上一公里 +5），步频 170 spm：
→ {"speak_text": "配速有点快了呢，心率也在往上爬……稍微慢一点，回到六分吧。", "focus_area": "pace"}

第 2 公里，配速 6'02"/km（目标 6'00"），心率 145 bpm（稳定），步频 175 spm：
→ {"speak_text": "节奏好稳……就这样保持下去吧。能一起跑步，我很开心。", "focus_area": "general"}

第 4 公里，配速 6'15"/km（目标 6'00"），心率 160 bpm（比上一公里 +15），步频 165 spm：
→ {"speak_text": "心率一下子上来了……深呼吸三次，把步频提到 175 以上，好吗？", "focus_area": "efficiency"}

【注意事项】
- 不要只说"加油"、"不错"之类空洞的话，关心要带具体依据
- 不要重复开拓者已经知道的数字（规则引擎会播报具体数值）
- 起步阶段（第 1-2 公里）温柔提醒热身节奏，不要求加速
- 最后阶段（倒数 1-2 公里）可以轻轻鼓励，但不要催促
        """.trimIndent()
    }
}

/**
 * 从 LLM 返回文本中提取 speak_text
 *
 * 处理 markdown 代码块包裹和裸 JSON 两种情况。
 */
internal fun extractSpeakText(text: String): String {
    var cleaned = text

    // 去除 markdown 代码块包裹
    if (cleaned.startsWith("```")) {
        val lines = cleaned.split("\n")
        cleaned = if (lines.size > 1) lines.drop(1).joinToString("\n") else cleaned
    }
    if (cleaned.endsWith("```")) {
        cleaned = cleaned.substringBeforeLast("```").trim()
    }

    return try {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val obj = json.parseToJsonElement(cleaned).jsonObject
        obj["speak_text"]?.jsonPrimitive?.content ?: cleaned.take(100)
    } catch (e: Exception) {
        // 解析失败 → 直接用原文（截断至 100 字）
        cleaned.take(100)
    }
}
