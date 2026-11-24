package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** 这些预期值必须由可信宿主从原任务、当前身份和本机配置读取，不能照抄收到的请求作为授权。 */
data class McpPhoneScope(
    val identity: CloudTaskIdentity, val requestId: String, val taskId: String, val serviceId: String,
    val revision: String, val generation: String, val endpoint: McpEndpoint,
)

class McpPhoneRequest private constructor(
    val requestId: String, val taskId: String, val serviceId: String, val revision: String, val generation: String,
    val endpoint: McpEndpoint, val exchangeId: String, val method: String, val body: String?,
    val sessionId: String?, val protocolVersion: String?,
) {
    override fun toString() = "McpPhoneRequest(正文与会话已脱敏)"

    internal fun matches(scope: McpPhoneScope) = requestId == scope.requestId && taskId == scope.taskId &&
        serviceId == scope.serviceId && revision == scope.revision && generation == scope.generation && endpoint.url == scope.endpoint.url

    internal fun reply(response: McpHttpReply? = null, error: String? = null) = buildJsonObject {
        put("version", "native_mcp_v1"); put("exchangeId", exchangeId); put("generation", generation)
        if (error != null) put("error", error) else {
            val value = requireNotNull(response)
            require(!value.eventStream && value.status in setOf(200, 202, 204, 405))
            require(value.text.toByteArray(Charsets.UTF_8).size <= 131072 && (if (value.status == 200) value.text.isNotEmpty() else value.text.isEmpty()))
            require(value.sessionId == null || (value.sessionId.length in 1..256 && value.sessionId.all { it.code in 33..126 }))
            put("status", value.status); put("text", value.text); put("sessionId", value.sessionId?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    companion object {
        /** 发现和正式任务共用同一回执约束；插件密钥、任意响应头和原始错误不得进入云正文。 */
        internal fun validateReply(scope: McpPhoneScope, packet: McpPhoneRequest, reply: JsonObject): String {
            if (!packet.matches(scope) || reply["version"] != JsonPrimitive("native_mcp_v1") ||
                reply["exchangeId"] != JsonPrimitive(packet.exchangeId) || reply["generation"] != JsonPrimitive(scope.generation) ||
                reply.keys != if ("error" in reply) setOf("version", "exchangeId", "generation", "error")
                    else setOf("version", "exchangeId", "generation", "status", "text", "sessionId")) throw CloudTaskFailure("invalid_request")
            val body = reply.toString()
            if (body.toByteArray(Charsets.UTF_8).size > 800000) throw CloudTaskFailure("invalid_request")
            if ("error" in reply) {
                if (reply["error"] !in setOf("credential_unavailable", "stale_configuration", "transport_failed", "invalid_response",
                        "catalog_changed", "limit_exceeded", "authentication_required", "cancelled").map(::JsonPrimitive))
                    throw CloudTaskFailure("invalid_request")
            } else {
                val status = reply["status"] as? JsonPrimitive
                val text = reply["text"] as? JsonPrimitive
                val session = reply["sessionId"]
                if (status == null || status.isString || status.intOrNull !in setOf(200, 202, 204, 405) ||
                    text == null || !text.isString || text.content.toByteArray(Charsets.UTF_8).size > 131072 ||
                    (if (status.intOrNull == 200) text.content.isEmpty() else text.content.isNotEmpty()) ||
                    (session != JsonNull && (session !is JsonPrimitive || !session.isString || session.content.length !in 1..256 ||
                        session.content.any { it.code !in 33..126 }))) throw CloudTaskFailure("invalid_request")
            }
            return body
        }

        fun parse(text: String): McpPhoneRequest = try {
            val row = McpRpcResponse.boundedObject(text, 131072)
            require(row.keys == setOf("version", "requestId", "taskId", "serviceId", "revision", "generation", "endpoint",
                "exchangeId", "method", "body", "sessionId", "protocolVersion"))
            fun string(key: String) = row.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            fun nullable(key: String) = if (row.getValue(key) == JsonNull) null else string(key)
            fun uuid(key: String) = string(key).also { require(CloudTaskResponse.CLOUD_UUID.matches(it)) }
            require(string("version") == "native_mcp_v1")
            val method = string("method"); val body = nullable("body")
            require(method in setOf("POST", "DELETE") && (method == "POST") == (body != null))
            if (body != null) McpRpcResponse.requestId(body)
            val session = nullable("sessionId").also { value -> require(value == null ||
                (value.length in 1..256 && value.all { it.code in 33..126 })) }
            val protocol = nullable("protocolVersion").also { require(it == null || it in setOf("2025-11-25", "2025-06-18", "2025-03-26")) }
            McpPhoneRequest(uuid("requestId"), uuid("taskId"), uuid("serviceId"), uuid("revision"), uuid("generation"),
                McpEndpoint.parse(string("endpoint")), uuid("exchangeId"), method, body, session, protocol)
        } catch (_: Exception) { throw McpTransportFailure("invalid_response") }
    }
}

/** 每个通道一个实例；唯一交付来自云端 take，不持久化正文或恢复重发。宿主必须另行核验任务与工具意图。 */
class McpPhoneExchange internal constructor(
    private val scope: McpPhoneScope, private val configuration: McpPrivateConfiguration,
    private val authorize: suspend (McpPhoneRequest) -> Unit,
    private val send: (suspend (McpEndpoint, McpCredentialSource, McpPhoneRequest, suspend (() -> Unit) -> Unit) -> McpHttpReply)? = null,
) {
    private val lock = Any()
    private val seen = mutableSetOf<String>()
    private var running = false
    private val source by lazy { configuration.source(McpPrivateSlot(scope.identity, scope.serviceId), scope.endpoint, scope.generation) }
    private val transport by lazy { McpHttpTransport(scope.endpoint, source) }

    suspend fun execute(request: McpPhoneRequest, dispatch: suspend (() -> Unit) -> Unit = { it() }): JsonObject {
        synchronized(lock) {
            if (running || !request.matches(scope) || request.exchangeId in seen || seen.size >= 13)
                throw McpTransportFailure("invalid_request")
            seen.add(request.exchangeId); running = true
        }
        return try {
            withTimeout(10000) {
                authorize(request)
                val response = if (send != null) send.invoke(scope.endpoint, source, request, dispatch)
                    else if (request.method == "POST") transport.exchangeRpc(requireNotNull(request.body), request.sessionId, request.protocolVersion, dispatch)
                    else transport.exchange("DELETE", sessionId = request.sessionId, protocolVersion = request.protocolVersion, dispatch = dispatch)
                // 撤权不撤销已发送的外部副作用，但不把迟到结果交回模型。
                authorize(request)
                request.reply(response)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val code = (error as? McpTransportFailure)?.code?.takeIf { it in setOf("credential_unavailable", "stale_configuration",
                "transport_failed", "invalid_response", "catalog_changed", "limit_exceeded", "authentication_required") } ?: "transport_failed"
            request.reply(error = code)
        } finally { synchronized(lock) { running = false } }
    }
}
