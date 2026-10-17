package com.healthyagent.running.agent.cloud

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

interface CloudTaskApi {
    suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse
    suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject): CloudTaskResponse = throw CloudTaskFailure("invalid_request")
    suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse
    suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse
    suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse
}

/** 固定 origin、无日志/缓存/重定向/自动认证；POST 正文明确不可重放。 */
class CloudTaskHttpClient internal constructor(
    private val origin: String, private val credentials: CloudCredentialSource, private val calls: Call.Factory,
) : CloudTaskApi {
    constructor(origin: String, credentials: CloudCredentialSource) : this(origin, credentials, clientBuilder().build())
    init { require(origin.toHttpUrl().let { it.isHttps && it.toString() == "$origin/" && it.encodedPath == "/" && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }) { "云服务地址无效" } }

    override suspend fun submit(intent: CloudTaskIntent) = request(intent.identity, "tasks", intent.submission().toString(), "submit")
    override suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject) = request(intent.identity, "tasks", intent.submission(memoryContext).toString(), "submit")
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
    private suspend fun request(identity: CloudTaskIdentity, path: String, body: String?, operation: String): CloudTaskResponse = withContext(Dispatchers.IO) {
        if (identity.origin != origin) throw CloudTaskFailure("credential_unavailable")
        val bearer = credentials.read(identity)
        val builder = Request.Builder().url("$origin/v1/agent/$path")
            .header("Authorization", "Bearer ${bearer.value}").header("Accept", "application/json")
            .header("Cache-Control", "no-store").tag(AtomicBoolean::class.java, AtomicBoolean(false))
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
                        val value = response.use { parse(it, operation) }
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

    private fun parse(response: Response, operation: String): CloudTaskResponse {
        if (response.code !in setOf(200, 202, 400, 401, 404, 409, 410, 413, 415, 503)) throw CloudTaskFailure("invalid_response")
        val body = response.body ?: throw CloudTaskFailure("invalid_response")
        if (body.contentType()?.let { it.type == "application" && it.subtype == "json" } != true) throw CloudTaskFailure("invalid_response")
        val bytes = body.byteStream().use { it.readCloudBytes(65536) }
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
        val view = CloudTaskResponse.parse(text)
        if (response.code != if (operation == "submit" && !view.terminal) 202 else 200) throw CloudTaskFailure("invalid_response")
        if (operation in setOf("cancel", "withdraw", "acknowledge") && !view.terminal) throw CloudTaskFailure("invalid_response")
        return view
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
