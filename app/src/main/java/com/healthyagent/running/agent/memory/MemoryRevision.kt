package com.healthyagent.running.agent.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.serialization.Serializable

/** 历史只保存在原生库；删除记忆时一并清除正文，不能通过恢复绕过删除。 */
@Entity(tableName = "agent_memory_revisions", primaryKeys = ["ownerId", "memoryId", "version"],
    foreignKeys = [ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["memoryId"])])
data class MemoryRevision(
    val ownerId: String, val memoryId: String, val version: Int, val content: String,
    val changedAtMillis: Long, val changeSource: String, val turnId: String?,
) {
    fun record() = MemoryRevisionRecord(version, content, changedAtMillis, changeSource, turnId)
    override fun toString() = "MemoryRevision(内容已隐藏)"
}

@Serializable
data class MemoryRevisionRecord(val version: Int, val content: String, val changedAtMillis: Long,
    val changeSource: String, val turnId: String?) {
    override fun toString() = "MemoryRevisionRecord(内容已隐藏)"
}

@Serializable
data class MemoryHistoryPage(val memoryId: String, val currentVersion: Int, val offset: Int, val limit: Int,
    val totalItems: Int, val items: List<MemoryRevisionRecord>)
