package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskResponse
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** 正式聊天任务的固定云通道；与独立目录发现分离，不提供 URL 或凭据参数。 */
interface McpTaskApi {
    suspend fun taskConfirmation(context: McpTaskContext): McpTaskInvocation?
    suspend fun takeTaskExchange(context: McpTaskContext): McpTaskDelivery?
    suspend fun replyTaskExchange(context: McpTaskContext, delivery: McpTaskDelivery, reply: JsonObject)
    suspend fun decideTaskInvocation(context: McpTaskContext, invocation: McpTaskInvocation, decision: String)
    suspend fun closeTaskMcp(context: McpTaskContext)
}

/** 确认框使用实际工具和实际参数。摘要只作一致性校验，不能替代用户对写操作的确认。 */
class McpTaskInvocation private constructor(val scope: McpPhoneScope,
    val invocationId: String, val intentDigest: String, val modelName: String, val name: String,
    val mode: String, val argumentsJson: String, val expiresAtMillis: Long,
) {
    override fun toString() = "McpTaskInvocation(参数已隐藏)"

    internal fun matches(other: McpTaskInvocation): Boolean =
        scope.copy(endpoint = other.scope.endpoint) == other.scope && scope.endpoint.toString() == other.scope.endpoint.toString() &&
            invocationId == other.invocationId && intentDigest == other.intentDigest && modelName == other.modelName &&
            name == other.name && mode == other.mode && argumentsJson == other.argumentsJson && expiresAtMillis == other.expiresAtMillis

    internal fun validate(context: McpTaskContext) {
        val expected = context.scope(scope.serviceId)
        require(scope.copy(endpoint = expected.endpoint) == expected && scope.endpoint.toString() == expected.endpoint.toString() && expiresAtMillis > context.receipt.acceptedAtMillis &&
            expiresAtMillis <= context.receipt.deadlineAtMillis)
        val allowed = context.service(scope.serviceId).getValue("grants").jsonArray
        require(allowed.any { it.jsonObject == buildJsonObject { put("name", name); put("modelName", modelName); put("mode", mode) } })
    }

    /** 每次外网发送前核对本次意图，不能将握手通道变成任意工具调用通道。 */
    internal fun authorize(packet: McpPhoneRequest) {
        require(packet.matches(scope))
        if (packet.method == "DELETE") return
        val message = McpRpcResponse.boundedObject(requireNotNull(packet.body), 16384)
        if (message["method"] == JsonPrimitive("tools/call")) {
            val params = message.getValue("params").jsonObject
            require(params.keys == setOf("name", "arguments") && params["name"] == JsonPrimitive(name) &&
                params["arguments"] == McpRpcResponse.boundedObject(argumentsJson, 8192))
        }
    }

    internal fun decision(context: McpTaskContext, choice: String): JsonObject {
        validate(context)
        require(mode == "confirm" && choice in setOf("approve", "deny"))
        return buildJsonObject { put("version", "native_mcp_v1"); put("invocationId", invocationId)
            put("intentDigest", intentDigest); put("generation", scope.generation); put("decision", choice) }
    }

    companion object {
        internal fun parse(value: JsonElement, context: McpTaskContext): McpTaskInvocation {
            val row = value.jsonObject
            require(row.keys == setOf("version", "requestId", "taskId", "serviceId", "revision", "generation", "endpoint",
                "invocationId", "intentDigest", "argumentsDigest", "modelName", "name", "mode", "argumentsJson", "expiresAtMillis"))
            fun text(key: String) = McpManagementWire.text(row, key)
            fun id(key: String) = text(key).also { require(CloudTaskResponse.CLOUD_UUID.matches(it)) }
            val scope = context.scope(id("serviceId"))
            require(text("version") == "native_mcp_v1" && id("requestId") == scope.requestId && id("taskId") == scope.taskId &&
                id("revision") == scope.revision && id("generation") == scope.generation && text("endpoint") == scope.endpoint.toString())
            val args = text("argumentsJson")
            McpRpcResponse.boundedObject(args, 8192)
            val hash = MessageDigest.getInstance("SHA-256").digest(args.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            require(text("argumentsDigest") == hash && text("intentDigest").matches(Regex("[0-9a-f]{64}")))
            val expires = row.getValue("expiresAtMillis").jsonPrimitive.also { require(!it.isString) }.long
            return McpTaskInvocation(scope, id("invocationId"), text("intentDigest"), text("modelName"), text("name"),
                text("mode"), args, expires).also { it.validate(context) }
        }
    }
}

class McpTaskDelivery internal constructor(val invocation: McpTaskInvocation, val packet: McpPhoneRequest) {
    internal fun validate(context: McpTaskContext) { invocation.validate(context); invocation.authorize(packet) }
    override fun toString() = "McpTaskDelivery(传输正文已隐藏)"
}

internal object McpTaskWire {
    private fun <T> decode(run: () -> T): T = try { run() } catch (_: Exception) { throw CloudTaskFailure("invalid_response") }
    fun confirmation(text: String, context: McpTaskContext): McpTaskInvocation? = decode {
        val row = McpRpcResponse.boundedObject(text, 65536)
        require(row.keys == setOf("confirmation"))
        if (row.getValue("confirmation") == JsonNull) null else McpTaskInvocation.parse(row.getValue("confirmation"), context).also {
            require(it.mode == "confirm")
        }
    }
    fun delivery(text: String, context: McpTaskContext): McpTaskDelivery? = decode {
        val envelope = McpRpcResponse.boundedObject(text, 196608)
        require(envelope.keys == setOf("delivery"))
        if (envelope.getValue("delivery") == JsonNull) null else {
            val row = envelope.getValue("delivery").jsonObject
            require(row.keys == setOf("exchange", "intent"))
            McpTaskDelivery(McpTaskInvocation.parse(row.getValue("intent"), context),
                McpPhoneRequest.parse(row.getValue("exchange").toString())).also { it.validate(context) }
        }
    }
    fun accepted(text: String) = decode {
        val row = McpRpcResponse.boundedObject(text, 4096)
        require(row.keys == setOf("accepted") && row["accepted"] == JsonPrimitive(true))
    }
    fun decision(text: String, invocation: McpTaskInvocation, choice: String) = decode {
        val row = McpRpcResponse.boundedObject(text, 4096)
        require(row.keys == setOf("decision"))
        val decision = row.getValue("decision").jsonObject
        require(decision.keys == setOf("invocationId", "status") && decision["invocationId"] == JsonPrimitive(invocation.invocationId) &&
            decision["status"] == JsonPrimitive(if (choice == "approve") "approved" else "denied"))
    }
}
