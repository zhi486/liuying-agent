package com.healthyagent.running.agent.cloud

import kotlinx.serialization.json.*

/** 费用仅作提示；未知用量不能显示为免费。 */
internal object CloudCostEstimate {
    fun validate(value: JsonElement): JsonObject {
        val row = value as? JsonObject ?: error("费用估算无效")
        require(row.keys == setOf("currency", "estimatedMicros", "inputTokens", "outputTokens", "usageComplete", "rateStatus"))
        fun text(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("费用估算无效")
        fun integer(key: String) {
            val number = row[key] as? JsonPrimitive ?: error("费用估算无效")
            require(!number.isString && number.longOrNull?.let { it in 0..9007199254740991L } == true)
        }
        require(text("currency") == "USD" && text("rateStatus") in setOf("current", "stale", "reference"))
        integer("inputTokens"); integer("outputTokens")
        val complete = row["usageComplete"] as? JsonPrimitive ?: error("费用估算无效")
        require(!complete.isString && complete.booleanOrNull != null)
        if (complete.boolean) integer("estimatedMicros") else require(row["estimatedMicros"] == JsonNull)
        return row
    }
}
