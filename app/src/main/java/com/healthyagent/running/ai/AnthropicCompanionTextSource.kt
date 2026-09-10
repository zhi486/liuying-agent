package com.healthyagent.running.ai

import com.healthyagent.running.speech.CompanionTextSource
import com.healthyagent.running.speech.BundledVoiceCatalog
import com.healthyagent.running.speech.SpeechRequest
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class AnthropicCompanionTextSource(
    private val callFactory: Call.Factory = OkHttpClient(),
) : CompanionTextSource {
    override suspend fun create(request: SpeechRequest): String? {
        val apiKey = LlmConfig.apiKey
        if (apiKey.isBlank()) return null
        val core = BundledVoiceCatalog.find(request.trigger.ruleId)?.text ?: request.trigger.speakText
        val prompt = buildJsonObject {
            put("固定核心", core)
            if (request.trigger.ruleId == "km_split") request.analysis?.let { analysis ->
                put("split_data", Json.encodeToJsonElement(analysis.split_data))
                put("session_summary", Json.encodeToJsonElement(analysis.session_summary))
                analysis.run_config?.let { put("run_config", Json.encodeToJsonElement(it)) }
            }
        }
        val body = buildJsonObject {
            put("model", LlmConfig.anthropicModel)
            put("max_tokens", 100)
            put("system", SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", prompt.toString()) })
            }
        }
        val call = callFactory.newCall(Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.success(null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive || !it.isSuccessful) null else {
                                val text = it.body?.string()?.let { content ->
                                    Json.parseToJsonElement(content).jsonObject["content"]?.jsonArray
                                        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim()
                                }
                                text?.takeIf { phrase -> phrase.isNotBlank() && phrase.length <= 50 &&
                                    '\n' !in phrase && !phrase.contains(core) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        continuation.resumeWith(Result.failure(cancelled))
                        return
                    } catch (_: Exception) {
                        null
                    }
                    continuation.resumeWith(Result.success(result))
                }
            })
        }
    }

    private companion object {
        val SYSTEM_PROMPT = """
            你是流萤，正陪开拓者跑步。只输出一条简短、自然、温柔的中文陪伴句，最多五十字。
            固定核心是已经确定的播报前句，只供理解场景。禁止重复、改写核心或其中的真实数字。
            禁止新增健康判断、运动动作或行动建议，不要求加速、减速、休息、喝水或调整呼吸。
            只表达陪伴与感受，不复述数据，不输出数字、JSON、括号动作、颜文字或多条句子。
        """.trimIndent()
    }
}
