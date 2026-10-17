package com.healthyagent.running.agent.cloud

import androidx.room.*
import java.util.UUID

/** 会话只归属一个原生身份；消息仍使用已有请求及回执，不复制成第二份正文。 */
@Entity(tableName = "agent_conversations")
data class AgentConversation(
    @PrimaryKey val id: String,
    @Embedded val identity: CloudTaskIdentity,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val selected: Boolean,
)

@Dao
abstract class AgentConversationDao {
    @Insert protected abstract suspend fun insert(row: AgentConversation)
    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    protected abstract suspend fun byId(id: String): AgentConversation?
    @Query("SELECT * FROM agent_conversations WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef ORDER BY updatedAtMillis DESC, id DESC")
    protected abstract suspend fun rows(origin: String, ownerId: String, deviceId: String, credentialRef: String): List<AgentConversation>
    @Query("UPDATE agent_conversations SET selected = 0 WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef")
    protected abstract suspend fun deselect(origin: String, ownerId: String, deviceId: String, credentialRef: String)
    @Query("UPDATE agent_conversations SET selected = 1 WHERE id = :id")
    protected abstract suspend fun markSelected(id: String)
    @Query("UPDATE agent_conversations SET title = :title, updatedAtMillis = :now WHERE id = :id")
    protected abstract suspend fun renameRow(id: String, title: String, now: Long)

    suspend fun list(identity: CloudTaskIdentity) = rows(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)
    suspend fun find(identity: CloudTaskIdentity, id: String) = byId(id)?.takeIf { it.identity == identity }

    @Transaction
    open suspend fun create(identity: CloudTaskIdentity, now: Long): AgentConversation {
        require(now >= 0)
        deselect(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)
        val row = AgentConversation(UUID.randomUUID().toString(), identity, "新对话", now, now, true)
        insert(row)
        return row
    }

    @Transaction
    open suspend fun select(identity: CloudTaskIdentity, id: String?) {
        if (id != null) require(find(identity, id) != null) { "会话不可用" }
        deselect(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)
        if (id != null) markSelected(id)
    }

    @Transaction
    open suspend fun rename(identity: CloudTaskIdentity, id: String, title: String, now: Long) {
        val row = requireNotNull(find(identity, id)) { "会话不可用" }
        require(title.isNotBlank() && title.length <= 80 && title.none { it.isISOControl() })
        renameRow(id, title.trim(), maxOf(now, row.updatedAtMillis))
    }
}
