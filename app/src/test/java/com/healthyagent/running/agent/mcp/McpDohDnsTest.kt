package com.healthyagent.running.agent.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class McpDohDnsTest {
    private fun response(type: Int, data: String) = """
        {"Status":0,"TC":false,"Question":[{"name":"docs.mcp.cloudflare.com.","type":$type}],
         "Answer":[{"name":"docs.mcp.cloudflare.com.","type":$type,"TTL":60,"data":"$data"}]}
    """.trimIndent()

    @Test fun DoH使用固定云端主机和JSON查询并返回公网地址() {
        val urls = mutableListOf<String>()
        val fallback = McpDohDnsFallback { url ->
            urls += url.toString()
            response(1, "104.18.24.159")
        }
        assertEquals("104.18.24.159", fallback.lookup("docs.mcp.cloudflare.com").single().hostAddress)
        assertEquals(1, urls.size)
        assert(urls.single().startsWith("https://cloudflare-dns.com/dns-query?"))
        assert(urls.single().contains("name=docs.mcp.cloudflare.com"))
        assert(urls.single().contains("type=1"))
    }

    @Test fun IPv4无答案时才查询IPv6() {
        val types = mutableListOf<Int>()
        val fallback = McpDohDnsFallback { url ->
            val type = url.queryParameter("type")!!.toInt()
            types += type
            if (type == 1) {
                """{"Status":0,"TC":false,"Question":[{"name":"v6.example.com.","type":1}]}"""
            } else {
                response(28, "2606:4700:4700::1111")
                    .replace("docs.mcp.cloudflare.com.", "v6.example.com.")
            }
        }
        assertEquals(
            InetAddress.getByName("2606:4700:4700::1111").address.toList(),
            fallback.lookup("v6.example.com").single().address.toList(),
        )
        assertEquals(listOf(1, 28), types)
    }

    @Test fun DoH错误状态非IP私网混合和主机不匹配均拒绝() {
        val invalid = listOf(
            """{"Status":2,"TC":false,"Question":[{"name":"docs.mcp.cloudflare.com.","type":1}]}""",
            response(1, "not-an-ip"),
            response(1, "127.0.0.1"),
            """{"Status":0,"TC":false,"Question":[{"name":"other.example.com.","type":1}],
                "Answer":[{"name":"other.example.com.","type":1,"TTL":60,"data":"104.18.24.159"}]}""",
        )
        for (text in invalid) {
            try {
                McpDohDnsFallback { text }.lookup("docs.mcp.cloudflare.com")
                fail("无效DoH响应不应通过")
            } catch (_: UnknownHostException) { }
        }
    }

    @Test fun DoH文本解析拒绝CNAME冒充IPv4() {
        val text = """{"Status":0,"TC":false,"Question":[{"name":"docs.mcp.cloudflare.com.","type":1}],
            "Answer":[{"name":"docs.mcp.cloudflare.com.","type":5,"TTL":60,"data":"example.com"}]}"""
        assertEquals(emptyList<InetAddress>(), McpDohDnsFallback.parseAnswers(text, "docs.mcp.cloudflare.com", 1))
    }
}
