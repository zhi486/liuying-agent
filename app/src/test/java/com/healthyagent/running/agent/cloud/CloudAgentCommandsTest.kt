package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryRef
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudAgentCommandsTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private fun body(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun 持续授权命令不接受隐式布尔值或附带数据直发() {
        val context = CloudChatConsent.context(identity)
        CloudAgentCommands.validate("cloud.chatConsent", body("""{"identityToken":"$context","enabled":true}"""))
        CloudAgentCommands.validate("cloud.chatConsent", body("""{"identityToken":"$context","enabled":false}"""))
        CloudAgentCommands.validate("cloud.send", body("""{"token":"$id"}"""))
        for ((name, input) in listOf(
            "cloud.chatConsent" to """{"identityToken":"$context","enabled":"true"}""",
            "cloud.chatConsent" to """{"identityToken":"old","enabled":true}""",
            "cloud.send" to """{"token":"$id","approved":true}""",
            "cloud.send" to """{"token":"$id","memoryRefs":[]}""",
        )) assertTrue(runCatching { CloudAgentCommands.validate(name, body(input)) }.isFailure)
    }
    @Test fun 只接受受限引用与真正布尔授权() {
        CloudAgentCommands.validate("cloud.prepare", body("""{"prompt":"今晚怎样安排","memoryRefs":[]}"""))
        CloudAgentCommands.validate("cloud.confirm", body("""{"token":"$id","approved":true}"""))
        for (invalid in listOf("""{"token":"$id","approved":false}""", """{"token":"$id","approved":"true"}""", """{"token":"$id","approved":true,"prompt":"替换"}""")) {
            assertTrue(runCatching { CloudAgentCommands.validate("cloud.confirm", body(invalid)) }.isFailure)
        }
        for (invalid in listOf("""{"prompt":"问题","memoryRefs":[],"bearer":"秘密"}""", """{"prompt":"问题","memoryRefs":[{"id":"$id","version":1,"content":"偷渡"}]}""", """{"prompt":"问题","memoryRefs":[{"id":"$id","version":"1"}]}""")) {
            assertTrue(runCatching { CloudAgentCommands.validate("cloud.prepare", body(invalid)) }.isFailure)
        }
    }
    @Test fun 旧授权不扩义且新授权只存引用() {
        val old = CloudTaskIntent.confirm(identity, "问题", true, 1000)
        assertFalse(old.submission().containsKey("memoryContext"))
        val selected = CloudTaskIntent.confirmSelected(identity, "问题", listOf(MemoryRef(id, 2)), 1000)
        assertEquals("selected_memories_v1", selected.consentVersion)
        val context = body("""{"version":"selected_memories_v1","approved":true,"items":[{"id":"$id","version":2,"content":"喜欢晚跑"}]}""")
        assertTrue(runCatching { selected.submission() }.isFailure)
        assertTrue(runCatching { old.submission(context) }.isFailure)
        assertEquals(context, selected.submission(context)["memoryContext"])
        assertFalse(selected.memoryRefsJson!!.contains("喜欢晚跑"))
        assertTrue(runCatching { selected.submission(body(context.toString().replace("\"version\":2", "\"version\":1"))) }.isFailure)
        assertTrue(runCatching { selected.submission(body(context.toString().replace("喜欢晚跑", "<｜image｜>"))) }.isFailure)
    }
}
