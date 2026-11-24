package com.healthyagent.running.agent.memory

import androidx.room.Entity

/** 仅保留删除指纹及时间，不保留正文；手动和 Agent 写入同样受删除边界约束。 */
@Entity(tableName = "agent_memory_deletion_markers", primaryKeys = ["ownerId", "contentHash"])
data class MemoryDeletionMarker(val ownerId: String, val contentHash: String, val deletedAtMillis: Long) {
    override fun toString() = "MemoryDeletionMarker(内容已隐藏)"
}
