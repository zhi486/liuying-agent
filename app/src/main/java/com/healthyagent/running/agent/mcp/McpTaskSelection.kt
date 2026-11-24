package com.healthyagent.running.agent.mcp

import kotlinx.serialization.json.*

/** 原生读取的本次授权快照；网页只能选择服务 ID，不能提供版本、代次或工具许可。 */
internal class McpTaskSelection private constructor(
    val configuration: McpTaskConfiguration, private val services: List<JsonObject>,
) {
    fun matches(other: McpTaskSelection) = configuration.wire() == other.configuration.wire() && services == other.services
    fun preview() = JsonArray(services.map { service -> buildJsonObject {
        put("id", service.getValue("id")); put("endpoint", service.getValue("endpoint"))
        put("tools", JsonArray(service.getValue("grants").jsonArray.map { item -> buildJsonObject {
            put("name", item.jsonObject.getValue("name")); put("mode", item.jsonObject.getValue("mode"))
        } }))
    } })
    override fun toString() = "McpTaskSelection(本次授权已隐藏)"

    companion object {
        fun capture(ids: List<String>, selected: List<Pair<JsonObject, McpPrivateStatus>>): McpTaskSelection {
            require(ids.size in 1..8 && ids.distinct().size == ids.size && selected.size == ids.size)
            val snapshots = selected.mapIndexed { index, (service, local) ->
                McpManagementWire.id(ids[index]); McpManagementWire.service(service)
                require(service["id"] == JsonPrimitive(ids[index]) && service["enabled"] == JsonPrimitive(true) &&
                    local.configured && service["endpoint"] == JsonPrimitive(local.endpoint) && local.generation != null)
                Json.parseToJsonElement(service.toString()).jsonObject
            }
            require(snapshots.sumOf { it.getValue("grants").jsonArray.size } <= 64)
            val configuration = McpTaskConfiguration.parse(buildJsonObject {
                put("version", "native_mcp_v1")
                put("services", JsonArray(snapshots.mapIndexed { index, service -> buildJsonObject {
                    put("serviceId", service.getValue("id")); put("revision", service.getValue("revision"))
                    put("generation", requireNotNull(selected[index].second.generation))
                } }))
            }.toString())
            return McpTaskSelection(configuration, snapshots)
        }
    }
}
