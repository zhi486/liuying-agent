package com.healthyagent.running.agent.mcp

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun interface McpDnsFallback {
    fun lookup(hostname: String): List<InetAddress>
}

/**
 * 仅当系统 DNS 全部返回特殊用途地址时使用。
 * DoH 主机通过固定的 Cloudflare 公网地址引导，不回退到系统解析，也不允许重定向或隐式重试。
 */
internal class McpDohDnsFallback internal constructor(
    private val query: (HttpUrl) -> String = ::queryDoh,
) : McpDnsFallback {
    override fun lookup(hostname: String): List<InetAddress> = try {
        val ipv4 = queryType(hostname, IPV4)
        val addresses = ipv4.ifEmpty { queryType(hostname, IPV6) }
        if (addresses.isEmpty() || addresses.size > 32 || addresses.any { !McpPublicDns.isPublic(it) })
            throw UnknownHostException("DoH 地址不可连接")
        addresses
    } catch (error: UnknownHostException) {
        throw error
    } catch (_: Exception) {
        throw UnknownHostException("DoH 解析失败")
    }

    private fun queryType(hostname: String, type: Int): List<InetAddress> {
        val url = DOH_BASE.newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", type.toString())
            .build()
        return parseAnswers(query(url), hostname, type)
    }

    companion object {
        private const val DOH_HOST = "cloudflare-dns.com"
        private const val MAX_RESPONSE_BYTES = 32768
        private const val IPV4 = 1
        private const val IPV6 = 28
        private val DOH_BASE = "https://$DOH_HOST/dns-query".toHttpUrl()
        private val DOH_ADDRESSES = listOf(
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)),
        )
        private val client by lazy {
            OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname == DOH_HOST) DOH_ADDRESSES else throw UnknownHostException()
                })
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val route = chain.connection()?.route() ?: throw UnknownHostException()
                    val address = route.socketAddress.address
                    if (chain.request().url.host != DOH_HOST || route.proxy.type() != Proxy.Type.DIRECT ||
                        route.socketAddress.port != 443 || address !in DOH_ADDRESSES ||
                        chain.request().tag(AtomicBoolean::class.java)?.compareAndSet(false, true) != true)
                        throw UnknownHostException()
                    chain.proceed(chain.request())
                }
                .build()
        }

        internal fun parseAnswers(text: String, hostname: String, type: Int): List<InetAddress> {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
            val row = McpRpcResponse.boundedObject(text, MAX_RESPONSE_BYTES)
            require(row.getValue("Status").jsonPrimitive.intOrNull == 0)
            require(row["TC"]?.jsonPrimitive?.booleanOrNull == false)
            val questions = row["Question"]?.jsonArray ?: throw IllegalArgumentException()
            require(questions.isNotEmpty() && questions.size <= 2)
            require(questions.all { item ->
                val question = item.jsonObject
                val name = question.getValue("name").jsonPrimitive.also { require(it.isString) }.content
                question.getValue("type").jsonPrimitive.intOrNull == type &&
                    name.trimEnd('.').equals(hostname, ignoreCase = true)
            })
            val answers = row["Answer"]?.let { (it as? JsonArray)?.toList() ?: throw IllegalArgumentException() }
                ?: return emptyList()
            require(answers.size <= 32)
            return answers.mapNotNull { item ->
                val answer = item as? JsonObject ?: throw IllegalArgumentException()
                if (answer.getValue("type").jsonPrimitive.intOrNull != type) null else {
                    val data = answer.getValue("data").jsonPrimitive.also { require(it.isString) }.content
                    parseAddress(data, type).also { require(McpPublicDns.isPublic(it)) }
                }
            }.distinctBy { it.address.toList() }.map { InetAddress.getByAddress(hostname, it.address) }
        }

        private fun parseAddress(data: String, type: Int): InetAddress {
            require(data.length in 1..64)
            if (type == IPV4) require(IPV4_LITERAL.matches(data)) else require(data.contains(':') && IPV6_LITERAL.matches(data))
            return InetAddress.getByName(data).also { require(it.address.size == if (type == IPV4) 4 else 16) }
        }

        private val IPV4_LITERAL = Regex(
            "^(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\\.){3}(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})$"
        )
        private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.]+$")

        private fun queryDoh(url: HttpUrl): String {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store")
                .tag(AtomicBoolean::class.java, AtomicBoolean(false))
                .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.code != 200 || response.isRedirect ||
                    response.request.url != url || response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } == true)
                    throw IOException()
                val body = response.body ?: throw IOException()
                val contentType = body.contentType()
                if (contentType?.type != "application" ||
                    contentType.subtype !in setOf("json", "dns-json") ||
                    contentType.parameter("charset")?.lowercase()?.let { it != "utf-8" } == true)
                    throw IOException()
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException()
                val bytes = body.byteStream().use { stream ->
                    val buffer = ByteArray(MAX_RESPONSE_BYTES + 1)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size > MAX_RESPONSE_BYTES) throw IOException()
                    buffer.copyOf(size)
                }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }
        }
    }
}
