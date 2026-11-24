package com.healthyagent.running.agent.mcp

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 与云端登记使用相同的首版范围；地址格式检查不能替代实际连接检查。 */
class McpEndpoint private constructor(internal val url: HttpUrl) {
    override fun toString() = url.toString()
    companion object {
        fun parse(raw: String): McpEndpoint {
            try {
                val url = raw.toHttpUrl()
                if (raw.length > 2048 || raw.any { it.isWhitespace() } || raw.any { it in "?#\\" } ||
                    !url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                    url.toString() != raw || !Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}").matches(url.host) ||
                    Regex("\\.(localhost|local|internal|lan|home|onion)$").containsMatchIn(url.host)) throw McpTransportFailure("invalid_endpoint")
                return McpEndpoint(url)
            } catch (_: Exception) { throw McpTransportFailure("invalid_endpoint") }
        }
    }
}

/** 此对象就是 OkHttp 建立连接时使用的 DNS；检查后的地址直接交给 Socket，不再次按主机解析。 */
internal class McpPublicDns(
    private val endpoint: McpEndpoint,
    private val upstream: Dns,
    private val fallback: McpDnsFallback? = null,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        try {
            if (hostname != endpoint.url.host) throw UnknownHostException()
            val system = upstream.lookup(hostname).toList()
            if (system.isEmpty() || system.size > 32) throw UnknownHostException()
            val addresses = if (system.all { !isPublic(it) }) fallback?.lookup(hostname) ?: throw UnknownHostException() else system
            if (addresses.isEmpty() || addresses.size > 32 || addresses.any { !isPublic(it) }) throw UnknownHostException()
            return Collections.unmodifiableList(addresses.map { InetAddress.getByAddress(hostname, it.address) })
        } catch (_: Exception) { throw UnknownHostException("插件地址不可连接") }
    }

    companion object {
        // 依据 IANA 特殊用途地址表，保守拒绝整段及转换／隧道地址，不依赖 isSiteLocalAddress 的有限范围。
        fun isPublic(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) {
                val (a, c, d) = b
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && c in 64..127) || (a == 169 && c == 254) || (a == 172 && c in 16..31) ||
                    (a == 192 && ((c == 0 && d in listOf(0, 2)) || (c == 88 && d == 99) || c == 168)) ||
                    (a == 198 && (c in 18..19 || (c == 51 && d == 100))) || (a == 203 && c == 0 && d == 113))
            }
            if (b.size != 16 || (address is Inet6Address && address.scopeId != 0)) return false
            return b[0] in 0x20..0x3f &&
                !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) &&
                !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0)
        }
    }
}
