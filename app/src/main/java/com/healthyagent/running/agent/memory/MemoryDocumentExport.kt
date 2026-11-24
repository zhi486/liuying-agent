package com.healthyagent.running.agent.memory

import androidx.annotation.MainThread
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import java.io.Writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

object MemoryExportCommands {
    fun decode(name: String, body: JsonObject): String {
        require(name in setOf("memory.exportBegin", "memory.exportStatus", "memory.exportCancel"))
        require(body.keys == if (name == "memory.exportBegin") setOf("exportId", "confirmed") else setOf("exportId"))
        if (name == "memory.exportBegin") require(body["confirmed"] == JsonPrimitive(true))
        val value = body["exportId"] as? JsonPrimitive
        require(value != null && value.isString)
        return MemoryCommands.id(value.content)
    }
}

@Serializable
data class MemoryExportStatus(
    val exportId: String,
    val phase: String,
    val totalItems: Int? = null,
    val generatedAtMillis: Long? = null,
)

/** 由宿主主线程串行调用；等待文件选择器不占用 Bridge 请求，也不读取正文。 */
@MainThread
class MemoryDocumentExport(
    private val scope: CoroutineScope,
    private val choose: () -> Unit,
    private val snapshot: suspend () -> MemoryDocument,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var current: MemoryExportStatus? = null
    private var chooserId: String? = null
    private var writerJob: Job? = null

    fun handle(name: String, body: JsonObject): CommandOutcome {
        val id = try { MemoryExportCommands.decode(name, body) } catch (_: IllegalArgumentException) {
            return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "文档保存参数无效")
        }
        if (name == "memory.exportBegin" && current?.exportId != id) {
            if (chooserId != null || writerJob?.isCompleted == false) {
                return CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "请先完成或取消当前文件保存")
            }
            current = MemoryExportStatus(id, "choosing")
            chooserId = id
            try { choose() } catch (_: Exception) {
                chooserId = null
                current = MemoryExportStatus(id, "failed")
            }
        }
        val state = current?.takeIf { it.exportId == id }
            ?: return CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "保存状态已失效，请重新选择保存位置")
        if (name == "memory.exportCancel") {
            current = when (state.phase) {
                "choosing" -> MemoryExportStatus(id, "cancelled")
                "writing" -> MemoryExportStatus(id, "interrupted").also { writerJob?.cancel() }
                else -> state
            }
        }
        return CommandOutcome.AcceptedWithData(BridgeCodec.json.encodeToJsonElement(checkNotNull(current)).jsonObject)
    }

    /** 目标仅由原生系统选择器提供；网页不能提交 URI、路径或待写正文。 */
    fun selected(open: (() -> Writer)?) {
        val id = chooserId ?: return
        chooserId = null
        if (current?.exportId != id || current?.phase != "choosing") return
        if (open == null) {
            current = MemoryExportStatus(id, "cancelled")
            return
        }
        current = MemoryExportStatus(id, "writing")
        writerJob = scope.launch {
            try {
                val document = withContext(io) {
                    val document = snapshot()
                    ensureActive()
                    open().use { writer ->
                        val context = coroutineContext
                        document.writeMarkdown(object : Appendable {
                            override fun append(value: CharSequence?): Appendable = apply { context.ensureActive(); writer.append(value) }
                            override fun append(value: CharSequence?, start: Int, end: Int): Appendable = apply { context.ensureActive(); writer.append(value, start, end) }
                            override fun append(value: Char): Appendable = apply { context.ensureActive(); writer.append(value) }
                        })
                    }
                    ensureActive()
                    document
                }
                if (current?.exportId == id && current?.phase == "writing") {
                    current = MemoryExportStatus(id, "saved", document.entries.size, document.generatedAtMillis)
                }
            } catch (cancelled: CancellationException) {
                if (current?.exportId == id && current?.phase == "writing") current = MemoryExportStatus(id, "interrupted")
                throw cancelled
            } catch (_: Exception) {
                if (current?.exportId == id && current?.phase == "writing") current = MemoryExportStatus(id, "failed")
            }
        }
    }

    /** 文档重载或宿主销毁后不消费迟到选择结果，也不恢复旧写入。 */
    fun reset() {
        current = null
        writerJob?.cancel()
    }
}
