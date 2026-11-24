package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.mcp.McpDiscoveryApi
import com.healthyagent.running.agent.mcp.McpDiscoveryView
import com.healthyagent.running.agent.mcp.McpDohDnsFallback
import com.healthyagent.running.agent.mcp.McpManagementApi
import com.healthyagent.running.agent.mcp.McpManagementWire
import com.healthyagent.running.agent.mcp.McpPhoneRequest
import com.healthyagent.running.agent.mcp.McpPhoneScope
import com.healthyagent.running.agent.mcp.McpPublicDns
import com.healthyagent.running.agent.mcp.McpRpcResponse
import com.healthyagent.running.agent.mcp.McpTaskApi
import com.healthyagent.running.agent.mcp.McpTaskContext
import com.healthyagent.running.agent.mcp.McpTaskDelivery
import com.healthyagent.running.agent.mcp.McpTaskInvocation
import com.healthyagent.running.agent.mcp.McpTaskWire
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

internal object CloudDohPub {
    private const val DOH_HOST = "doh.pub"
    private const val MAX_RESPONSE_BYTES = 32768
    private const val IPV4 = 1
    private const val IPV6 = 28
    private val endpoint = "https://$DOH_HOST/dns-query".toHttpUrl()
    private val edge = InetAddress.getByAddress(byteArrayOf(1, 12, 12, 12))
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == DOH_HOST) listOf(edge) else throw UnknownHostException()
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
                if (chain.request().url.host != DOH_HOST || route.proxy.type() != Proxy.Type.DIRECT ||
                    route.socketAddress.port != 443 || route.socketAddress.address != edge ||
                    chain.request().tag(AtomicBoolean::class.java)?.compareAndSet(false, true) != true)
                    throw UnknownHostException()
                chain.proceed(chain.request())
            }
            .build()
    }

    fun lookup(hostname: String): List<InetAddress> = try {
        val ipv4 = query(hostname, IPV4)
        val addresses = ipv4.ifEmpty { query(hostname, IPV6) }
        if (addresses.isEmpty() || addresses.size > 32 || addresses.any { !McpPublicDns.isPublic(it) })
            throw UnknownHostException("DoH 地址不可连接")
        addresses.distinctBy { it.address.toList() }
    } catch (error: UnknownHostException) {
        throw error
    } catch (_: Exception) {
        throw UnknownHostException("DoH 解析失败")
    }

    private fun query(hostname: String, type: Int): List<InetAddress> {
        val url = endpoint.newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", type.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .tag(AtomicBoolean::class.java, AtomicBoolean(false))
            .build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.code != 200 || response.isRedirect ||
                response.request.url != url || response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } == true)
                throw IOException()
            val body = response.body ?: throw IOException()
            val media = body.contentType()
            if (media?.type != "application" || media.subtype != "json" ||
                media.parameter("charset")?.lowercase()?.let { it != "utf-8" } == true)
                throw IOException()
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException()
            val bytes = body.byteStream().use { it.readCloudBytes(MAX_RESPONSE_BYTES) }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }
        return McpDohDnsFallback.parseAnswers(text, hostname, type)
    }
}

internal class CloudPublicDnsCache(private val maxEntries: Int = 32) {
    private data class Entry(val values: List<InetAddress>, val expiresAtMillis: Long)
    private val entries = linkedMapOf<String, Entry>()

    init { require(maxEntries > 0) }

    @Synchronized
    fun get(hostname: String, nowMillis: Long): List<InetAddress>? {
        val entry = entries[hostname] ?: return null
        if (nowMillis >= entry.expiresAtMillis) {
            entries.remove(hostname)
            return null
        }
        return entry.values
    }

    @Synchronized
    fun put(hostname: String, values: List<InetAddress>, expiresAtMillis: Long) {
        entries.remove(hostname)
        if (entries.size >= maxEntries) entries.remove(entries.keys.first())
        entries[hostname] = Entry(values, expiresAtMillis)
    }
}

private val sharedCloudPublicDnsCache = CloudPublicDnsCache()

internal class CloudPublicDns(
    private val hostname: String,
    private val resolve: (String) -> List<InetAddress> = CloudDohPub::lookup,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = 30_000L,
    private val cache: CloudPublicDnsCache = sharedCloudPublicDnsCache,
) : Dns {
    init { require(cacheTtlMillis > 0) }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != this.hostname) throw UnknownHostException()
        val now = nowMillis()
        cache.get(hostname, now)?.let { return it }
        val addresses = try { resolve(hostname) } catch (_: Exception) { throw UnknownHostException("云端地址不可连接") }
        if (addresses.isEmpty() || addresses.size > 32 || addresses.any { !McpPublicDns.isPublic(it) })
            throw UnknownHostException("云端地址不可连接")
        val result = Collections.unmodifiableList(
            addresses.distinctBy { it.address.toList() }.map { InetAddress.getByAddress(hostname, it.address) },
        )
        cache.put(hostname, result, nowMillis() + cacheTtlMillis)
        return result
    }
}

interface CloudTaskApi {
    val supportsStreaming: Boolean get() = false
    suspend fun events(identity: CloudTaskIdentity, taskId: String, after: Int, consume: suspend (CloudTaskEvent) -> Unit): Unit = throw CloudTaskFailure("invalid_request")
    suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse
    suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject): CloudTaskResponse = throw CloudTaskFailure("invalid_request")
    suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse
    suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse
    suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse
    suspend fun purge(identity: CloudTaskIdentity, requestId: String): Unit = throw CloudTaskFailure("invalid_request")
    suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean = false): CloudTaskResponse = throw CloudTaskFailure("invalid_request")
    suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool? = throw CloudTaskFailure("invalid_request")
    suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject): Unit = throw CloudTaskFailure("invalid_request")
}

/** 固定 origin、无日志/缓存/重定向/自动认证；POST 正文明确不可重放。 */
class CloudTaskHttpClient internal constructor(
    private val origin: String, private val credentials: CloudCredentialSource, private val calls: Call.Factory,
    override val supportsStreaming: Boolean = false,
) : CloudTaskApi, McpDiscoveryApi, McpManagementApi, McpTaskApi {
    constructor(origin: String, credentials: CloudCredentialSource, streaming: Boolean = false) :
        this(origin, credentials, clientBuilder(origin.toHttpUrl()).build(), streaming)
    init { require(origin.toHttpUrl().let { it.isHttps && it.toString() == "$origin/" && it.encodedPath == "/" && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }) { "云服务地址无效" } }

    override suspend fun listServices(identity: CloudTaskIdentity, after: String?): JsonObject {
        after?.let(McpManagementWire::id)
        return exchange(identity, "mcp/services" + (after?.let { "?after=$it" } ?: ""), null) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpManagementWire.parse(text, true)
        }
    }
    override suspend fun getService(identity: CloudTaskIdentity, id: String, summaryOnly: Boolean): JsonObject {
        McpManagementWire.id(id)
        return exchange(identity, "mcp/services/$id" + (if (summaryOnly) "/summary" else ""), null, if (summaryOnly) 8192 else 262144) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpManagementWire.parse(text, false, summaryOnly).getValue("service").jsonObject.also {
                if (it["id"] != JsonPrimitive(id)) throw CloudTaskFailure("invalid_response")
            }
        }
    }
    override suspend fun changeService(identity: CloudTaskIdentity, id: String?, action: String, body: JsonObject): JsonObject {
        val keys = when (action) { "register" -> setOf("endpoint"); "disable", "remove" -> setOf("revision")
            "endpoint" -> setOf("revision", "endpoint")
            "approve" -> setOf("revision", "selection", "confirmed"); else -> throw CloudTaskFailure("invalid_request") }
        require(body.keys == keys)
        if (action == "register") { require(id == null); com.healthyagent.running.agent.mcp.McpEndpoint.parse(McpManagementWire.text(body, "endpoint")) }
        else {
            McpManagementWire.id(requireNotNull(id)); McpManagementWire.id(McpManagementWire.text(body, "revision"))
            if (action == "endpoint") com.healthyagent.running.agent.mcp.McpEndpoint.parse(McpManagementWire.text(body, "endpoint"))
            if (action == "approve") com.healthyagent.running.agent.mcp.McpManagementCommands.validate("mcp.approve", buildJsonObject {
                put("identityToken", CloudChatConsent.context(identity)); put("id", id); body.forEach { (key, value) -> put(key, value) }
            })
        }
        val path = "mcp/services" + (id?.let { "/$it/$action" } ?: "")
        return exchange(identity, path, body.toString(), 262144) { text, status ->
            if (status != if (action == "register") 201 else 200) throw CloudTaskFailure("invalid_response")
            if (action == "remove") return@exchange McpRpcResponse.boundedObject(text, 262144).also {
                if (it.keys != setOf("removed") || it["removed"] != JsonPrimitive(true)) throw CloudTaskFailure("invalid_response")
            }
            McpManagementWire.parse(text, false).getValue("service").jsonObject.also {
                if (id != null && it["id"] != JsonPrimitive(id)) throw CloudTaskFailure("invalid_response")
                val grants = it.getValue("grants").jsonArray
                val valid = when (action) {
                    "register" -> it["endpoint"] == body["endpoint"] && it["enabled"] == JsonPrimitive(false) && it["catalog"] == JsonNull && grants.isEmpty()
                    "disable" -> it["enabled"] == JsonPrimitive(false) && grants.isEmpty() && it["revision"] != body["revision"]
                    "endpoint" -> it["endpoint"] == body["endpoint"] && it["revision"] != body["revision"] &&
                        it["enabled"] == JsonPrimitive(false) && it["catalog"] == JsonNull && grants.isEmpty()
                    else -> it["enabled"] == JsonPrimitive(true) && it["revision"] != body["revision"] &&
                        JsonArray(grants.map { grant -> JsonObject(grant.jsonObject.filterKeys { key -> key != "modelName" }) }) == body["selection"]
                }
                if (!valid) throw CloudTaskFailure("invalid_response")
            }
        }
    }

    override suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView {
        McpDiscoveryView.validateScope(scope)
        val body = buildJsonObject { put("requestId", scope.requestId); put("serviceId", scope.serviceId)
            put("revision", scope.revision); put("generation", scope.generation) }
        return exchange(scope.identity, "mcp/discoveries", body.toString()) { text, status ->
            McpDiscoveryView.parse(text, scope).also { if (status != if (it.terminal) 200 else 202) throw CloudTaskFailure("invalid_response") }
        }
    }
    override suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView {
        McpDiscoveryView.validateScope(scope)
        return exchange(scope.identity, "mcp/discoveries/${scope.requestId}", null) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpDiscoveryView.parse(text, scope)
        }
    }
    override suspend fun takeDiscovery(scope: McpPhoneScope): McpPhoneRequest? {
        McpDiscoveryView.validateScope(scope)
        // take 是不可重放的空 POST；上限单独覆盖 16 KiB 请求正文经过 JSON 转义后的信封。
        return exchange(scope.identity, "mcp/discoveries/${scope.requestId}/take", "", 131072) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            val row = McpRpcResponse.boundedObject(text, 131072)
            if (row.keys != setOf("exchange")) throw CloudTaskFailure("invalid_response")
            if (row.getValue("exchange") == JsonNull) null else McpPhoneRequest.parse(row.getValue("exchange").toString()).also {
                if (!it.matches(scope)) throw CloudTaskFailure("invalid_response")
            }
        }
    }
    override suspend fun replyDiscovery(scope: McpPhoneScope, packet: McpPhoneRequest, reply: JsonObject) {
        McpDiscoveryView.validateScope(scope)
        val body = McpPhoneRequest.validateReply(scope, packet, reply)
        exchange(scope.identity, "mcp/discoveries/${scope.requestId}/reply", body) { text, status ->
            val value = McpRpcResponse.boundedObject(text, 4096)
            if (status != 200 || value.keys != setOf("accepted") || value["accepted"] != JsonPrimitive(true)) throw CloudTaskFailure("invalid_response")
        }
    }

    override suspend fun taskConfirmation(context: McpTaskContext): McpTaskInvocation? =
        exchange(context.intent.identity, "tasks/${context.receipt.cloudTaskId}/mcp", null) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpTaskWire.confirmation(text, context)
        }

    override suspend fun takeTaskExchange(context: McpTaskContext): McpTaskDelivery? =
        exchange(context.intent.identity, "tasks/${context.receipt.cloudTaskId}/mcp/take", "", 196608) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpTaskWire.delivery(text, context)
        }

    override suspend fun replyTaskExchange(context: McpTaskContext, delivery: McpTaskDelivery, reply: JsonObject) {
        try { delivery.validate(context) } catch (_: Exception) { throw CloudTaskFailure("invalid_request") }
        val body = McpPhoneRequest.validateReply(delivery.invocation.scope, delivery.packet, reply)
        exchange(context.intent.identity, "tasks/${context.receipt.cloudTaskId}/mcp/reply", body, 4096) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpTaskWire.accepted(text)
        }
    }

    override suspend fun decideTaskInvocation(context: McpTaskContext, invocation: McpTaskInvocation, decision: String) {
        val body = try { invocation.decision(context, decision) } catch (_: Exception) { throw CloudTaskFailure("invalid_request") }
        exchange(context.intent.identity, "tasks/${context.receipt.cloudTaskId}/mcp/decision", body.toString(), 4096) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpTaskWire.decision(text, invocation, decision)
        }
    }

    override suspend fun closeTaskMcp(context: McpTaskContext) {
        exchange(context.intent.identity, "tasks/${context.receipt.cloudTaskId}/mcp/close", "", 4096) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            McpTaskWire.accepted(text)
        }
    }

    override suspend fun submit(intent: CloudTaskIntent) = request(intent.identity, "tasks", intent.submission().toString(), "submit")
    override suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject) = request(intent.identity, "tasks", intent.submission(memoryContext).toString(), "submit")
    override suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean) =
        request(intent.identity, "tasks", CloudMemoryTool.submission(intent, context, generation, factsAllowed).toString(), "submit")
    override suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool? {
        require(CloudTaskResponse.CLOUD_UUID.matches(taskId))
        return exchange(identity, "tasks/$taskId/memory", null) { text, status ->
            if (status != 200) throw CloudTaskFailure("invalid_response")
            CloudMemoryTool.parse(text, taskId)
        }
    }
    override suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject) {
        require(CloudTaskResponse.CLOUD_UUID.matches(tool.taskId))
        exchange(identity, "tasks/${tool.taskId}/memory", tool.reply(observation).toString()) { text, status ->
            val reply = CloudTaskResponse.json.parseToJsonElement(text).jsonObject
            if (status != 200 || reply.keys != setOf("accepted") || reply["accepted"] != JsonPrimitive(true))
                throw CloudTaskFailure("invalid_response")
        }
    }
    override suspend fun purge(identity: CloudTaskIdentity, requestId: String) {
        com.healthyagent.running.agent.memory.MemoryCommands.id(requestId)
        exchange(identity, "requests/$requestId/purge", "", 4096) { text, status ->
            val result = McpRpcResponse.boundedObject(text, 4096)
            if (status != 200 || result.keys != setOf("requestId", "purged") ||
                result["requestId"] != JsonPrimitive(requestId) || result["purged"] != JsonPrimitive(true))
                throw CloudTaskFailure("invalid_response")
        }
    }

    override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse {
        require(CloudTaskResponse.CLOUD_UUID.matches(requestId))
        return request(identity, "requests/$requestId", null, "find")
    }
    override suspend fun get(identity: CloudTaskIdentity, taskId: String) = byId(identity, taskId, null)
    override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse {
        require(action in setOf("cancel", "withdraw", "acknowledge"))
        return byId(identity, taskId, action)
    }
    private suspend fun byId(identity: CloudTaskIdentity, taskId: String, action: String?): CloudTaskResponse {
        require(CloudTaskResponse.CLOUD_UUID.matches(taskId))
        val view = request(identity, "tasks/$taskId" + (action?.let { "/$it" } ?: ""), if (action == null) null else "", action ?: "get")
        if (view.id != taskId) throw CloudTaskFailure("invalid_response")
        return view
    }
    private suspend fun request(identity: CloudTaskIdentity, path: String, body: String?, operation: String): CloudTaskResponse =
        exchange(identity, path, body, streaming = supportsStreaming && operation == "submit") { text, status ->
            val view = CloudTaskResponse.parse(text)
            if (status != if (operation == "submit" && !view.terminal) 202 else 200) throw CloudTaskFailure("invalid_response")
            if (operation in setOf("cancel", "withdraw", "acknowledge") && !view.terminal) throw CloudTaskFailure("invalid_response")
            view
        }

    private suspend fun <T> exchange(identity: CloudTaskIdentity, path: String, body: String?, responseLimit: Int = 65536,
        streaming: Boolean = false,
        decode: (String, Int) -> T): T = withContext(Dispatchers.IO) {
        if (identity.origin != origin) throw CloudTaskFailure("credential_unavailable")
        val bearer = credentials.read(identity)
        val builder = Request.Builder().url("$origin/v1/agent/$path")
            .header("Authorization", "Bearer ${bearer.value}").header("Accept", "application/json")
            .header("Cache-Control", "no-store").tag(AtomicBoolean::class.java, AtomicBoolean(false))
        if (streaming) builder.header("X-Agent-Stream", "task_events_v1")
        if (body != null) builder.post(object : RequestBody() {
            private val bytes = body.toByteArray(Charsets.UTF_8)
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        })
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(CloudTaskFailure(CloudTaskDiagnostics.networkCode(e))) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use { parse(it, responseLimit, decode) }
                        continuation.resume(value)
                    } catch (error: Exception) {
                        continuation.resumeWithException(when (error) {
                            is CloudTaskFailure -> error
                            is java.nio.charset.CharacterCodingException -> CloudTaskFailure("invalid_response")
                            is IOException -> CloudTaskFailure(CloudTaskDiagnostics.networkCode(error))
                            else -> CloudTaskFailure("invalid_response")
                        })
                    }
                }
            })
        }
    }

    override suspend fun events(identity: CloudTaskIdentity, taskId: String, after: Int,
        consume: suspend (CloudTaskEvent) -> Unit): Unit = withContext(Dispatchers.IO) {
        require(supportsStreaming && CloudTaskResponse.CLOUD_UUID.matches(taskId) && after in 0..12016)
        if (identity.origin != origin) throw CloudTaskFailure("credential_unavailable")
        val bearer = credentials.read(identity)
        val call = calls.newCall(Request.Builder().url("$origin/v1/agent/tasks/$taskId/events/$after")
            .header("Authorization", "Bearer ${bearer.value}").header("Accept", "text/event-stream")
            .header("Cache-Control", "no-store").tag(AtomicBoolean::class.java, AtomicBoolean(false)).build())
        kotlinx.coroutines.coroutineScope {
            // 同步读取仍可被协程取消打断；只取消本次GET，不发送任务控制或重试。
            val cancellation = launch(kotlinx.coroutines.Dispatchers.Default,
                start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code != 200) parse(response, 4096) { _, _ -> throw CloudTaskFailure("invalid_response") }
                    val body = response.body ?: throw CloudTaskFailure("invalid_response")
                    if (body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } != true)
                        throw CloudTaskFailure("invalid_response")
                    CloudTaskEvents.read(body.byteStream(), taskId, after) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive(); consume(it)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                throw when (error) {
                    is CloudTaskFailure -> error
                    is IOException -> CloudTaskFailure(CloudTaskDiagnostics.networkCode(error))
                    else -> CloudTaskFailure("invalid_response")
                }
            } finally { cancellation.cancel() }
        }
    }

    private fun <T> parse(response: Response, responseLimit: Int, decode: (String, Int) -> T): T {
        if (response.code !in setOf(200, 201, 202, 400, 401, 404, 409, 410, 413, 415, 503)) throw CloudTaskFailure("invalid_response")
        val body = response.body ?: throw CloudTaskFailure("invalid_response")
        if (body.contentType()?.let { it.type == "application" && it.subtype == "json" } != true) throw CloudTaskFailure("invalid_response")
        val bytes = body.byteStream().use { it.readCloudBytes(responseLimit) }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (response.code >= 400) {
            val error = CloudTaskResponse.json.parseToJsonElement(text).jsonObject
            val code = (error["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val allowed = when (response.code) {
                400, 413, 415 -> setOf("invalid_request"); 401 -> setOf("unauthenticated"); 404 -> setOf("not_found")
                409 -> setOf("conflict", "busy", "inactive"); 410 -> setOf("request_expired"); else -> setOf("unavailable")
            }
            if (error.keys != setOf("error") || code !in allowed) throw CloudTaskFailure("invalid_response")
            throw CloudTaskFailure(requireNotNull(code))
        }
        return decode(text, response.code)
    }

    companion object {
        internal fun clientBuilder() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES).cache(null).connectTimeout(10, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                // 即使库增加新的 follow-up 路径，也不能在同一 Call 中第二次发网。
                if (chain.request().tag(AtomicBoolean::class.java)?.compareAndSet(false, true) != true) throw IOException("拒绝隐式重复请求")
                chain.proceed(chain.request())
            }
        internal fun clientBuilder(origin: HttpUrl) = clientBuilder()
            .dns(CloudPublicDns(origin.host)).proxy(Proxy.NO_PROXY)
            .addNetworkInterceptor { chain ->
                val route = chain.connection()?.route() ?: throw IOException("云端连接缺少路由")
                if (chain.request().url.host != origin.host || route.proxy.type() != Proxy.Type.DIRECT ||
                    route.address.url.host != origin.host || route.socketAddress.port != 443 ||
                    route.socketAddress.address?.let(McpPublicDns::isPublic) != true)
                    throw IOException("云端地址不可连接")
                chain.proceed(chain.request())
            }
    }
}

/** 不依赖 Android 低版本缺少的 InputStream.readNBytes。 */
internal fun InputStream.readCloudBytes(limit: Int): ByteArray {
    val bytes = ByteArray(limit + 1)
    var size = 0
    while (size < bytes.size) {
        val count = read(bytes, size, bytes.size - size)
        if (count == -1) break
        size += count
    }
    require(size <= limit) { "输入超过大小限制" }
    return bytes.copyOf(size)
}
