package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.CloudTaskResponse
import kotlinx.serialization.json.*

/** 仅管理已认证的服务元数据，不传插件凭据，不触发工具执行。 */
interface McpManagementApi {
    suspend fun listServices(identity: CloudTaskIdentity, after: String?): JsonObject
    suspend fun getService(identity: CloudTaskIdentity, id: String, summaryOnly: Boolean = false): JsonObject
    suspend fun changeService(identity: CloudTaskIdentity, id: String?, action: String, body: JsonObject): JsonObject
}

internal object McpManagementWire {
    fun id(value: String) { require(CloudTaskResponse.CLOUD_UUID.matches(value)) }
    fun text(row: JsonObject, name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("插件字段无效")
    private fun flag(row: JsonObject, name: String) { require(row[name] in listOf(JsonPrimitive(true), JsonPrimitive(false))) }
    fun summary(row: JsonObject) {
        require(row.keys == setOf("id", "endpoint", "revision", "enabled"))
        id(text(row, "id")); id(text(row, "revision")); McpEndpoint.parse(text(row, "endpoint")); flag(row, "enabled")
    }
    fun service(row: JsonObject) {
        require(row.keys == setOf("id", "endpoint", "revision", "enabled", "catalog", "grants"))
        summary(JsonObject(row.filterKeys { it !in setOf("catalog", "grants") }))
        val names = mutableSetOf<String>()
        if (row["catalog"] != JsonNull) {
            val catalog = row.getValue("catalog").jsonObject
            require(catalog.keys == setOf("fingerprint", "tools") && text(catalog, "fingerprint").matches(Regex("[0-9a-f]{64}")))
            val tools = catalog.getValue("tools").jsonArray
            require(tools.size <= 64)
            tools.forEach { item ->
                val tool = item.jsonObject
                require(tool.keys == setOf("name", "definition"))
                val name = text(tool, "name")
                require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && names.add(name))
                val definition = tool.getValue("definition").jsonObject
                require(text(definition, "name") == name && definition["inputSchema"]?.jsonObject?.get("type") == JsonPrimitive("object"))
                definition["description"]?.let { require(it is JsonPrimitive && it.isString) }
            }
        }
        val grants = row.getValue("grants").jsonArray
        require(grants.size <= 64 && (row["enabled"] == JsonPrimitive(true) || grants.isEmpty()))
        if (row["enabled"] == JsonPrimitive(true)) require(grants.isNotEmpty() && row["catalog"] != JsonNull)
        val selected = mutableSetOf<String>()
        grants.forEach { item ->
            val grant = item.jsonObject
            require(grant.keys == setOf("name", "mode", "modelName") && text(grant, "name") in names && selected.add(text(grant, "name")))
            val index = names.indexOf(text(grant, "name"))
            require(text(grant, "mode") in setOf("read", "confirm") &&
                text(grant, "modelName") == "mcp_${text(row, "id").replace("-", "")}_${index.toString(36)}")
        }
    }
    fun parse(text: String, list: Boolean, summaryOnly: Boolean = false): JsonObject = try {
        require(!list || !summaryOnly)
        val row = McpRpcResponse.boundedObject(text, 262144)
        if (list) {
            require(row.keys == setOf("services", "nextCursor", "discoveryAvailable") +
                if ("phoneDiscoveryAvailable" in row) setOf("phoneDiscoveryAvailable") else emptySet())
            flag(row, "discoveryAvailable"); if ("phoneDiscoveryAvailable" in row) flag(row, "phoneDiscoveryAvailable")
            val services = row.getValue("services").jsonArray
            require(services.size <= 20)
            services.forEach { summary(it.jsonObject) }
            val ids = services.map { text(it.jsonObject, "id") }
            require(ids.distinct().size == ids.size && ids == ids.sorted())
            if (row["nextCursor"] != JsonNull) require(text(row, "nextCursor").also(::id) == ids.lastOrNull())
        } else {
            require(row.keys == setOf("service"))
            if (summaryOnly) summary(row.getValue("service").jsonObject) else service(row.getValue("service").jsonObject)
        }
        row
    } catch (_: Exception) { throw CloudTaskFailure("invalid_response") }
}
