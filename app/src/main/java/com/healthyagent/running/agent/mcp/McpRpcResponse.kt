package com.healthyagent.running.agent.mcp

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** 手机只解析传输信封，MCP 生命周期、目录和 schema 仍交由既有可信 SDK 通路处理。 */
internal object McpRpcResponse {
    fun requestId(text: String): JsonPrimitive? = checked {
        val message = message(text, 16384)
        require(message.keys.all { it in setOf("jsonrpc", "id", "method", "params") })
        val method = message.getValue("method").jsonPrimitive.also { require(it.isString) }.content
        val notification = method in setOf("notifications/initialized", "notifications/cancelled")
        require(notification || method in setOf("initialize", "tools/list", "tools/call"))
        require(("id" !in message) == notification)
        require("params" !in message || message["params"] is JsonObject)
        if (notification) null else message.getValue("id").jsonPrimitive.also {
            require(it != JsonNull && if (it.isString) it.content.length in 1..128 else
                it.longOrNull?.let { number -> number in -9007199254740991..9007199254740991 } == true)
        }
    }

    /** true 为匹配终止响应，false 只用于丢弃不进入模型的进度／日志通知。 */
    fun validateResponse(text: String, id: JsonPrimitive): Boolean = checked {
        val message = message(text, 131072)
        if ("method" in message) {
            if ("id" in message) throw McpTransportFailure("unsupported_server_request")
            require(message.keys.all { it in setOf("jsonrpc", "method", "params") })
            require("params" !in message || message["params"] is JsonObject)
            val method = message.getValue("method").jsonPrimitive.also { require(it.isString) }.content
            if (method == "notifications/tools/list_changed") throw McpTransportFailure("catalog_changed")
            require(method in setOf("notifications/progress", "notifications/message"))
            return@checked false
        }
        require(message["id"] == id && (("result" in message) != ("error" in message)))
        require(message.keys == setOf("jsonrpc", "id", if ("result" in message) "result" else "error"))
        if ("error" in message) {
            val error = message.getValue("error").jsonObject
            require(error.getValue("code").jsonPrimitive.let { !it.isString && it.intOrNull != null })
            require(error.getValue("message").jsonPrimitive.isString)
        }
        true
    }

    /** 匹配请求 ID 即关闭流，不等远端 EOF；不跟随 retry／id／endpoint，也不恢复或补发。 */
    fun readSse(stream: InputStream, id: JsonPrimitive): String = checked {
        val limited = object : FilterInputStream(stream) {
            var size = 0
            private fun count(amount: Int) { if (amount > 0) size += amount; if (size > 131072) throw McpTransportFailure("limit_exceeded") }
            override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                `in`.read(bytes, offset, minOf(length, 131073 - size)).also(::count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(limited, decoder).use { reader ->
            val line = StringBuilder(); val data = StringBuilder()
            var event = ""; var skipLf = false; var first = true
            var response: String? = null
            while (response == null) {
                val next = reader.read()
                if (next < 0) throw McpTransportFailure("invalid_response")
                val char = next.toChar()
                if (first) { first = false; if (char == '\uFEFF') continue }
                if (skipLf && char == '\n') { skipLf = false; continue }
                skipLf = char == '\r'
                if (char != '\r' && char != '\n') { line.append(char); continue }
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        require(event.isEmpty() || event == "message")
                        val text = data.toString().dropLast(1)
                        if (text.isNotEmpty() && validateResponse(text, id)) response = text
                    }
                    data.setLength(0); event = ""
                } else {
                    val text = line.toString(); val colon = text.indexOf(':')
                    val field = if (colon < 0) text else text.substring(0, colon)
                    val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
                    if (field == "data") data.append(value).append('\n')
                    if (field == "event") event = value
                }
                line.setLength(0)
            }
            response
        }
    }

    private fun message(text: String, limit: Int): JsonObject {
        return boundedObject(text, limit).also { require(it["jsonrpc"] == JsonPrimitive("2.0")) }
    }

    internal fun boundedObject(text: String, limit: Int): JsonObject {
        if (text.toByteArray(Charsets.UTF_8).size > limit) throw McpTransportFailure("limit_exceeded")
        // 在 JSON 库递归之前限制嵌套，字符串中的括号不计数，避免远端深嵌套导致栈溢出。
        var depth = 0; var quoted = false; var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun <T> checked(run: () -> T): T = try { run() }
        catch (error: Exception) { throw if (error is McpTransportFailure) error else McpTransportFailure("invalid_response") }
}
