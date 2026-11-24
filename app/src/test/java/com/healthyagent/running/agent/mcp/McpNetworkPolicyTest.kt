package com.healthyagent.running.agent.mcp

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test

class McpNetworkPolicyTest {
    private fun resolver(resolve: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = resolve(hostname)
    }
    @Test fun 端点只接收无秘密的标准HTTPS域名地址() {
        assertEquals("tools.example.com", McpEndpoint.parse("https://tools.example.com/mcp").url.host)
        listOf("http://tools.example.com/mcp", "https://127.0.0.1/mcp", "https://[::1]/mcp",
            "https://2130706433/mcp", "https://0x7f000001/mcp", "https://user:secret@tools.example.com/mcp",
            "https://tools.example.com:8443/mcp", "https://tools.example.com/mcp?key=secret",
            "https://tools.example.com/mcp#secret", "https://host.local/mcp", "https://host.internal/mcp",
            "https://localhost/mcp", "https://tools.example.com/mcp ", "https://tools.example.com\\mcp").forEach { raw ->
            try { McpEndpoint.parse(raw); fail("应拒绝：$raw") } catch (_: McpTransportFailure) { }
        }
    }

    @Test fun IPv4私网保留共享广播多播与云元数据地址全部拒绝() {
        listOf("0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.254", "127.0.0.1",
            "169.254.169.254", "172.16.0.1", "172.31.255.254", "192.0.0.9", "192.0.2.1",
            "192.88.99.2", "192.168.1.1", "198.18.0.1", "198.19.255.254", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255").forEach {
            assertFalse("应拒绝：$it", McpPublicDns.isPublic(InetAddress.getByName(it)))
        }
        listOf("1.1.1.1", "8.8.8.8", "93.184.215.14", "100.63.255.254", "100.128.0.1", "172.15.0.1", "172.32.0.1").forEach {
            assertTrue("应允许：$it", McpPublicDns.isPublic(InetAddress.getByName(it)))
        }
    }

    @Test fun IPv6只接受普通全球单播且拒绝本地转换隧道文档及带scope的地址() {
        listOf("::", "::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::7f00:1", "64:ff9b:1::1",
            "100::1", "2001::1", "2001:2::1", "2001:10::1", "2001:db8::1", "2002:7f00:1::1",
            "3fff::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1").forEach {
            assertFalse("应拒绝：$it", McpPublicDns.isPublic(InetAddress.getByName(it)))
        }
        val publicAddress = InetAddress.getByName("2606:4700:4700::1111")
        assertTrue(McpPublicDns.isPublic(publicAddress))
        assertFalse(McpPublicDns.isPublic(Inet6Address.getByAddress(null, publicAddress.address, 2)))
    }

    @Test fun 混合DNS答案不筛掉坏地址继续执行而是整批拒绝() {
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        val dns = McpPublicDns(endpoint, resolver { listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1")) })
        try { dns.lookup(endpoint.url.host); fail("混合答案不应通过") } catch (_: UnknownHostException) { }
    }

    @Test fun 系统DNS全为fakeIP时才回退DoH且回退结果重新逐项校验() {
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        var fallbackLookups = 0
        val dns = McpPublicDns(endpoint, resolver { listOf(InetAddress.getByName("198.18.0.54")) }, McpDnsFallback {
            fallbackLookups++
            assertEquals("tools.example.com", it)
            listOf(InetAddress.getByName("93.184.215.14"))
        })
        assertEquals("93.184.215.14", dns.lookup(endpoint.url.host).single().hostAddress)
        assertEquals(1, fallbackLookups)
    }

    @Test fun 混合系统DNS不触发DoH且DoH私网混合空答案全部拒绝() {
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        var fallbackLookups = 0
        val mixed = McpPublicDns(endpoint,
            resolver { listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("198.18.0.54")) },
            McpDnsFallback { fallbackLookups++; listOf(InetAddress.getByName("93.184.215.14")) })
        try { mixed.lookup(endpoint.url.host); fail("混合系统答案不应通过") } catch (_: UnknownHostException) { }
        assertEquals(0, fallbackLookups)
        for (answers in listOf(
            emptyList<InetAddress>(),
            listOf(InetAddress.getByName("10.0.0.1")),
            listOf(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("127.0.0.1")),
        )) {
            val dns = McpPublicDns(endpoint, resolver { listOf(InetAddress.getByName("198.18.0.54")) },
                McpDnsFallback { answers })
            try { dns.lookup(endpoint.url.host); fail("无效DoH答案不应通过") } catch (_: UnknownHostException) { }
        }
    }

    @Test fun 解析只允许原主机且结果逐次检查不复用旧的允许结论() {
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        var lookups = 0
        val dns = McpPublicDns(endpoint, resolver { lookups++; listOf(InetAddress.getByName(if (lookups == 1) "1.1.1.1" else "10.0.0.1")) })
        assertEquals("1.1.1.1", dns.lookup(endpoint.url.host).single().hostAddress)
        try { dns.lookup("other.example.com"); fail("不应查询另一个主机") } catch (_: UnknownHostException) { }
        assertEquals(1, lookups)
        try { dns.lookup(endpoint.url.host); fail("重绑定不应通过") } catch (_: UnknownHostException) { }
        assertEquals(2, lookups)
    }

    @Test fun 空DNS和过多地址均关闭而不是回退到系统解析() {
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        for (answers in listOf(emptyList(), List(33) { InetAddress.getByName("1.1.1.1") })) {
            try { McpPublicDns(endpoint, resolver { answers }).lookup(endpoint.url.host); fail("应拒绝无效答案") }
            catch (_: UnknownHostException) { }
        }
    }
}
