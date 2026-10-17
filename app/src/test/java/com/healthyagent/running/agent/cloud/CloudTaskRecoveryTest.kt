package com.healthyagent.running.agent.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal val testCloudId = "33333333-3333-4333-8333-333333333333"
internal fun response(status: String = "completed", state: String = "available", time: Long = 2000, answer: String = "慢慢来，一起跑") = CloudTaskResponse(
    testCloudId, status, when (status) { "cancelled" -> "cancelled"; "timed_out" -> "deadline"; "failed" -> "model_unavailable"; else -> null },
    1000, 31000, time, state,
    if (state == "available") buildJsonObject {
        put("status", status)
        if (status == "completed") put("answer", answer) else put("reason", if (status == "failed") "model_unavailable" else "cancelled")
        put("actions", JsonArray(emptyList()))
    } else null,
)
internal fun envelope(value: CloudTaskResponse) = buildJsonObject { put("task", CloudTaskResponse.json.encodeToJsonElement(value)) }.toString()

class CloudTaskRecoveryTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val intent = CloudTaskIntent.confirm(identity, "轻松跑", true, 1000).copy(submissionAttempted = true)

    @Test fun `同毫秒终态与确认推进不会丢掉本机结果`() {
        val running = CloudTaskReceipt.merge(intent, null, response("running", "none"))
        val completed = CloudTaskReceipt.merge(intent, running, response())
        assertTrue(completed.ackPending)
        val acknowledged = CloudTaskReceipt.merge(intent, completed, response(state = "acknowledged"))
        assertEquals(completed.resultJson, acknowledged.resultJson)
        assertFalse(acknowledged.ackPending)
        assertEquals(acknowledged, CloudTaskReceipt.merge(intent, acknowledged, response()))
        assertEquals(acknowledged, CloudTaskReceipt.merge(intent, acknowledged, response("running", "none", 1000)))
    }

    @Test fun `同毫秒过期不重新接纳迟到结果但保留此前本机正文`() {
        val completed = CloudTaskReceipt.merge(intent, null, response())
        val expired = CloudTaskReceipt.merge(intent, completed, response(state = "expired"))
        assertEquals(completed.resultJson, expired.resultJson)
        assertFalse(expired.ackPending)
        val unseen = CloudTaskReceipt.merge(intent, null, response(state = "expired"))
        assertNull(CloudTaskReceipt.merge(intent, unseen, response()).resultJson)
    }

    @Test fun `撤回优先且取消后的完成仍是完成`() {
        val cancelledIntent = intent.copy(pendingControl = "cancel")
        val completed = CloudTaskReceipt.merge(cancelledIntent, null, response())
        assertEquals("completed", completed.status)
        assertEquals("cancel", completed.confirmedControl)
        val withdrawnIntent = intent.copy(pendingControl = "withdraw")
        val late = CloudTaskReceipt.merge(withdrawnIntent, completed, response())
        assertNull(late.resultJson)
        assertFalse(late.ackPending)
        assertNotEquals("withdraw", late.confirmedControl)
        val withdrawn = CloudTaskReceipt.merge(withdrawnIntent, late, response(state = "withdrawn"))
        assertEquals("withdraw", withdrawn.confirmedControl)
        assertEquals(withdrawn, CloudTaskReceipt.merge(withdrawnIntent, withdrawn, response()))
    }

    @Test fun `冲突终态与云ID时间正文冲突必须拒绝`() {
        val completed = CloudTaskReceipt.merge(intent, null, response())
        listOf(response("failed"), response(state = "withdrawn").copy(status = "failed", reason = "model_unavailable"),
            response().copy(id = "44444444-4444-4444-8444-444444444444"),
            response().copy(acceptedAtMillis = 999, deadlineAtMillis = 30999), response(answer = "不同正文")).forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudTaskReceipt.merge(intent, completed, it) }
        }
        val cancelled = CloudTaskReceipt.merge(intent, null, response("cancelled", "none"))
        assertThrows(IllegalArgumentException::class.java) { CloudTaskReceipt.merge(intent, cancelled, response()) }
    }

    @Test fun `解析拒绝额外字段字符串时间错误状态及行动但允许空行动`() {
        assertEquals(response(), CloudTaskResponse.parse(envelope(response())))
        val valid = envelope(response())
        listOf(valid.replace("\"task\":", "\"extra\":true,\"task\":"),
            valid.replace("\"acceptedAtMillis\":1000", "\"acceptedAtMillis\":\"1000\""),
            valid.replace("\"acceptedAtMillis\":1000", "\"acceptedAtMillis\":1000.5"),
            envelope(response().copy(deadlineAtMillis = 999)), envelope(response().copy(result = null)),
            envelope(response("running", "available")), envelope(response().copy(reason = "cancelled")),
            valid.replace("\"actions\":[]", "\"actions\":[{\"kind\":\"tool_completed\",\"step\":1,\"tool\":\"runs.list\"}]"),
            valid.replace("\"actions\":[]", "\"actions\":[{\"kind\":\"model_requested\",\"step\":2}]"),
        ).forEach { assertEquals("invalid_response", assertThrows(CloudTaskFailure::class.java) { CloudTaskResponse.parse(it) }.code) }
    }
}
