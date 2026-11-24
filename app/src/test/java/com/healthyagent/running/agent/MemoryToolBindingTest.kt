package com.healthyagent.running.agent

import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.memory.*
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemoryToolBindingTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val id = UUID.randomUUID().toString()
    private val intent = CloudTaskIntent.confirm(identity, "我喜欢夜跑", true, 1000).copy(
        conversationId = id,
        conversationJson = """{"version":"conversation_v1","id":"$id","approved":true,"messages":[]}""",
    )
    private val grant = MemoryToolGrant(CloudChatConsent.context(identity), UUID.randomUUID().toString())

    @Test fun 事实范围冻结在本机来源哈希且不能篡改旧任务扩权() {
        val previous = MemoryToolBinding.capture(intent, grant)
        val facts = MemoryToolBinding.capture(intent, grant.copy(factsAllowed = true))
        assertFalse(previous.factsAllowed)
        assertTrue(facts.factsAllowed)
        assertTrue(facts.matches(intent))
        assertNotEquals(previous.sourceHash, facts.sourceHash)
        assertFalse(previous.copy(factsAllowed = true).matches(intent))
        assertFalse(facts.copy(factsAllowed = false).matches(intent))
        val command = buildJsonObject { put("identityToken", "a".repeat(64)); put("enabled", true); put("factsAllowed", true) }
        CloudAgentCommands.validate("cloud.memoryConsent", command)
        for (invalid in listOf(JsonObject(command + ("enabled" to JsonPrimitive(false))),
            JsonObject(command + ("factsAllowed" to JsonPrimitive("true"))))) {
            assertThrows(IllegalArgumentException::class.java) { CloudAgentCommands.validate("cloud.memoryConsent", invalid) }
        }
    }

    @Test fun 来源绑定所有不可变字段但不受提交与控制状态影响() {
        val binding = MemoryToolBinding.capture(intent, grant)
        assertTrue(binding.matches(intent))
        assertTrue(binding.matches(intent.copy(submissionAttempted = true, pendingControl = "cancel", updatedAtMillis = 2000)))
        for (changed in listOf(intent.copy(prompt = "我喜欢晨跑"),
            intent.copy(clientRequestId = UUID.randomUUID().toString()), intent.copy(localTaskId = UUID.randomUUID().toString()),
            intent.copy(consentedAtMillis = 999), intent.copy(identity = identity.copy(credentialRef = "changed")),
            intent.copy(conversationId = null, conversationJson = null),
            intent.copy(consentVersion = "selected_knowledge_v1", knowledgeJson = """{"version":"curated_knowledge_v1","library":"firefly","allowSpoilers":false}"""))) {
            assertFalse(binding.matches(changed))
        }
        assertFalse(binding.copy(sourceHash = "0".repeat(64)).matches(intent))
        assertThrows(IllegalArgumentException::class.java) { MemoryToolBinding.capture(intent, grant.copy(scope = "0".repeat(64))) }
    }

    @Test fun 旧协议不增加授权字段且无会话任务不能绑定() {
        val binding = MemoryToolBinding.capture(intent, grant)
        assertEquals(setOf("clientRequestId", "localTaskId", "prompt", "consent", "summaries", "conversation"), intent.submission().keys)
        assertFalse(binding.toString().contains(intent.prompt))
        assertThrows(IllegalArgumentException::class.java) {
            MemoryToolBinding.capture(intent.copy(conversationId = null, conversationJson = null), grant)
        }
    }

    @Test fun 授权命令只接受严格布尔与当前状态指纹() {
        CloudAgentCommands.validate("cloud.memoryAuthorization", buildJsonObject {})
        val valid = buildJsonObject { put("identityToken", "a".repeat(64)); put("enabled", true) }
        CloudAgentCommands.validate("cloud.memoryConsent", valid)
        for (invalid in listOf(JsonObject(valid + ("enabled" to JsonPrimitive("true"))),
            JsonObject(valid + ("ownerId" to JsonPrimitive("other"))),
            JsonObject(valid - "identityToken"))) {
            assertThrows(IllegalArgumentException::class.java) { CloudAgentCommands.validate("cloud.memoryConsent", invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { CloudAgentCommands.validate("cloud.memoryAuthorization", valid) }
    }
}
