package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryCommand
import com.healthyagent.running.agent.memory.MemoryRef
import kotlinx.serialization.json.*

/** Web 只能选择记录 ID，不能传摘要数值、身份、凭据、记忆正文或旧任务作为发送来源。 */
object CloudAgentCommands {
    fun validate(name: String, body: JsonObject) {
        when (name) {
            "cloud.list", "cloud.configure", "cloud.newConversation", "cloud.memoryAuthorization", "cloud.retryConversationCleanup", "cloud.stopSpeech" -> require(body.isEmpty())
            "cloud.chatConsent", "cloud.memoryConsent" -> {
                val facts = name == "cloud.memoryConsent" && body.containsKey("factsAllowed")
                require(body.keys == setOf("identityToken", "enabled") + if (facts) setOf("factsAllowed") else emptySet())
                require(text(body, "identityToken").matches(Regex("[0-9a-f]{64}")))
                require(body["enabled"] == JsonPrimitive(true) || body["enabled"] == JsonPrimitive(false))
                if (facts) {
                    require(body["factsAllowed"] == JsonPrimitive(true) || body["factsAllowed"] == JsonPrimitive(false))
                    require(body["factsAllowed"] != JsonPrimitive(true) || body["enabled"] == JsonPrimitive(true))
                }
            }
            "cloud.selectConversation", "cloud.archiveConversation", "cloud.restoreConversation", "cloud.deleteConversation" -> {
                if (name == "cloud.deleteConversation") require(body["confirmed"] == JsonPrimitive(true))
                require(body.keys == setOf("conversationId") + if (name == "cloud.deleteConversation") setOf("confirmed") else emptySet())
                if (body["conversationId"] != JsonNull) MemoryCommands.id(text(body, "conversationId"))
            }
            "cloud.renameConversation" -> {
                require(body.keys == setOf("conversationId", "title"))
                MemoryCommands.id(text(body, "conversationId"))
                require(text(body, "title").let { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) })
            }
            "cloud.prepare" -> {
                require(body.keys == setOf("prompt", "memoryRefs") +
                    (if (body.containsKey("knowledge")) setOf("knowledge") else emptySet()) +
                    (if (body.containsKey("runId")) setOf("runId") else emptySet()) +
                    (if (body.containsKey("mcpServices")) setOf("mcpServices") else emptySet()) +
                    (if (body.containsKey("conversationId")) setOf("conversationId") else emptySet()))
                if (body.containsKey("conversationId")) MemoryCommands.id(text(body, "conversationId"))
                body["knowledge"]?.let(CloudKnowledge::selection)
                if (body.containsKey("runId")) CloudRunSummary.id(text(body, "runId"))
                CloudTaskIntent.confirm(CloudTaskIdentity("https://validation.invalid", "local", "local", "local"), text(body, "prompt"), true, 0)
                refs(body)
                if (body.containsKey("mcpServices")) {
                    require(body.containsKey("conversationId"))
                    val services = body.getValue("mcpServices").jsonArray
                    require(services.size in 1..8 && services.distinct().size == services.size)
                    services.forEach { item ->
                        require(item is JsonPrimitive && item.isString)
                        MemoryCommands.id(item.content)
                    }
                }
            }
            "cloud.confirm" -> {
                require(body.keys == setOf("token", "approved") && body["approved"] == JsonPrimitive(true))
                MemoryCommands.id(text(body, "token"))
            }
            "cloud.send", "cloud.discard" -> { require(body.keys == setOf("token")); MemoryCommands.id(text(body, "token")) }
            "cloud.recover", "cloud.cancel", "cloud.withdraw", "cloud.speak" -> {
                require(body.keys == setOf("requestId")); MemoryCommands.id(text(body, "requestId"))
            }
            else -> throw IllegalArgumentException("未知在线命令")
        }
    }
    fun refs(body: JsonObject): List<MemoryRef> {
        val values = body["memoryRefs"] as? JsonArray ?: throw IllegalArgumentException("记忆引用无效")
        return if (values.isEmpty()) emptyList() else (MemoryCommands.decode("memory.preview", JsonObject(mapOf("memoryRefs" to values))) as MemoryCommand.Preview).memoryRefs
    }
    fun text(body: JsonObject, key: String): String = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("参数无效")
}
