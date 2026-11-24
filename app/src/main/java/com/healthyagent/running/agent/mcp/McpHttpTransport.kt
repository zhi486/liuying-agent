package com.healthyagent.running.agent.mcp

import java.io.IOException
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

class McpTransportFailure(val code: String) : IOException("插件连接未完成")

/** 仅由原生私密配置源提供，不接受模型参数或云设备凭据的自动转发。 */
class McpBearer internal constructor(internal val value: String) {
    init { if (value.length !in 1..4096 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(value)) throw McpTransportFailure("credential_unavailable") }
    override fun toString() = "McpBearer(已脱敏)"
}
fun interface McpCredentialSource { fun read(): McpBearer? }

class McpHttpReply internal constructor(val status: Int, val text: String, val sessionId: String?, val eventStream: Boolean) {
    override fun toString() = "McpHttpReply(status=$status, 正文与会话已脱敏)"
}

/** 原生受限 HTTP 基础层；尚未注册到 Bridge 或模型，不自行发现、批准或重试工具。 */
class McpHttpTransport internal constructor(
    private val endpoint: McpEndpoint, private val credentials: McpCredentialSource, private val calls: Call.Factory,
) {
    constructor(endpoint: McpEndpoint, credentials: McpCredentialSource) : this(endpoint, credentials, clientBuilder(endpoint).build())

    suspend fun exchange(method: String, body: String? = null, sessionId: String? = null, protocolVersion: String? = null,
        dispatch: suspend (() -> Unit) -> Unit = { it() }): McpHttpReply =
        execute(method, body, sessionId, protocolVersion, false, null, dispatch)

    /** 供既有 SDK 的可信手机传输端口使用；SSE 终止响应转为单个 JSON，不另建 MCP 客户端。 */
    suspend fun exchangeRpc(body: String, sessionId: String? = null, protocolVersion: String? = null,
        dispatch: suspend (() -> Unit) -> Unit = { it() }): McpHttpReply =
        execute("POST", body, sessionId, protocolVersion, true, McpRpcResponse.requestId(body), dispatch)

    private suspend fun execute(method: String, body: String?, sessionId: String?, protocolVersion: String?, rpc: Boolean,
        rpcId: JsonPrimitive?, dispatch: suspend (() -> Unit) -> Unit): McpHttpReply =
        withTimeout(10000) { withContext(Dispatchers.IO) {
            if (method !in setOf("POST", "GET", "DELETE") || (method == "POST") != (body != null) ||
                (body != null && body.toByteArray(Charsets.UTF_8).size > 16384) ||
                (sessionId != null && !validSession(sessionId)) ||
                (protocolVersion != null && protocolVersion !in setOf("2025-11-25", "2025-06-18", "2025-03-26")))
                throw McpTransportFailure("invalid_request")
            val bearer = try { credentials.read() } catch (_: Exception) { throw McpTransportFailure("credential_unavailable") }
            val request = Request.Builder().url(endpoint.url).method(method, body?.let(::OneShotBody))
                .header("Accept", "application/json, text/event-stream").header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store").tag(AtomicBoolean::class.java, AtomicBoolean(false))
                .apply {
                    bearer?.let { header("Authorization", "Bearer ${it.value}") }
                    sessionId?.let { header("Mcp-Session-Id", it) }
                    protocolVersion?.let { header("MCP-Protocol-Version", it) }
                }.build()
            val call = calls.newCall(request)
            val result = CompletableDeferred<McpHttpReply>()
            val operationContext = currentCoroutineContext()
            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    result.completeExceptionally(if (e is McpTransportFailure) e else McpTransportFailure("transport_failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use { parse(it, rpc, rpcId) }
                        result.complete(reply)
                    } catch (error: Exception) {
                        result.completeExceptionally(if (error is McpTransportFailure) error else McpTransportFailure("invalid_response"))
                    }
                }
            }
            try {
                // 删除与实际派发共用宿主锁；只在锁内入队，网络等待不占锁。
                dispatch { operationContext.ensureActive(); call.enqueue(callback) }
                result.await()
            } finally { call.cancel() }
        } }

    private fun parse(response: Response, rpc: Boolean, rpcId: JsonPrimitive?): McpHttpReply {
        if (response.code == 401 || response.code == 403) throw McpTransportFailure("authentication_required")
        if (response.code == 405) return McpHttpReply(405, "", null, false)
        if (response.code !in setOf(200, 202, 204)) throw McpTransportFailure("remote_rejected")
        if (rpc && response.code != (if (rpcId == null) 202 else 200)) throw McpTransportFailure("invalid_response")
        if (response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } == true) throw McpTransportFailure("invalid_response")
        val session = response.header("Mcp-Session-Id")
        if (session != null && !validSession(session)) throw McpTransportFailure("invalid_response")
        val body = response.body ?: throw McpTransportFailure("invalid_response")
        val media = body.contentType()
        val sse = media?.let { it.type == "text" && it.subtype == "event-stream" } == true
        if (response.code == 200 && !sse && media?.let { it.type == "application" && it.subtype == "json" } != true)
            throw McpTransportFailure("invalid_response")
        if (media?.parameter("charset")?.lowercase()?.let { it != "utf-8" } == true) throw McpTransportFailure("invalid_response")
        if (body.contentLength() > 131072) throw McpTransportFailure("limit_exceeded")
        if (rpc && sse) {
            if (rpcId == null) throw McpTransportFailure("invalid_response")
            return McpHttpReply(response.code, body.byteStream().use { McpRpcResponse.readSse(it, rpcId) }, session, false)
        }
        val bytes = body.byteStream().use { stream ->
            val buffer = ByteArray(131073)
            var size = 0
            while (size < buffer.size) {
                val count = stream.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            if (size > 131072) throw McpTransportFailure("limit_exceeded")
            buffer.copyOf(size)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (rpc && (if (rpcId == null) text.isNotEmpty() else !McpRpcResponse.validateResponse(text, rpcId)))
            throw McpTransportFailure("invalid_response")
        return McpHttpReply(response.code, text, session, sse)
    }

    private class OneShotBody(text: String) : RequestBody() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        override fun contentType() = "application/json; charset=utf-8".toMediaType()
        override fun contentLength() = bytes.size.toLong()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
    }

    companion object {
        private fun validSession(value: String) = value.length in 1..256 && value.all { it.code in 33..126 }
        internal fun clientBuilder(endpoint: McpEndpoint, dns: Dns = Dns.SYSTEM): OkHttpClient.Builder = OkHttpClient.Builder()
            .dns(McpPublicDns(endpoint, dns, McpDohDnsFallback())).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).cache(null)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val route = chain.connection()?.route() ?: throw McpTransportFailure("address_rejected")
                if (chain.request().url != endpoint.url || route.proxy.type() != Proxy.Type.DIRECT ||
                    route.address.url.host != endpoint.url.host || route.socketAddress.port != 443 ||
                    route.socketAddress.address?.let { McpPublicDns.isPublic(it) } != true)
                    throw McpTransportFailure("address_rejected")
                // retryOnConnectionFailure=false 仍不足以禁止所有 HTTP follow-up；在真实发送边界再拦一次。
                if (chain.request().tag(AtomicBoolean::class.java)?.compareAndSet(false, true) != true)
                    throw McpTransportFailure("duplicate_request")
                chain.proceed(chain.request())
            }
    }
}
