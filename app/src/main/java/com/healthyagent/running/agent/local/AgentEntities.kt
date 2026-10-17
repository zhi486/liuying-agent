package com.healthyagent.running.agent.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.healthyagent.running.agent.AgentTaskSummary

@Entity(
    tableName = "agent_tasks",
    indices = [Index(value = ["ownerId", "clientRequestId"], unique = true), Index(value = ["ownerId", "createdAtMillis", "id"])],
)
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val clientRequestId: String,
    val title: String,
    val status: String,
    val reason: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun summary() = AgentTaskSummary(id, title, status, reason, createdAtMillis, updatedAtMillis)
}

@Entity(
    tableName = "agent_messages",
    primaryKeys = ["taskId", "sequence"],
    foreignKeys = [ForeignKey(entity = AgentTaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class AgentMessageEntity(val taskId: String, val sequence: Int, val role: String, val text: String, val createdAtMillis: Long)

@Entity(
    tableName = "agent_actions",
    primaryKeys = ["taskId", "sequence"],
    foreignKeys = [ForeignKey(entity = AgentTaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class AgentActionEntity(val taskId: String, val sequence: Int, val kind: String, val createdAtMillis: Long)
