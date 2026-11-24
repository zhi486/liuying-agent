package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskResponse
import kotlinx.serialization.json.*

internal const val MCP_DISCOVERY_TIMEOUT_MS = 25_000L
private val MCP_DISCOVERY_DEADLINES_MS = setOf(10_000L, MCP_DISCOVERY_TIMEOUT_MS)

interface McpDiscoveryApi {
    suspend fun startDiscovery(scope: McpPhoneScope): McpDiscoveryView
    suspend fun discovery(scope: McpPhoneScope): McpDiscoveryView
    suspend fun takeDiscovery(scope: McpPhoneScope): McpPhoneRequest?
    suspend fun replyDiscovery(scope: McpPhoneScope, packet: McpPhoneRequest, reply: JsonObject)
}

/** 仅含配置操作状态，不包含目录正文、凭据或插件响应。 */
data class McpDiscoveryView(val startedAtMillis: Long, val deadlineAtMillis: Long, val status: String,
    val resultRevision: String?, val reason: String?) {
    val terminal get() = status != "running"

    internal fun envelope(scope: McpPhoneScope) = buildJsonObject { put("discovery", buildJsonObject {
        put("version", "mcp_discovery_v1"); put("requestId", scope.requestId); put("serviceId", scope.serviceId)
        put("revision", scope.revision); put("generation", scope.generation); put("endpoint", scope.endpoint.toString())
        put("startedAtMillis", startedAtMillis); put("deadlineAtMillis", deadlineAtMillis); put("status", status)
        put("resultRevision", resultRevision?.let(::JsonPrimitive) ?: JsonNull); put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
    }) }

    companion object {
        internal fun validateScope(scope: McpPhoneScope) {
            if (scope.taskId != scope.requestId || listOf(scope.requestId, scope.serviceId, scope.revision, scope.generation)
                    .any { !CloudTaskResponse.CLOUD_UUID.matches(it) }) throw CloudTaskFailure("invalid_request")
        }

        internal fun parse(text: String, scope: McpPhoneScope): McpDiscoveryView = try {
            validateScope(scope)
            val envelope = McpRpcResponse.boundedObject(text, 4096)
            require(envelope.keys == setOf("discovery"))
            val row = envelope.getValue("discovery").jsonObject
            require(row.keys == setOf("version", "requestId", "serviceId", "revision", "generation", "endpoint",
                "startedAtMillis", "deadlineAtMillis", "status", "resultRevision", "reason"))
            fun string(key: String) = row.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            fun nullable(key: String) = if (row.getValue(key) == JsonNull) null else string(key)
            fun number(key: String) = row.getValue(key).jsonPrimitive.also { require(!it.isString) }.long.also { require(it in 0..9007199254740991L) }
            require(string("version") == "mcp_discovery_v1" && string("requestId") == scope.requestId &&
                string("serviceId") == scope.serviceId && string("revision") == scope.revision &&
                string("generation") == scope.generation && string("endpoint") == scope.endpoint.url.toString())
            val started = number("startedAtMillis"); val deadline = number("deadlineAtMillis")
            require(deadline - started in MCP_DISCOVERY_DEADLINES_MS)
            val status = string("status"); val result = nullable("resultRevision"); val reason = nullable("reason")
            when (status) {
                "running" -> require(result == null && reason == null)
                "completed" -> require(result != null && CloudTaskResponse.CLOUD_UUID.matches(result) && reason == null)
                "unconfirmed" -> require(result == null && reason in setOf("unavailable", "service_changed", "interrupted", "timed_out"))
                else -> error("发现状态无效")
            }
            McpDiscoveryView(started, deadline, status, result, reason)
        } catch (_: Exception) { throw CloudTaskFailure("invalid_response") }
    }
}
