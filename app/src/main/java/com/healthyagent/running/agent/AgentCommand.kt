package com.healthyagent.running.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

sealed interface AgentCommand {
    data class Submit(val clientRequestId: String, val prompt: String) : AgentCommand
    data class ListTasks(val offset: Int, val limit: Int) : AgentCommand
    data class Detail(val taskId: String) : AgentCommand
    data class Cancel(val taskId: String) : AgentCommand
}

/** 网页不能指定所有者、任务状态、云端授权或任何执行结果。 */
object AgentCommands {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun decode(name: String, body: JsonObject): AgentCommand = when (name) {
        "agent.submit" -> {
            require(body.keys == setOf("clientRequestId", "prompt"))
            val prompt = body.string("prompt")
            require(prompt.length in 1..2000 && prompt.isNotBlank())
            AgentCommand.Submit(body.id("clientRequestId"), prompt.trim())
        }
        "agent.list" -> {
            require(body.keys == setOf("offset", "limit"))
            val offset = body.integer("offset")
            val limit = body.integer("limit")
            require(offset >= 0 && limit in 1..50)
            AgentCommand.ListTasks(offset, limit)
        }
        "agent.detail", "agent.cancel" -> {
            require(body.keys == setOf("taskId"))
            val id = body.id("taskId")
            if (name == "agent.detail") AgentCommand.Detail(id) else AgentCommand.Cancel(id)
        }
        else -> throw IllegalArgumentException("未知任务命令")
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key) as? JsonPrimitive
        require(value != null && value.isString)
        return value.content
    }
    private fun JsonObject.id(key: String) = string(key).also { require(uuid.matches(it)) }
    private fun JsonObject.integer(key: String): Int {
        val value = get(key) as? JsonPrimitive
        require(value != null && !value.isString)
        return requireNotNull(value.intOrNull)
    }
}
