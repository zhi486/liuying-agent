package com.healthyagent.running.agent.cloud

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationContextV2Test {
    @Test fun 来源数近期字符轮数及权限字段严格限制() {
        val ids = (1..9).map { JsonPrimitive("33333333-3333-4333-8333-" + it.toString().padStart(12, '0')) }
        val messages = JsonArray((0..7).map { buildJsonObject {
            put("role", if (it % 2 == 0) "user" else "assistant"); put("text", "字".repeat(1500))
        } })
        val summary = buildJsonObject {
            put("requestId", "22222222-2222-4222-8222-222222222222"); put("fingerprint", "a".repeat(64))
            put("sourceIds", JsonArray(ids.take(8))); put("text", "字".repeat(2000))
        }
        val valid = buildJsonObject {
            put("version", "conversation_v2"); put("id", "11111111-1111-4111-8111-111111111111")
            put("approved", true); put("messages", messages); put("summary", summary)
        }
        ConversationContextV2.validate(valid)
        val tooLong = JsonArray(messages.mapIndexed { i, m -> if (i == 0)
            JsonObject(m.jsonObject + ("text" to JsonPrimitive("字".repeat(1501)))) else m })
        val tooMany = JsonArray((0..9).map { buildJsonObject {
            put("role", if (it % 2 == 0) "user" else "assistant"); put("text", "字")
        } })
        for (invalid in listOf(
            JsonObject(valid + ("summary" to JsonObject(summary + ("sourceIds" to JsonArray(ids))))),
            JsonObject(valid + ("messages" to tooLong)), JsonObject(valid + ("messages" to tooMany)),
            JsonObject(valid + ("tools" to JsonArray(emptyList()))),
            JsonObject(valid + ("factsAllowed" to JsonPrimitive(true))),
            JsonObject(valid + ("summary" to JsonObject(summary + ("approved" to JsonPrimitive(true))))),
        )) assertTrue(runCatching { ConversationContextV2.validate(invalid) }.isFailure)
    }

    @Test fun 共享样本编码一致且旧版拒绝新版() {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "contracts/agent/conversation-v2.json").isFile }
        val sample = Json.parseToJsonElement(File(root, "contracts/agent/conversation-v2.json").readText()).jsonObject
        assertEquals(sample, Json.parseToJsonElement(ConversationContextV2.encode(sample)))
        assertTrue(runCatching { ConversationContext.validate(sample) }.isFailure)
        val summary = sample.getValue("summary").jsonObject
        for (invalid in listOf(
            JsonObject(summary + ("extra" to JsonPrimitive(true))),
            JsonObject(summary + ("text" to JsonPrimitive("x".repeat(2001)))),
            JsonObject(summary + ("text" to JsonPrimitive(" "))),
            JsonObject(summary + ("text" to JsonPrimitive(1.toChar().toString()))),
            JsonObject(summary + ("sourceIds" to JsonArray(emptyList()))),
            JsonObject(summary + ("sourceIds" to JsonArray(List(2) { summary.getValue("sourceIds").jsonArray.single() }))),
            JsonObject(summary + ("requestId" to JsonPrimitive(123))),
        )) assertTrue(runCatching { ConversationContextV2.validate(JsonObject(sample + ("summary" to invalid))) }.isFailure)
    }
}
