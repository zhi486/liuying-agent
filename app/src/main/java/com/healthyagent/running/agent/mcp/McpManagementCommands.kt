package com.healthyagent.running.agent.mcp

import kotlinx.serialization.json.*

/** Bridge 不接收插件密钥、身份正文、目录或自报批准快照。 */
object McpManagementCommands {
    fun validate(name: String, body: JsonObject) {
        if (name == "mcp.status") { require(body.isEmpty()); return }
        val text = McpManagementWire::text
        require(text(body, "identityToken").matches(Regex("[0-9a-f]{64}")))
        val keys = setOf("identityToken")
        when (name) {
            "mcp.list" -> { require(body.keys == keys + "after"); if (body["after"] != JsonNull) McpManagementWire.id(text(body, "after")) }
            "mcp.register" -> { require(body.keys == keys + "endpoint"); McpEndpoint.parse(text(body, "endpoint")) }
            "mcp.detail" -> { require(body.keys == keys + "id"); McpManagementWire.id(text(body, "id")) }
            "mcp.recover" -> { require(body.keys == keys + "requestId"); McpManagementWire.id(text(body, "requestId")) }
            "mcp.endpoint", "mcp.remove" -> {
                require(body.keys == keys + setOf("id", "revision", "context", "confirmed") +
                    if (name == "mcp.endpoint") setOf("endpoint") else emptySet())
                McpManagementWire.id(text(body, "id")); McpManagementWire.id(text(body, "revision"))
                require(text(body, "context").matches(Regex("[0-9a-f]{64}")) && body["confirmed"] == JsonPrimitive(true))
                if (name == "mcp.endpoint") McpEndpoint.parse(text(body, "endpoint"))
            }
            "mcp.configure", "mcp.disable", "mcp.discover", "mcp.approve" -> {
                val extra = when (name) { "mcp.discover" -> setOf("confirmed"); "mcp.approve" -> setOf("confirmed", "selection"); else -> emptySet() }
                require(body.keys == keys + setOf("id", "revision") + extra)
                McpManagementWire.id(text(body, "id")); McpManagementWire.id(text(body, "revision"))
                if (extra.isNotEmpty()) require(body["confirmed"] == JsonPrimitive(true))
                if (name == "mcp.approve") {
                    val selection = body.getValue("selection").jsonArray
                    require(selection.size in 1..64)
                    val names = mutableSetOf<String>()
                    selection.forEach { item ->
                        val row = item.jsonObject
                        require(row.keys == setOf("name", "mode"))
                        require(text(row, "name").matches(Regex("[A-Za-z0-9_.-]{1,128}")) && names.add(text(row, "name")))
                        require(text(row, "mode") in setOf("read", "confirm"))
                    }
                }
            }
            "mcp.revokePrivate" -> {
                require(body.keys == keys + setOf("id", "context")); McpManagementWire.id(text(body, "id"))
                require(text(body, "context").matches(Regex("[0-9a-f]{64}")))
            }
            else -> throw IllegalArgumentException("未知插件命令")
        }
    }
}
