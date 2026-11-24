package com.healthyagent.running.agent.memory

import kotlinx.serialization.json.*

/** 仅传本次工具需要的正文、标识和版本，不上传本机来源、时间或授权资料。 */
object MemoryToolWire {
    fun encode(result: MemoryToolResult): JsonObject = try {
        when (result) {
            is MemoryToolResult.Found -> {
                require(result.items.size <= 5 && result.items.map { it.id }.distinct().size == result.items.size)
                buildJsonObject { put("status", "found"); put("items", JsonArray(result.items.map(::item))) }
            }
            is MemoryToolResult.Saved -> buildJsonObject {
                put("status", "saved"); put("memory", item(result.memory)); put("replayed", result.replayed)
            }
            is MemoryToolResult.Rejected -> when (result.reason) {
                // 存储异常可能发生在提交后，不能向模型声称明确未保存。
                "storage_unavailable" -> unconfirmed("unavailable")
                "sensitive_or_instruction" -> rejected("sensitive_content")
                "consent_required", "scope_denied", "task_inactive", "invalid_tool", "evidence_required", "conflict" -> rejected(result.reason)
                else -> unconfirmed("invalid_response")
            }
        }
    } catch (_: IllegalArgumentException) {
        unconfirmed("invalid_response")
    }

    private fun item(record: MemoryRecord) = buildJsonObject {
        MemoryCommands.id(record.id)
        require(record.version > 0 && MemoryCommands.content(record.content) == record.content)
        require(!record.content.contains("<｜image｜>") && !record.content.contains("<｜deepseek_image｜>"))
        put("id", record.id); put("version", record.version); put("content", record.content)
    }

    private fun rejected(reason: String) = buildJsonObject { put("status", "rejected"); put("reason", reason) }
    private fun unconfirmed(reason: String) = buildJsonObject { put("status", "unconfirmed"); put("reason", reason) }
}
