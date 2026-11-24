package com.healthyagent.running.agent.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudCostEstimateTest {
    private val valid = """{"currency":"USD","estimatedMicros":78,"inputTokens":100,"outputTokens":40,"usageComplete":true,"rateStatus":"stale"}"""
    private fun value(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun response(cost: String? = valid) = CloudTaskResponse("33333333-3333-4333-8333-333333333333", "completed", null,
        1000, 31000, 2000, "available", buildJsonObject {
            put("status", "completed"); put("answer", "回答"); put("actions", JsonArray(emptyList()))
            if (cost != null) put("cost", value(cost))
        })

    @Test fun 旧回执兼容且费用随回答持久化快照合并() {
        response(null).validate(); response().validate()
        val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
        val intent = CloudTaskIntent.confirm(identity, "问题", true, 1000)
        val receipt = CloudTaskReceipt.merge(intent, null, response())
        assertEquals(value(valid), value(receipt.resultJson!!)["cost"])
        val acknowledged = CloudTaskReceipt.merge(intent, receipt, response().copy(resultState = "acknowledged", result = null))
        assertEquals(receipt.resultJson, acknowledged.resultJson)
    }

    @Test fun 未知费用明确为null且拒绝非法数字币种和额外字段() {
        response(valid.replace("78", "null").replace("true", "false")).validate()
        for (bad in listOf(valid.replace("78", "-1"), valid.replace("USD", "CNY"),
            valid.replace("true", "false"), valid.replace("78", "null"),
            valid.replace("100", "9007199254740992"), valid.replace("stale", "unknown"),
            valid.dropLast(1) + ",\"extra\":true}")) {
            assertTrue(runCatching { response(bad).validate() }.isFailure)
        }
    }
}
