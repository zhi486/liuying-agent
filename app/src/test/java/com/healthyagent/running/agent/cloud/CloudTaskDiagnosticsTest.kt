package com.healthyagent.running.agent.cloud

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class CloudTaskDiagnosticsTest {
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
}
