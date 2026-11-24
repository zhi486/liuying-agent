package com.healthyagent.running.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandCodecTest {
    private val id = "11111111-1111-4111-8111-111111111111"

    @Test fun `新任务命令按严格体接受`() {
        for ((name, body) in listOf(
            "agent.submit" to """{"clientRequestId":"$id","prompt":"今晚轻松跑"}""",
            "agent.list" to """{"offset":0,"limit":20}""",
            "agent.detail" to """{"taskId":"$id"}""",
            "agent.cancel" to """{"taskId":"$id"}""",
        )) assertTrue(name, BridgeCodec.decode(command(name, body)) is BridgeDecodeResult.Accepted)
    }

    @Test fun `任务命令拒绝未知字段空文本数值字符串与越界`() {
        for ((name, body) in listOf(
            "agent.submit" to """{"clientRequestId":"$id","prompt":" "}""",
            "agent.submit" to """{"clientRequestId":"$id","prompt":"${"字".repeat(2001)}"}""",
            "agent.submit" to """{"clientRequestId":"$id","prompt":"测试","ownerId":"other"}""",
            "agent.submit" to """{"clientRequestId":"bad","prompt":"测试"}""",
            "agent.list" to """{"offset":"0","limit":20}""",
            "agent.list" to """{"offset":-1,"limit":20}""",
            "agent.list" to """{"offset":0,"limit":51}""",
            "agent.detail" to """{"taskId":"bad"}""",
            "agent.cancel" to """{"taskId":"$id","confirmed":true}""",
        )) assertTrue(name, BridgeCodec.decode(command(name, body)) is BridgeDecodeResult.Rejected)
    }

    private fun command(name: String, body: String): String {
        val original = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/v1/fixtures/valid/command.settings-query.json")).readText()).jsonObject
        val payload = original.getValue("payload").jsonObject
        return JsonObject(original + ("payload" to JsonObject(payload + mapOf(
            "name" to JsonPrimitive(name), "body" to Json.parseToJsonElement(body),
        )))).toString()
    }

    @Test fun `语音命令仅接受会话ID与单次同意不接受密钥或音频正文`() {
        assertTrue(BridgeCodec.decode(command("voice.begin", """{"sessionId":"$id","approved":true}""")) is BridgeDecodeResult.Accepted)
        for (name in listOf("voice.status", "voice.finish", "voice.cancel")) {
            assertTrue(BridgeCodec.decode(command(name, """{"sessionId":"$id"}""")) is BridgeDecodeResult.Accepted)
        }
        for (body in listOf("""{"sessionId":"$id","approved":false}""",
            """{"sessionId":"$id","approved":true,"audio":"PCM"}""",
            """{"sessionId":"$id","approved":true,"apiKey":"秘密"}""")) {
            assertTrue(BridgeCodec.decode(command("voice.begin", body)) is BridgeDecodeResult.Rejected)
        }
    }
}
