package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskIntent
import com.healthyagent.running.agent.cloud.CloudTaskReceipt
import com.healthyagent.running.agent.cloud.CloudTaskResponse
import java.util.Collections
import kotlinx.serialization.json.*

/** 只保存本次任务选择的服务版本与本机代次，不含端点、凭据或模型自报许可。 */
class McpTaskConfiguration private constructor(private val value: JsonObject) {
    val services: List<JsonObject> get() = Collections.unmodifiableList(value.getValue("services").jsonArray.map { it.jsonObject })
    fun wire(): JsonObject = value
    override fun toString() = "McpTaskConfiguration(配置已隐藏)"

    companion object {
        fun parse(text: String): McpTaskConfiguration {
            val row = McpRpcResponse.boundedObject(text, 4096)
            require(row.keys == setOf("version", "services") && row["version"] == JsonPrimitive("native_mcp_v1"))
            val services = row.getValue("services").jsonArray
            require(services.size in 1..8)
            services.forEach { item ->
                val service = item.jsonObject
                require(service.keys == setOf("serviceId", "revision", "generation"))
                service.values.forEach { require(it is JsonPrimitive && it.isString && CloudTaskResponse.CLOUD_UUID.matches(it.content)) }
            }
            require(services.map { it.jsonObject.getValue("serviceId") }.distinct().size == services.size)
            return McpTaskConfiguration(row)
        }
    }
}

/** 来源必须是原生已保存任务、原回执、重新读取的云目录和本机私密配置；不能由下行交换包构造。 */
class McpTaskContext private constructor(
    internal val intent: CloudTaskIntent, internal val receipt: CloudTaskReceipt,
    private val services: Map<String, JsonObject>,
) {
    internal fun service(id: String) = services[id] ?: throw McpTransportFailure("invalid_response")
    internal fun scope(id: String): McpPhoneScope {
        val row = service(id)
        return McpPhoneScope(intent.identity, intent.clientRequestId, receipt.cloudTaskId, id,
            McpManagementWire.text(row, "revision"), McpManagementWire.text(row, "generation"),
            McpEndpoint.parse(McpManagementWire.text(row, "endpoint")))
    }
    override fun toString() = "McpTaskContext(任务与目录已隐藏)"

    internal fun matches(other: McpTaskContext): Boolean =
        intent == other.intent.copy(updatedAtMillis = intent.updatedAtMillis) && services == other.services &&
            receipt.cloudTaskId == other.receipt.cloudTaskId && receipt.acceptedAtMillis == other.receipt.acceptedAtMillis &&
            receipt.deadlineAtMillis == other.receipt.deadlineAtMillis

    companion object {
        internal fun capture(intent: CloudTaskIntent, receipt: CloudTaskReceipt,
            selected: List<Pair<JsonObject, McpPrivateStatus>>): McpTaskContext {
            require(intent.conversationId != null && intent.submissionAttempted && intent.pendingControl == null &&
                receipt.clientRequestId == intent.clientRequestId && receipt.status in setOf("queued", "running") &&
                receipt.confirmedControl == null && CloudTaskResponse.CLOUD_UUID.matches(receipt.cloudTaskId) &&
                receipt.acceptedAtMillis in 0..9007199254710991L &&
                receipt.deadlineAtMillis == receipt.acceptedAtMillis + 30000 && receipt.resultState == "none")
            val configuration = requireNotNull(intent.mcpTools())
            require(selected.size == configuration.services.size)
            val snapshots = configuration.services.mapIndexed { index, reference ->
                val (service, local) = selected[index]
                McpManagementWire.service(service)
                require(service["id"] == reference["serviceId"] && service["revision"] == reference["revision"] &&
                    service["enabled"] == JsonPrimitive(true) && local.configured &&
                    service["endpoint"] == JsonPrimitive(local.endpoint) && reference["generation"] == JsonPrimitive(local.generation))
                // JSON 重读切断调用方持有的可变 Map；原生执行前仍须复核实际配置及云授权。
                val copy = Json.parseToJsonElement(service.toString()).jsonObject
                McpManagementWire.text(service, "id") to JsonObject(copy + ("generation" to reference.getValue("generation")))
            }
            require(snapshots.sumOf { it.second.getValue("grants").jsonArray.size } <= 64)
            return McpTaskContext(intent, receipt, Collections.unmodifiableMap(snapshots.toMap()))
        }
    }
}
