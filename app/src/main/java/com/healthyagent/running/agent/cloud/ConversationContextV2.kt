package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryCommands
import kotlinx.serialization.json.*

/** 未挂载；来源与授权真实性由未来可信宿主负责，不接受Web正文。 */
object ConversationContextV2 {
    fun validate(value: JsonElement): JsonObject {
        val row = value.jsonObject
        require(row.keys == setOf("version", "id", "approved", "messages", "summary"))
        require(row["version"] == JsonPrimitive("conversation_v2"))
        ConversationContext.validate(JsonObject(row - "summary" + ("version" to JsonPrimitive("conversation_v1"))))
        val summary = row.getValue("summary").jsonObject
        require(summary.keys == setOf("requestId", "fingerprint", "sourceIds", "text"))
        MemoryCommands.id(CloudAgentCommands.text(summary, "requestId"))
        require(CloudAgentCommands.text(summary, "fingerprint").matches(Regex("[0-9a-f]{64}")))
        val ids = summary.getValue("sourceIds").jsonArray.map {
            val id = it.jsonPrimitive
            require(id.isString)
            MemoryCommands.id(id.content)
        }
        require(ids.size in 1..8 && ids.toSet().size == ids.size)
        val text = CloudAgentCommands.text(summary, "text")
        require(CloudTaskIntent.supportedPrompt(text, 2000))
        require(text.none { it.isISOControl() && it !in "\n\r\t" })
        return row
    }

    fun encode(value: JsonElement): String = validate(value).toString()
}
