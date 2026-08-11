package com.healthyagent.running.ai

import com.healthyagent.running.core.model.RunConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 紧急指令修饰语缓存 — 跑步开始前 LLM 预生成，触发时零延迟播报
 *
 * 设计思路：
 * 1. 跑步开始时，一次性生成所有紧急场景的修饰语（1 次 LLM 调用）
 * 2. 规则触发时，从缓存取出对应修饰语，替换硬编码文本
 * 3. 取用后异步回填该槽位（单场景 LLM 调用），为下次触发准备
 *
 * 效果：紧急指令播报零 LLM 延迟（<50ms 取缓存），用完即补。
 */
class EmergencyPhraseCache(
    private val llmClient: LlmClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 公共 API ──

    /**
     * 跑步开始时调用：一次性预生成所有紧急场景的修饰语
     */
    fun pregenerate(config: RunConfig) {
        scope.launch {
            try {
                val prompt = buildPregenerationPrompt(config)
                val response = (llmClient as? AnthropicLlmClient)?.generateRaw(
                    systemPrompt = PREGEN_SYSTEM_PROMPT,
                    userPrompt = prompt,
                ) ?: return@launch

                parsePhrases(response).forEach { (key, text) ->
                    cache[key] = text
                }
                Timber.i("EmergencyPhraseCache: 预生成了 ${cache.size} 条修饰语")
            } catch (e: Exception) {
                Timber.w(e, "EmergencyPhraseCache: 预生成失败，将使用规则默认文本")
            }
        }
    }

    /**
     * 获取并消耗（取后即删）指定规则的修饰语
     *
     * 调用后自动触发异步回填。
     *
     * @return 修饰后的短语，缓存为空时返回 null（调用方应使用规则默认文本）
     */
    fun consume(ruleId: String): String? {
        val phrase = cache.remove(ruleId)
        if (phrase != null) {
            // 异步回填：为下次触发准备新的修饰语
            scope.launch {
                try {
                    refillSlot(ruleId)
                } catch (e: Exception) {
                    Timber.w(e, "EmergencyPhraseCache: 回填 $ruleId 失败")
                }
            }
        }
        return phrase
    }

    /** 清空缓存（跑步结束时调用） */
    fun clear() {
        cache.clear()
    }

    // ── 内部实现 ──

    /**
     * 回填单个槽位
     */
    private suspend fun refillSlot(ruleId: String) {
        val scenarioLabel = scenarioLabel(ruleId)
        val prompt = "请为以下跑步紧急场景生成一条新的语音播报文本：$scenarioLabel。要求与上一次表述不同，保持简洁有力。"

        val response = (llmClient as? AnthropicLlmClient)?.generateRaw(
            systemPrompt = PHRASE_SYSTEM_PROMPT,
            userPrompt = prompt,
        ) ?: return

        val text = extractSinglePhrase(response)
        if (text != null) {
            cache[ruleId] = text
            Timber.d("EmergencyPhraseCache: $ruleId 已回填")
        }
    }

    /**
     * 解析预生成的短语列表
     *
     * 期望格式：
     * ```json
     * {"phrases": [{"key": "hr_zone5", "text": "..."}, ...]}
     * ```
     */
    private fun parsePhrases(response: String): List<Pair<String, String>> {
        val cleaned = extractSpeakText(response).let {
            // 预生成的响应可能是纯 JSON，不包裹在 speak_text 里
            if (it.startsWith("{")) it else response
        }
        return try {
            val root = json.parseToJsonElement(cleaned).jsonObject
            root["phrases"]?.jsonArray?.map { elem ->
                val obj = elem.jsonObject
                val key = obj["key"]?.jsonPrimitive?.content ?: ""
                val text = obj["text"]?.jsonPrimitive?.content ?: ""
                key to text
            }?.filter { it.first.isNotEmpty() } ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "EmergencyPhraseCache: 解析预生成响应失败")
            emptyList()
        }
    }

    /**
     * 从单条回填响应中提取文本
     */
    private fun extractSinglePhrase(response: String): String? {
        val cleaned = extractSpeakText(response)
        return if (cleaned.length in 5..60) cleaned else null
    }

    private fun buildPregenerationPrompt(config: RunConfig): String {
        val paceMin = (config.targetPaceSecPerKm?.toInt() ?: 360) / 60
        val paceSec = (config.targetPaceSecPerKm?.toInt() ?: 360) % 60
        val paceSecStr = if (paceSec < 10) "0$paceSec" else paceSec.toString()
        val distanceKm = ((config.targetDistanceMeters ?: 5000.0) / 1000.0).toInt()
        return """
跑步配置：目标配速 ${paceMin}'$paceSecStr/km，目标距离 $distanceKm 公里。

请为以下 8 个跑步紧急场景各生成 1 条中文语音播报文本：
- hr_zone5: 心率进入第 5 区间（危险区）
- hr_rapid_rise: 心率快速上升
- hr_too_low: 心率偏低（热身不足）
- cadence_drop: 步频骤降（疲劳）
- cadence_too_high: 步频过高（小碎步）
- pace_deviation: 配速偏离目标
- start_too_fast: 起步过快
- efficiency_abnormal: 跑步效率下降

要求：
1. 每条 5-20 字，简洁有力
2. 语气果断但不吓人，像专业教练
3. 每条表述不同，避免模板化
4. 包含具体行动建议（如"减速"、"深呼吸"、"收紧核心"等）

返回 JSON 格式：{"phrases": [{"key": "场景ID", "text": "播报文本"}, ...]}
        """.trimIndent()
    }

    private fun scenarioLabel(ruleId: String): String = when (ruleId) {
        "hr_zone5" -> "心率进入危险区"
        "hr_rapid_rise" -> "心率快速上升"
        "hr_too_low" -> "心率偏低（热身不足）"
        "cadence_drop" -> "步频骤降（可能疲劳）"
        "cadence_too_high" -> "步频过高（小碎步、效率低）"
        "pace_deviation" -> "配速偏离目标"
        "start_too_fast" -> "起步过快"
        "efficiency_abnormal" -> "跑步效率异常下降"
        else -> ruleId
    }

    companion object {
        /**
         * 预生成使用的系统提示词 — 批量生成紧急场景修饰语
         *
         * 跑步开始前调用，一次性生成所有场景的备选短语。
         * 这些短语会在规则触发时直接播报，实现零 LLM 延迟。
         */
        val PREGEN_SYSTEM_PROMPT = """
【角色设定】
你是一位经验丰富的跑步教练。跑者即将开始跑步，你需要为可能出现的各种紧急情况提前准备好简洁的语音指令。

【要求】
- 每条指令 5-20 字，适合 TTS 语音播报
- 语气果断但不吓人，像教练在耳边提醒
- 每条必须有具体行动建议（如"减速"、"深呼吸"、"收紧核心"、"缩小步幅"等）
- 8 个场景的措辞要有区分度，不要出现相似的句子
- 同一场景如果被多次触发，每次播报的文本应该不同（但这是批量生成，你只需保证初始 8 条各有特点）

【回复格式 — 严格遵守】
纯 JSON，不要额外内容：{"phrases": [{"key": "场景ID", "text": "播报文本"}, ...]}

【示例】
输入场景：hr_zone5（心率危险区）, cadence_drop（步频骤降）
→ {"phrases": [{"key": "hr_zone5", "text": "心率太高了，走几步让心率降下来"}, {"key": "cadence_drop", "text": "步频在掉，摆臂带起来恢复节奏"}]}
        """.trimIndent()

        /**
         * 单条回填使用的系统提示词 — 为已消耗的槽位生成新的变体
         *
         * 要求与上次该场景的措辞不同，以保持播报体验新鲜。
         */
        val PHRASE_SYSTEM_PROMPT = """
【角色设定】
你是一位跑步教练，正在通过耳机向跑者发出紧急提醒。

【要求】
- 5-20 字，简洁果断
- 包含具体行动建议
- 语气温和但坚定，不要吓人
- 措辞要有变化，不要总是用同样的句式

【回复格式】
直接返回播报文本，不要 JSON，不要引号，不要解释。只返回一句话。
        """.trimIndent()
    }
}
