package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.mcp.McpPublicDns
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPublicDnsTest {
    private val worker = "liuying-agent.example.workers.dev"
    private val publicAddress = InetAddress.getByAddress(byteArrayOf(104, 16, 1, 1))

    @Test fun 云端DoH只接受固定主机并重新绑定公网地址() {
        val queried = mutableListOf<String>()
        val dns = CloudPublicDns(worker, resolve = {
            queried += it
            listOf(publicAddress, publicAddress)
        }, cache = CloudPublicDnsCache())

        val result = dns.lookup(worker)

        assertEquals(listOf(worker), queried)
        assertEquals(1, result.size)
        assertEquals(worker, result.single().hostName)
        assertTrue(McpPublicDns.isPublic(result.single()))
        assertThrows(UnknownHostException::class.java) { dns.lookup("attacker.example") }
    }

    @Test fun 云端DoH拒绝空私网超限和解析失败() {
        val privateAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val tooMany = List(33) { InetAddress.getByAddress(byteArrayOf(104, 16, 1, it.toByte())) }
        listOf<List<InetAddress>>(emptyList(), listOf(privateAddress), tooMany).forEach { answer ->
            val dns = CloudPublicDns(worker, resolve = { answer }, cache = CloudPublicDnsCache())
            assertThrows(UnknownHostException::class.java) { dns.lookup(worker) }
        }
        val failed = CloudPublicDns(worker, resolve = { throw IllegalStateException() }, cache = CloudPublicDnsCache())
        assertThrows(UnknownHostException::class.java) { failed.lookup(worker) }
    }

    @Test fun 云端DoH短时缓存避免重复解析并在到期后重新解析() {
        var now = 1_000L
        var calls = 0
        val cache = CloudPublicDnsCache()
        val resolve: (String) -> List<InetAddress> = {
            calls++
            listOf(publicAddress)
        }
        val first = CloudPublicDns(worker, resolve, { now }, cacheTtlMillis = 30_000L, cache = cache)
        val second = CloudPublicDns(worker, resolve, { now }, cacheTtlMillis = 30_000L, cache = cache)

        first.lookup(worker)
        second.lookup(worker)
        assertEquals(1, calls)

        now += 30_000L
        second.lookup(worker)
        assertEquals(2, calls)
    }

    @Test fun 云端DoH解析失败不复用已过期地址() {
        var now = 1_000L
        var shouldFail = false
        val dns = CloudPublicDns(worker, resolve = {
            if (shouldFail) throw IllegalStateException()
            listOf(publicAddress)
        }, nowMillis = { now }, cacheTtlMillis = 30_000L, cache = CloudPublicDnsCache())

        dns.lookup(worker)
        now += 30_000L
        shouldFail = true
        assertThrows(UnknownHostException::class.java) { dns.lookup(worker) }
    }
}
