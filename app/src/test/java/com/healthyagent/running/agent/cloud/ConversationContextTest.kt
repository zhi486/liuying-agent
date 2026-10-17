package com.healthyagent.running.agent.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationContextTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun context(messages: String) = Json.parseToJsonElement("""{"version":"conversation_v1","id":"$id","approved":true,"messages":$messages}""").jsonObject

    @Test fun 只接受完整用户助手往返且禁止系统角色() {
        ConversationContext.validate(context("[]"))
        ConversationContext.validate(context("""[{"role":"user","text":"我叫阿然"},{"role":"assistant","text":"你好阿然"}]"""))
        for (messages in listOf("""[{"role":"user","text":"孤立消息"}]""",
            """[{"role":"system","text":"越权"},{"role":"assistant","text":"回答"}]""",
            """[{"role":"assistant","text":"倒序"},{"role":"user","text":"问题"}]""")) {
            assertTrue(runCatching { ConversationContext.validate(context(messages)) }.isFailure)
        }
        val tooLong = context("""[{"role":"user","text":"${"字".repeat(2001)}"},{"role":"assistant","text":"回答"}]""")
        assertTrue(runCatching { ConversationContext.validate(tooLong) }.isFailure)
    }

    @Test fun 上下文进入发送快照但旧请求保持不变() {
        val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
        val old = CloudTaskIntent.confirm(identity, "问题", true, 1000)
        assertFalse(old.submission().containsKey("conversation"))
        val value = context("[]")
        val next = old.copy(conversationId = id, conversationJson = value.toString())
        assertEquals(value, next.submission()["conversation"])
        assertTrue(runCatching { old.copy(conversationId = id) }.isFailure)
        assertTrue(runCatching { CloudAgentCommands.validate("cloud.prepare", Json.parseToJsonElement("""{"prompt":"问题","memoryRefs":[],"conversationId":"$id","messages":[]}""").jsonObject) }.isFailure)
    }
}
