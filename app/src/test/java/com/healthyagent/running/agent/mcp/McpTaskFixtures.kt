package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** 纯合成任务；未读取设备配置或真实插件。 */
internal object McpTaskFixtures {
    fun id(seed: Int) = "%08x-1111-4111-8111-%012x".format(seed, seed.toLong())
    val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    val endpoint = "https://tools.example.com/mcp"
    val configuration = buildJsonObject { put("version", "native_mcp_v1"); put("services", JsonArray(listOf(buildJsonObject {
        put("serviceId", id(1)); put("revision", id(2)); put("generation", id(3))
    }))) }
    val intent = CloudTaskIntent.confirm(identity, "查找今晚的安排", true, 1000).copy(
        conversationId = id(4), conversationJson = """{"version":"conversation_v1","id":"${id(4)}","approved":true,"messages":[]}""",
        mcpToolsJson = configuration.toString(), submissionAttempted = true,
    )
    val receipt = CloudTaskReceipt(intent.clientRequestId, id(5), "running", null, 1000, 31000, 1000, "none", null, false, null)
    val local = McpPrivateStatus(true, true, endpoint, id(3), "a".repeat(64))
    val modelName = "mcp_${id(1).replace("-", "")}_0"
    fun service(mode: String = "confirm") = buildJsonObject {
        put("id", id(1)); put("revision", id(2)); put("endpoint", endpoint); put("enabled", true)
        put("catalog", buildJsonObject { put("fingerprint", "b".repeat(64)); put("tools", JsonArray(listOf(buildJsonObject {
            put("name", "notes.search"); put("definition", buildJsonObject { put("name", "notes.search")
                put("inputSchema", buildJsonObject { put("type", "object") }) })
        }))) })
        put("grants", JsonArray(listOf(buildJsonObject { put("name", "notes.search"); put("modelName", modelName); put("mode", mode) })))
    }
    fun context(target: CloudTaskIntent = intent, mode: String = "confirm") =
        McpTaskContext.capture(target, receipt, listOf(service(mode) to local))
    fun invocation(context: McpTaskContext = context(), args: String = """{"query":"今晚","pace":6.5}""") = buildJsonObject {
        val scope = context.scope(id(1))
        put("version", "native_mcp_v1"); put("requestId", scope.requestId); put("taskId", scope.taskId)
        put("serviceId", scope.serviceId); put("revision", scope.revision); put("generation", scope.generation); put("endpoint", endpoint)
        put("invocationId", id(6)); put("intentDigest", "c".repeat(64)); put("modelName", modelName); put("name", "notes.search")
        put("mode", McpManagementWire.text(context.service(id(1)).getValue("grants").jsonArray.single().jsonObject, "mode"))
        put("argumentsJson", args); put("argumentsDigest", MessageDigest.getInstance("SHA-256")
            .digest(args.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }); put("expiresAtMillis", 31000)
    }
    fun packet(context: McpTaskContext = context(), args: String = """{"query":"今晚","pace":6.5}""", method: String = "tools/call") = buildJsonObject {
        val scope = context.scope(id(1))
        put("version", "native_mcp_v1"); put("requestId", scope.requestId); put("taskId", scope.taskId)
        put("serviceId", scope.serviceId); put("revision", scope.revision); put("generation", scope.generation); put("endpoint", endpoint)
        put("exchangeId", id(7)); put("method", "POST"); put("sessionId", JsonNull); put("protocolVersion", "2025-11-25")
        put("body", buildJsonObject { put("jsonrpc", "2.0"); if (!method.startsWith("notifications/")) put("id", 1)
            put("method", method); put("params", if (method == "tools/call") buildJsonObject {
                put("name", "notes.search"); put("arguments", Json.parseToJsonElement(args))
            } else buildJsonObject {}) }.toString())
    }
    fun delivery(context: McpTaskContext = context(), value: JsonObject = invocation(context), exchange: JsonObject = packet(context)) = buildJsonObject {
        put("delivery", buildJsonObject { put("intent", value); put("exchange", exchange) })
    }.toString()
}
