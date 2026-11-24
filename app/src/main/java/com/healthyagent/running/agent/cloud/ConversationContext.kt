package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryCommands
import kotlinx.serialization.json.*

object ConversationContext {
    fun validate(value: JsonElement): JsonObject {
        val row = value.jsonObject
        require(row.keys == setOf("version", "id", "approved", "messages"))
        require(row["version"] == JsonPrimitive("conversation_v1") && row["approved"] == JsonPrimitive(true))
        MemoryCommands.id(CloudAgentCommands.text(row, "id"))
        val messages = row.getValue("messages").jsonArray
        require(messages.size <= 8 && messages.size % 2 == 0)
        var chars = 0
        messages.forEachIndexed { index, value ->
            val message = value.jsonObject
            require(message.keys == setOf("role", "text"))
            require(message["role"] == JsonPrimitive(if (index % 2 == 0) "user" else "assistant"))
            val text = CloudAgentCommands.text(message, "text")
            require(CloudTaskIntent.supportedPrompt(text, if (index % 2 == 0) 2000 else 4000))
            chars += text.length
        }
        require(chars <= 12000)
        return row
    }
}
