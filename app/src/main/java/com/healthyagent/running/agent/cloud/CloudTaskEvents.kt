package com.healthyagent.running.agent.cloud

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** 流中正文只是临时草稿；不能写入任务回执或下一轮上下文。 */
data class CloudTaskEvent(val taskId: String, val sequence: Int, val round: Int, val kind: String,
    val text: String? = null, val outcome: String? = null, val status: String? = null) {
    override fun toString() = "CloudTaskEvent(已脱敏)"
}

class CloudTaskStream(val taskId: String) {
    init { require(CloudTaskResponse.CLOUD_UUID.matches(taskId)) }
    var cursor = 0; private set
    private var round = 0
    private var phase = "idle"
    private var text = ""
    var ended = false; private set
    @Synchronized fun draft(): String? = text.takeIf { phase in setOf("open", "answer") && it.isNotEmpty() }

    @Synchronized fun accept(event: CloudTaskEvent) {
        require(!ended && event.taskId == taskId && event.sequence == cursor + 1 && event.sequence <= 12016)
        when (event.kind) {
            "round_start" -> {
                require(event.round == round + 1 && event.round in 1..3 && phase in setOf("idle", "tools", "discarded"))
                round = event.round; phase = "open"; text = ""
            }
            "text_delta" -> {
                require(event.round == round && phase == "open")
                val part = requireNotNull(event.text); require(part.isNotEmpty() && text.length + part.length <= 4000)
                text += part
            }
            "round_end" -> {
                require(event.round == round && phase == "open" && event.outcome in setOf("answer", "tools", "discarded"))
                phase = requireNotNull(event.outcome)
                if (phase != "answer") text = ""
            }
            "task_end" -> {
                require(event.round == round && event.status in setOf("completed", "blocked", "failed", "cancelled", "timed_out"))
                // 完整回答先由流显示，待正式回执落盘后再替换；失败和取消不能保留临时正文。
                if (event.status != "completed" || phase != "answer") text = ""
                ended = true
            }
            else -> error("未知流事件")
        }
        cursor = event.sequence
    }
}

/** 按实际读取施加背压；限制总字节、单行和事件数，拒绝坏UTF-8及半截终态。 */
object CloudTaskEvents {
    suspend fun read(input: InputStream, taskId: String, after: Int, consume: suspend (CloudTaskEvent) -> Unit) {
        require(CloudTaskResponse.CLOUD_UUID.matches(taskId) && after in 0..12016)
        val bounded = object : FilterInputStream(input) {
            private var count = 0
            private fun add(n: Int) { if (n > 0) { count += n; require(count <= 4 * 1024 * 1024) } }
            override fun read(): Int = super.read().also { if (it >= 0) add(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also(::add)
        }
        val reader = InputStreamReader(bounded, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
        val line = StringBuilder()
        val fields = mutableMapOf<String, String>()
        var cursor = after; var terminal = false; var first = true; var skipLf = false
        suspend fun dispatch() {
            if (fields.isEmpty()) return
            if (fields["event"] == "stream_error") {
                require(fields.keys == setOf("event", "data") &&
                    CloudTaskResponse.json.parseToJsonElement(fields.getValue("data")) == buildJsonObject { put("error", "unavailable") })
                throw CloudTaskFailure("unavailable")
            }
            require(!terminal && fields.keys == setOf("event", "id", "data"))
            val id = fields.getValue("id")
            require(Regex("[1-9][0-9]{0,4}").matches(id) && id.toInt() == cursor + 1 && id.toInt() <= 12016)
            val value = CloudTaskResponse.json.parseToJsonElement(fields.getValue("data")).jsonObject
            fun str(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("事件字段无效")
            fun int(key: String) = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("事件字段无效")
            val kind = str("kind")
            val extras = when (kind) { "round_start" -> emptySet(); "text_delta" -> setOf("text")
                "round_end" -> setOf("outcome"); "task_end" -> setOf("status"); else -> error("未知事件") }
            require(value.keys == setOf("version", "taskId", "sequence", "round", "kind") + extras &&
                str("version") == "task_events_v1" && str("taskId") == taskId && int("sequence") == id.toInt() &&
                int("round") in 0..3 && fields["event"] == kind)
            val event = CloudTaskEvent(taskId, int("sequence"), int("round"), kind,
                if (kind == "text_delta") str("text") else null,
                if (kind == "round_end") str("outcome") else null,
                if (kind == "task_end") str("status") else null)
            consume(event)
            cursor = event.sequence; terminal = kind == "task_end"; fields.clear()
        }
        suspend fun endLine() {
            val value = line.toString(); line.setLength(0)
            if (value.isEmpty()) dispatch()
            else if (!value.startsWith(':')) {
                val colon = value.indexOf(':'); require(colon > 0)
                val key = value.substring(0, colon)
                require(key in setOf("id", "event", "data") && key !in fields)
                fields[key] = value.substring(colon + 1).removePrefix(" ")
            }
        }
        val chars = CharArray(1024)
        while (!terminal) {
            val count = reader.read(chars); if (count < 0) break
            for (i in 0 until count) {
                val char = chars[i]
                if (first) { first = false; if (char == '\uFEFF') continue }
                if (skipLf) { skipLf = false; if (char == '\n') continue }
                when (char) {
                    '\r' -> { endLine(); skipLf = true }
                    '\n' -> endLine()
                    else -> { line.append(char); require(line.length <= 32768) }
                }
                if (terminal) return
            }
        }
        if (!terminal || line.isNotEmpty() || fields.isNotEmpty()) throw CloudTaskFailure("invalid_response")
    }
}
