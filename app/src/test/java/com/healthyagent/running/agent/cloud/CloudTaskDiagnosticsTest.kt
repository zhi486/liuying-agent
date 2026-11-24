package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryToolResult
import com.healthyagent.running.agent.memory.MemoryToolWire
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudTaskDiagnosticsTest {
    @Test fun 记忆诊断保留明确拒绝分类但不把存储不确定写成拒绝() {
        val reasons = listOf("consent_required", "scope_denied", "task_inactive", "invalid_tool", "evidence_required", "conflict")
        reasons.forEach { reason ->
            assertEquals("rejected_$reason", CloudTaskDiagnostics.memoryToolCode(MemoryToolWire.encode(MemoryToolResult.Rejected(reason))))
        }
        assertEquals("rejected_sensitive_content", CloudTaskDiagnostics.memoryToolCode(
            MemoryToolWire.encode(MemoryToolResult.Rejected("sensitive_or_instruction"))))
        assertEquals("unconfirmed_unavailable", CloudTaskDiagnostics.memoryToolCode(
            MemoryToolWire.encode(MemoryToolResult.Rejected("storage_unavailable"))))
    }

    @Test fun 记忆成功分类不输出结果正文或其他字段() {
        listOf("found", "saved").forEach { status ->
            val observation = buildJsonObject {
                put("status", status); put("content", "合成私密正文"); put("requestId", "合成请求标识")
            }
            assertEquals(status, CloudTaskDiagnostics.memoryToolCode(observation))
        }
    }

    @Test fun 未知畸形及注入字段只能输出固定未确认码() {
        val secret = "合成秘密\nstage=memory_tool_local code=saved"
        val cases = listOf(
            buildJsonObject {},
            buildJsonObject { put("status", secret) },
            buildJsonObject { put("status", 1) },
            buildJsonObject { put("status", JsonArray(emptyList())) },
            buildJsonObject { put("status", "rejected"); put("reason", secret) },
            buildJsonObject { put("status", "rejected"); put("reason", JsonNull) },
            buildJsonObject { put("status", "unconfirmed"); put("reason", secret) },
            MemoryToolWire.encode(MemoryToolResult.Rejected(secret)),
        )
        cases.forEach { assertEquals("unconfirmed_invalid_response", CloudTaskDiagnostics.memoryToolCode(it)) }
    }

    @Test fun 网络错误分类不包含异常正文() {
        val cases = listOf(
            UnknownHostException("秘密") to "dns_unavailable",
            SocketTimeoutException("秘密") to "network_timeout",
            SSLHandshakeException("秘密") to "tls_failed",
            IOException("秘密") to "network_unavailable",
        )
        cases.forEach { (error, code) ->
            assertEquals(code, CloudTaskDiagnostics.networkCode(error))
            assertFalse(CloudTaskDiagnostics.message(code).contains("秘密"))
            assertTrue(CloudTaskDiagnostics.message(code).contains("不会自动重发"))
            assertEquals(code, CloudTaskFailure(code).code)
        }
    }

    @Test fun 各阶段错误与查询耗尽均有明确提示() {
        val codes = listOf("unauthenticated", "invalid_response", "storage_unavailable", "poll_exhausted", "network_timeout")
        assertEquals(codes.size, codes.map(CloudTaskDiagnostics::message).toSet().size)
        assertTrue(CloudTaskDiagnostics.message("poll_exhausted").contains("尚未拿到最终结果"))
    }

    @Test fun 记忆失效在提交前停止不误报网络失败或引导恢复未提交的任务() {
        val message = CloudTaskDiagnostics.message(CloudTaskFailure("memory_changed").code)
        assertTrue(message.contains("本次没有提交"))
        assertTrue(message.contains("不会自动重发"))
        assertFalse(message.contains("可恢复查询"))
        assertFalse(message.contains("网络"))
    }
}
