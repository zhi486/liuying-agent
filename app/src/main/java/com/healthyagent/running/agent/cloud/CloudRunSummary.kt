package com.healthyagent.running.agent.cloud

import com.healthyagent.running.data.local.RunSessionEntity
import kotlinx.serialization.json.*

/** 单条授权快照；只读概要表，不查询轨迹、分段或连续心率。 */
object CloudRunSummary {
    fun id(value: String) = value.also { require(Regex("[a-zA-Z0-9_-]{1,80}").matches(it)) }

    fun from(row: RunSessionEntity): JsonObject {
        require(row.state == "FINISHED" && row.endTimeMillis != null && row.endTimeMillis >= row.startTimeMillis)
        return validate(buildJsonObject {
            put("runId", row.id)
            put("distanceMeters", row.totalDistanceMeters)
            put("durationSeconds", row.totalDurationSec)
            put("averagePaceSecPerKm", row.avgPaceSecPerKm?.let(::JsonPrimitive) ?: JsonNull)
            put("averageHeartRate", row.avgHeartRate?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    fun validate(value: JsonElement): JsonObject {
        val row = value as? JsonObject ?: throw IllegalArgumentException("跑步摘要无效")
        require(row.keys == setOf("runId", "distanceMeters", "durationSeconds", "averagePaceSecPerKm", "averageHeartRate"))
        id(CloudAgentCommands.text(row, "runId"))
        fun metric(key: String, max: Double, nullable: Boolean = false, positive: Boolean = false) {
            val field = row.getValue(key)
            if (nullable && field == JsonNull) return
            val number = (field as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            require(number != null && number.isFinite() && number >= 0 && number <= max && (!positive || number > 0))
        }
        metric("distanceMeters", 1_000_000.0)
        metric("durationSeconds", 604_800.0, positive = true)
        metric("averagePaceSecPerKm", 86_400.0, nullable = true, positive = true)
        metric("averageHeartRate", 400.0, nullable = true, positive = true)
        return row
    }
}

class CloudRunConflict : IllegalStateException("跑步记录已变化，请重新核对")
