package com.healthyagent.running.agent.cloud

import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.*

/** 只允许固定库与显式剧透许可，不接收网页提供的资料正文或 URL。 */
object CloudKnowledge {
    fun selection(value: JsonElement): JsonObject {
        val row = value as? JsonObject ?: error("知识库选择无效")
        require(row.keys == setOf("version", "library", "allowSpoilers"))
        require(row["version"] == JsonPrimitive("curated_knowledge_v1"))
        val library = row["library"]
        require(library == JsonPrimitive("health") || library == JsonPrimitive("firefly") || library == JsonPrimitive("both"))
        val spoiler = row["allowSpoilers"]
        require(spoiler == JsonPrimitive(true) || spoiler == JsonPrimitive(false))
        require(library != JsonPrimitive("health") || spoiler == JsonPrimitive(false))
        return row
    }

    fun sources(value: JsonElement): JsonArray {
        val rows = value as? JsonArray ?: error("知识来源无效")
        require(rows.size <= 3)
        val ids = mutableSetOf<String>()
        rows.forEach { item ->
            val row = item as? JsonObject ?: error("知识来源无效")
            require(row.keys == setOf("citationId", "title", "url", "publishedAt", "checkedAtMillis"))
            val id = CloudAgentCommands.text(row, "citationId")
            require(Regex("[a-zA-Z0-9_-]{1,100}").matches(id) && ids.add(id))
            val title = CloudAgentCommands.text(row, "title")
            require(title.length in 1..80 && title.isNotBlank() && title.none { it.code < 32 || it.code == 127 })
            val rawUrl = CloudAgentCommands.text(row, "url")
            require(rawUrl.length <= 300)
            val uri = URI(rawUrl)
            require(uri.scheme == "https" && uri.host in setOf("www.who.int", "hsr.hoyoverse.com") &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.port == -1)
            val checked = row["checkedAtMillis"] as? JsonPrimitive ?: error("知识来源无效")
            require(!checked.isString && checked.longOrNull != null && checked.long in 0..8640000000000000L)
            if (row["publishedAt"] != JsonNull) {
                val date = CloudAgentCommands.text(row, "publishedAt")
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date))
                require(LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() <= checked.long)
            }
        }
        return rows
    }
}
