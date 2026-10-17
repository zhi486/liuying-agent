package com.healthyagent.running.agent.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

sealed interface MemoryCommand {
    data class Create(val memoryId: String, val content: String) : MemoryCommand
    data class ListMemories(val offset: Int, val limit: Int) : MemoryCommand
    data class Update(val memoryId: String, val version: Int, val content: String) : MemoryCommand
    data class Delete(val memoryId: String, val version: Int) : MemoryCommand
    data class Preview(val memoryRefs: List<MemoryRef>) : MemoryCommand
}

data class MemoryRef(val id: String, val version: Int)

/** 所有者、来源和时间由原生决定；此接口不接受云端授权。 */
object MemoryCommands {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    // 与网页 String.trim 使用同一空白集合，避免落库成功后正文比对不一致。
    private val jsWhitespace = "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
    fun id(value: String) = value.also { require(uuid.matches(it)) }
    fun content(value: String): String {
        require(value.length in 1..500)
        val text = value.trim { it in jsWhitespace }
        require(text.isNotEmpty())
        value.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) require(index + 1 < value.length && value[index + 1].isLowSurrogate())
            if (char.isLowSurrogate()) require(index > 0 && value[index - 1].isHighSurrogate())
        }
        return text
    }

    fun decode(name: String, body: JsonObject): MemoryCommand = when (name) {
        "memory.preview" -> {
            require(body.keys == setOf("memoryRefs"))
            val refs = body["memoryRefs"] as? JsonArray
            require(refs != null && refs.size in 1..5)
            val selected = refs.map { value ->
                val ref = value as? JsonObject
                require(ref != null && ref.keys == setOf("id", "version"))
                MemoryRef(id(ref.string("id")), ref.integer("version").also { require(it > 0) })
            }
            require(selected.map { it.id }.distinct().size == selected.size)
            MemoryCommand.Preview(selected)
        }
        "memory.create" -> {
            require(body.keys == setOf("memoryId", "content"))
            MemoryCommand.Create(id(body.string("memoryId")), content(body.string("content")))
        }
        "memory.list" -> {
            require(body.keys == setOf("offset", "limit"))
            val offset = body.integer("offset")
            val limit = body.integer("limit")
            require(offset >= 0 && limit in 1..50)
            MemoryCommand.ListMemories(offset, limit)
        }
        "memory.update" -> {
            require(body.keys == setOf("memoryId", "version", "content"))
            MemoryCommand.Update(id(body.string("memoryId")), body.version(), content(body.string("content")))
        }
        "memory.delete" -> {
            require(body.keys == setOf("memoryId", "version", "confirmed"))
            val confirmed = body["confirmed"] as? JsonPrimitive
            require(confirmed != null && !confirmed.isString && confirmed.booleanOrNull == true)
            MemoryCommand.Delete(id(body.string("memoryId")), body.version())
        }
        else -> throw IllegalArgumentException("未知记忆命令")
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key) as? JsonPrimitive
        require(value != null && value.isString)
        return value.content
    }
    private fun JsonObject.integer(key: String): Int {
        val value = get(key) as? JsonPrimitive
        require(value != null && !value.isString)
        return requireNotNull(value.intOrNull)
    }
    private fun JsonObject.version() = integer("version").also { require(it in 1 until Int.MAX_VALUE) }
}
