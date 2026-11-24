package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryContextState
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationSummaryContractTest {
    @Test fun 共享服务端回执样本保留状态结束证据和用量() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/agent/summary-receipts-v1.json")).use {
            Json.parseToJsonElement(it.bufferedReader(Charsets.UTF_8).readText()).jsonObject
        }
        val id = fixture.getValue("requestId").jsonPrimitive.content
        val hash = fixture.getValue("fingerprint").jsonPrimitive.content
        val candidate = ConversationSummaryPolicy.Candidate(hash, listOf(ConversationSummaryPolicy.Turn(
            UUID.randomUUID().toString(), "合成问题", "合成回答", MemoryContextState())), emptySet())
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(5, cases.size)
        for (value in cases) {
            val sample = value.jsonObject
            val wire = JsonObject(sample + mapOf("version" to JsonPrimitive("summary_v1"),
                "purpose" to JsonPrimitive("conversation_summary"), "requestId" to JsonPrimitive(id),
                "fingerprint" to JsonPrimitive(hash)))
            val receipt = ConversationSummaryHttpClient.parse(wire.toString(), id, hash)
            assertEquals(sample.getValue("state").jsonPrimitive.content, receipt.state)
            assertEquals(sample.getValue("remoteFinished").jsonPrimitive.boolean, receipt.remoteFinished)
            assertEquals(sample["usage"] != JsonNull, receipt.usage != null)
            assertEquals(sample["summary"].takeUnless { it == JsonNull }?.jsonPrimitive?.content, receipt.acceptedSummary(candidate))
            assertNull(receipt.acceptedSummary(null))
            if (receipt.state == "withdrawn") {
                val result = receipt.result(id, hash, candidate) as ConversationSummaryProtocol.Result.Withdrawn
                assertEquals(receipt.remoteFinished, result.remoteFinished)
            }
        }
    }
}
