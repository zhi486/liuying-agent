package com.healthyagent.running.agent.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.healthyagent.running.agent.AgentAction
import com.healthyagent.running.agent.AgentMessage
import com.healthyagent.running.agent.AgentRequestConflict
import com.healthyagent.running.agent.AgentTaskDetail
import com.healthyagent.running.agent.AgentTaskPage

@Dao
interface AgentTaskDao {
    @Insert suspend fun insertTask(task: AgentTaskEntity)
    @Insert suspend fun insertMessage(message: AgentMessageEntity)
    @Insert suspend fun insertAction(action: AgentActionEntity)

    @Query("SELECT * FROM agent_tasks WHERE ownerId = :ownerId AND clientRequestId = :clientRequestId")
    suspend fun findRequest(ownerId: String, clientRequestId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE ownerId = :ownerId AND id = :taskId")
    suspend fun findTask(ownerId: String, taskId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_messages WHERE taskId = :taskId ORDER BY sequence ASC")
    suspend fun messages(taskId: String): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_actions WHERE taskId = :taskId ORDER BY sequence ASC")
    suspend fun actions(taskId: String): List<AgentActionEntity>

    @Query("SELECT COUNT(*) FROM agent_tasks WHERE ownerId = :ownerId")
    suspend fun count(ownerId: String): Int

    @Query("SELECT * FROM agent_tasks WHERE ownerId = :ownerId ORDER BY createdAtMillis DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun tasks(ownerId: String, offset: Int, limit: Int): List<AgentTaskEntity>

    @Query("UPDATE agent_tasks SET status = 'cancelled', reason = NULL, updatedAtMillis = :now WHERE ownerId = :ownerId AND id = :taskId AND status = 'blocked'")
    suspend fun markCancelled(ownerId: String, taskId: String, now: Long): Int

    @Transaction
    suspend fun saveLocal(ownerId: String, clientRequestId: String, prompt: String, taskId: String, now: Long): AgentTaskDetail {
        val existing = findRequest(ownerId, clientRequestId)
        if (existing != null) {
            val detail = checkNotNull(detail(ownerId, existing.id))
            if (detail.messages.firstOrNull()?.text != prompt) throw AgentRequestConflict()
            return detail
        }
        insertTask(AgentTaskEntity(taskId, ownerId, clientRequestId, prompt.take(80), "blocked", "cloud_not_connected", now, now))
        insertMessage(AgentMessageEntity(taskId, 0, "user", prompt, now))
        insertAction(AgentActionEntity(taskId, 0, "saved_locally", now))
        return checkNotNull(detail(ownerId, taskId))
    }

    @Transaction
    suspend fun page(ownerId: String, offset: Int, limit: Int): AgentTaskPage {
        require(offset >= 0 && limit in 1..50)
        return AgentTaskPage(offset, limit, count(ownerId), tasks(ownerId, offset, limit).map { it.summary() })
    }

    @Transaction
    suspend fun detail(ownerId: String, taskId: String): AgentTaskDetail? {
        val task = findTask(ownerId, taskId) ?: return null
        return AgentTaskDetail(
            task.summary(),
            messages(taskId).map { AgentMessage(it.sequence, it.role, it.text, it.createdAtMillis) },
            actions(taskId).map { AgentAction(it.sequence, it.kind, it.createdAtMillis) },
        )
    }

    @Transaction
    suspend fun cancel(ownerId: String, taskId: String, now: Long): AgentTaskDetail? {
        val task = findTask(ownerId, taskId) ?: return null
        val updated = maxOf(now, task.updatedAtMillis)
        if (markCancelled(ownerId, taskId, updated) == 1) {
            insertAction(AgentActionEntity(taskId, 1, "cancelled", updated))
        }
        return detail(ownerId, taskId)
    }
}
