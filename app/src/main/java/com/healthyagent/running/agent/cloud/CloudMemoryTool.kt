package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryToolPolicy
import kotlinx.serialization.json.*

/** 工具参数不携带用户原文或本机权限；执行前仍由原生任务绑定核验。 */
data class CloudMemoryTool(
    val requestId: String, val taskId: String, val generation: String,
    val callId: String, val name: String, val args: JsonObject,
) {
    override fun toString() = "CloudMemoryTool(内容已隐藏)"

    fun reply(observation: JsonObject) = buildJsonObject {
        put("version", "native_memory_v1"); put("generation", generation)
        put("callId", callId); put("observation", observation)
    }

    companion object {
        fun submission(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean = false): JsonObject {
            MemoryCommands.id(generation)
            require(intent.conversationId != null)
            return JsonObject(intent.submission(context) + ("memoryTools" to buildJsonObject {
                put("version", if (factsAllowed) "native_memory_v2" else "native_memory_v1"); put("generation", generation)
                if (factsAllowed) put("factsAllowed", true)
            }))
        }

        fun parse(text: String, taskId: String): CloudMemoryTool? = try {
            val envelope = CloudTaskResponse.json.parseToJsonElement(text).jsonObject
            require(envelope.keys == setOf("memory"))
            if (envelope["memory"] == JsonNull) null else {
                val row = envelope.getValue("memory").jsonObject
                require(row.keys == setOf("version", "requestId", "taskId", "generation", "call"))
                require(row.text("version") == "native_memory_v1" && row.text("taskId") == taskId)
                val call = row.getValue("call").jsonObject
                require(call.keys == setOf("id", "name", "args"))
                val callId = call.text("id")
                require(Regex("[a-zA-Z0-9_-]{1,80}").matches(callId))
                val name = call.text("name")
                val args = call.getValue("args").jsonObject
                MemoryToolPolicy.decode(name, args)
                CloudMemoryTool(MemoryCommands.id(row.text("requestId")), MemoryCommands.id(row.text("taskId")),
                    MemoryCommands.id(row.text("generation")), callId, name, args)
            }
        } catch (_: Exception) { throw CloudTaskFailure("invalid_response") }

        private fun JsonObject.text(key: String): String {
            val value = get(key) as? JsonPrimitive
            require(value != null && value.isString)
            return value.content
        }
    }
}
