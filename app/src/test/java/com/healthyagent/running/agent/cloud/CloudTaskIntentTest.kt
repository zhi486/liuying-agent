package com.healthyagent.running.agent.cloud

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CloudTaskIntentTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential_generation_1")
    private val requestId = "11111111-1111-4111-8111-111111111111"
    private val taskId = "22222222-2222-4222-8222-222222222222"

    private fun intent(prompt: String = "  今晚轻松跑 🏃  ", approved: Boolean = true): CloudTaskIntent {
        val ids = listOf(requestId, taskId).iterator()
        return CloudTaskIntent.confirm(identity, prompt, approved, 1000) { ids.next() }
    }

    @Test fun `只有明确授权才生成独立请求键且正文和授权可确定性恢复`() {
        val value = intent()
        assertEquals(requestId, value.clientRequestId)
        assertEquals(taskId, value.localTaskId)
        assertFalse(value.submissionAttempted)
        assertNull(value.pendingControl)
        assertEquals("prompt_only_v1", value.consentVersion)
        assertEquals(1000L, value.consentedAtMillis)
        assertEquals(JsonObject(mapOf(
            "clientRequestId" to JsonPrimitive(requestId), "localTaskId" to JsonPrimitive(taskId),
            "prompt" to JsonPrimitive("  今晚轻松跑 🏃  "),
            "consent" to JsonObject(mapOf("cloudProcessing" to JsonPrimitive(true), "runSummaries" to JsonPrimitive(false))),
            "summaries" to JsonArray(emptyList()),
        )), value.submission())
        assertThrows(IllegalArgumentException::class.java) { intent(approved = false) }
        assertFalse(value.toString().contains("今晚"))
    }

    @Test fun `文本校验与云端UTF16纯文本边界一致且不重写原文`() {
        listOf("", " \t\n\uFEFF\u00A0", "a".repeat(2001), "\uD800", "\uDC00", "a\uD800b", "<｜image｜>", "<｜deepseek_image｜>").forEach {
            assertThrows(IllegalArgumentException::class.java) { intent(it) }
        }
        listOf("a".repeat(2000), "🏃".repeat(1000), "\u0085", "\u001c", "<image>", "\uFEFF跑步\uFEFF").forEach {
            assertEquals(it, intent(it).prompt)
        }
    }

    @Test fun `身份要求HTTPS纯origin和非秘密代次标识`() {
        listOf("http://agent.example.com", "https://a.example/", "https://u:p@a.example", "https://a.example/path", "https://a.example?q=1", "https://a.example#x", "https://a.example:0", "https://a.example:65536", "https://", "https://a.example\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { identity.copy(origin = it) }
        }
        assertEquals("https://a.example:8443", identity.copy(origin = "https://a.example:8443").origin)
        listOf("", "x".repeat(81), "Bearer secret", "身份").forEach {
            assertThrows(IllegalArgumentException::class.java) { identity.copy(ownerId = it) }
            assertThrows(IllegalArgumentException::class.java) { identity.copy(deviceId = it) }
            assertThrows(IllegalArgumentException::class.java) { identity.copy(credentialRef = it) }
        }
    }

    @Test fun `非法UUID时钟授权版本和状态不可构造`() {
        val value = intent()
        assertThrows(IllegalArgumentException::class.java) { value.copy(clientRequestId = "not-a-uuid") }
        assertThrows(IllegalArgumentException::class.java) { value.copy(localTaskId = requestId) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(consentVersion = "unknown") }
        assertThrows(IllegalArgumentException::class.java) { value.copy(consentedAtMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(updatedAtMillis = 999) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(pendingControl = "acknowledge") }
    }
}
