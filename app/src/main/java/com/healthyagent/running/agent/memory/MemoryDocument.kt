package com.healthyagent.running.agent.memory

import androidx.room.Embedded
import java.time.Instant
import kotlinx.serialization.Serializable

/** 文档只是原生有效记录的只读投影，不构成第二份可回写的记忆库。 */
@Serializable
data class MemoryDocumentEntry(
    @Embedded val memory: MemoryRecord,
    val changeSource: String?,
    val sourceTurnId: String?,
    val category: String?,
) {
    override fun toString() = "MemoryDocumentEntry(内容已隐藏)"
}

data class MemoryDocument(val generatedAtMillis: Long, val entries: List<MemoryDocumentEntry>) {
    fun page(offset: Int, limit: Int): MemoryDocumentPage {
        require(offset >= 0 && limit in 1..20)
        return MemoryDocumentPage(generatedAtMillis, offset, limit, entries.size, entries.drop(offset).take(limit))
    }

    /** 写入调用方提供的目标；此层不决定文件路径，也不上传或调用模型。 */
    fun writeMarkdown(target: Appendable) {
        target.append("# 流萤长期记忆\n\n")
        target.append("生成时间：${Instant.ofEpochMilli(generatedAtMillis)}\n\n")
        target.append("共 ${entries.size} 条当前有效记忆；不包含历史版本或已删除内容。\n\n")
        target.append("这是生成时的只读副本，不会随应用内编辑或删除自动更新，也不会自动导入应用。请妥善保存。\n\n")
        if (entries.isEmpty()) target.append("暂无有效记忆。\n")
        entries.forEachIndexed { index, entry ->
            val memory = entry.memory
            val category = when (entry.category) {
                "preference" -> "偏好"
                "goal" -> "目标"
                "fact" -> "事实"
                else -> "未分类"
            }
            val source = when (entry.changeSource) {
                "manual" -> "手动保存或编辑"
                "agent" -> "Agent 经授权写入"
                "restored" -> "用户恢复历史版本"
                else -> "旧记录（来源未记录）"
            }
            target.append("## ${index + 1}\n\n")
            target.append("- 记忆标识：`${memory.id}`\n")
            target.append("- 分类：$category\n")
            target.append("- 当前版本：${memory.version}\n")
            target.append("- 有效状态：生成时有效\n")
            target.append("- 当前版本来源：$source\n")
            target.append("- 来源消息：${entry.sourceTurnId?.let { "`$it`" } ?: "未记录或不适用"}\n")
            target.append("- 创建时间：${Instant.ofEpochMilli(memory.createdAtMillis)}\n")
            target.append("- 更新时间：${Instant.ofEpochMilli(memory.updatedAtMillis)}\n\n")
            // 使用比正文更长的围栏，避免正文中的 Markdown 或 HTML 改写文档结构。
            val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(memory.content).maxOfOrNull { it.value.length } ?: 0) + 1))
            target.append("${fence}text\n")
            target.append(memory.content).append('\n')
            target.append("$fence\n\n")
        }
    }

    override fun toString() = "MemoryDocument(条目数=${entries.size}，内容已隐藏)"
}

@Serializable
data class MemoryDocumentPage(
    val generatedAtMillis: Long,
    val offset: Int,
    val limit: Int,
    val totalItems: Int,
    val items: List<MemoryDocumentEntry>,
) {
    override fun toString() = "MemoryDocumentPage(条目数=${items.size}，内容已隐藏)"
}
