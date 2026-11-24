package com.healthyagent.running.agent.cloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.healthyagent.running.agent.memory.MemoryEntity
import com.healthyagent.running.agent.memory.MemoryConflict
import com.healthyagent.running.agent.memory.MemoryContextDependencies
import kotlinx.serialization.json.*

@Dao
abstract class CloudTaskIntentDao {
    @Query("SELECT EXISTS(SELECT 1 FROM agent_conversation_cleanup WHERE requestId = :requestId)")
    protected abstract suspend fun deletedRequest(requestId: String): Boolean

    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    protected abstract suspend fun intentConversation(id: String): AgentConversation?

    @Query("SELECT EXISTS(SELECT 1 FROM agent_legacy_conversation_state WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND archivedAtMillis IS NOT NULL)")
    protected abstract suspend fun legacyReadOnly(origin: String, ownerId: String, deviceId: String, credentialRef: String): Boolean

    private suspend fun validateConversation(intent: CloudTaskIntent, confirmed: Boolean = false) {
        require(!deletedRequest(intent.clientRequestId)) { "请求已删除" }
        if (!confirmed && intent.conversationId == null) require(!legacyReadOnly(intent.identity.origin, intent.identity.ownerId,
            intent.identity.deviceId, intent.identity.credentialRef)) { "会话已归档" }
        intent.conversationId?.let { id ->
            require(intentConversation(id)?.let { it.identity == intent.identity && (confirmed || it.archivedAtMillis == null) } == true) { "会话不可发送" }
        }
    }
    @Query("SELECT * FROM agent_cloud_intents WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND ((:conversationId IS NULL AND conversationId IS NULL) OR conversationId = :conversationId) ORDER BY consentedAtMillis DESC, clientRequestId DESC LIMIT :limit OFFSET :offset")
    protected abstract suspend fun conversationRows(origin: String, ownerId: String, deviceId: String, credentialRef: String, conversationId: String?, offset: Int, limit: Int): List<CloudTaskIntent>

    suspend fun conversationPage(identity: CloudTaskIdentity, conversationId: String?, offset: Int = 0, limit: Int = 10): List<CloudTaskIntent> {
        require(offset >= 0 && limit in 1..50)
        return conversationRows(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, conversationId, offset, limit)
    }
    @Query("SELECT * FROM agent_memories WHERE ownerId = 'local' AND id = :id")
    protected abstract suspend fun selectedMemory(id: String): MemoryEntity?
    @Query("SELECT * FROM agent_memory_context_dependencies WHERE requestId = :requestId")
    protected abstract suspend fun memoryDependencies(requestId: String): MemoryContextDependencies?

    private suspend fun validateContextMemories(intent: CloudTaskIntent) {
        val stored = memoryDependencies(intent.clientRequestId)
        if (stored == null) {
            if (intent.conversation()?.get("messages")?.jsonArray?.isNotEmpty() == true) throw MemoryConflict()
            return
        }
        val dependencies = stored.state()
        if (dependencies.exhausted) throw MemoryConflict()
        for (ref in dependencies.refs) {
            val row = selectedMemory(ref.id) ?: throw MemoryConflict()
            if (row.version != ref.version || row.deletedAtMillis != null || row.content == null || row.source != "user_explicit") throw MemoryConflict()
        }
    }

    /** 读取授权版本与占用唯一 POST 许可在同一事务；记忆正文只在本次请求内存中存在。 */
    @Transaction
    open suspend fun claimSelected(identity: CloudTaskIdentity, requestId: String, now: Long): JsonObject? {
        val intent = find(identity, requestId) ?: return null
        if (intent.submissionAttempted || intent.pendingControl != null) return null
        validateConversation(intent, confirmed = true)
        validateContextMemories(intent)
        require(intent.memoryRefsJson != null && intent.consentVersion in setOf("selected_memories_v1", "selected_knowledge_v1", "selected_run_v1"))
        val context = buildJsonObject {
            put("version", "selected_memories_v1"); put("approved", true)
            put("items", JsonArray(intent.memoryRefs().map { ref ->
                val row = selectedMemory(ref.id) ?: throw MemoryConflict()
                if (row.version != ref.version || row.deletedAtMillis != null || row.content == null || row.source != "user_explicit") throw MemoryConflict()
                buildJsonObject { put("id", ref.id); put("version", ref.version); put("content", row.content) }
            }))
        }
        intent.submission(context)
        check(markAttempted(requestId, maxOf(now, intent.updatedAtMillis)) == 1)
        return context
    }
    @Insert protected abstract suspend fun insert(intent: CloudTaskIntent)

    @Query("SELECT * FROM agent_cloud_intents WHERE clientRequestId = :requestId")
    protected abstract suspend fun findRequest(requestId: String): CloudTaskIntent?

    @Query("SELECT * FROM agent_cloud_intents WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef ORDER BY consentedAtMillis DESC, clientRequestId DESC LIMIT :limit OFFSET :offset")
    protected abstract suspend fun rows(origin: String, ownerId: String, deviceId: String, credentialRef: String, offset: Int, limit: Int): List<CloudTaskIntent>

    @Query("UPDATE agent_cloud_intents SET submissionAttempted = 1, updatedAtMillis = :now WHERE clientRequestId = :requestId AND submissionAttempted = 0 AND pendingControl IS NULL")
    protected abstract suspend fun markAttempted(requestId: String, now: Long): Int

    @Query("UPDATE agent_cloud_intents SET pendingControl = :control, updatedAtMillis = :now WHERE clientRequestId = :requestId")
    protected abstract suspend fun markControl(requestId: String, control: String, now: Long)

    @Query("SELECT * FROM agent_cloud_receipts WHERE clientRequestId = :requestId")
    protected abstract suspend fun readReceipt(requestId: String): CloudTaskReceipt?

    @Upsert protected abstract suspend fun writeReceipt(receipt: CloudTaskReceipt)

    @Query("UPDATE agent_cloud_receipts SET resultJson = NULL, ackPending = 0 WHERE clientRequestId = :requestId")
    protected abstract suspend fun hideResult(requestId: String)

    @Transaction
    open suspend fun receipt(identity: CloudTaskIdentity, requestId: String): CloudTaskReceipt? {
        find(identity, requestId) ?: return null
        return readReceipt(requestId)
    }

    /** 事务失败则没有可确认的正文；客户端必须等此方法返回才可 acknowledge。 */
    @Transaction
    open suspend fun accept(identity: CloudTaskIdentity, requestId: String, incoming: CloudTaskResponse): CloudTaskReceipt {
        val intent = find(identity, requestId) ?: throw CloudTaskFailure("not_found")
        require(intent.submissionAttempted) { "任务尚未尝试提交" }
        val merged = CloudTaskReceipt.merge(intent, readReceipt(requestId), incoming)
        writeReceipt(merged)
        // Room Upsert 的非主键唯一冲突可能退为零行更新，不能把它当作已持久化。
        check(readReceipt(requestId) == merged) { "云结果未完整持久化" }
        return merged
    }

    /** 首次原子保存；重试必须带原始完整快照，不重置任何可变恢复状态。 */
    @Transaction
    open suspend fun save(intent: CloudTaskIntent): CloudTaskIntent {
        require(!intent.submissionAttempted && intent.pendingControl == null && intent.updatedAtMillis == intent.consentedAtMillis)
        validateConversation(intent)
        val existing = findRequest(intent.clientRequestId)
        if (existing != null) {
            if (existing.copy(submissionAttempted = false, pendingControl = null, updatedAtMillis = existing.consentedAtMillis) != intent)
                throw CloudTaskIntentConflict()
            return existing
        }
        insert(intent)
        return intent
    }

    open suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskIntent? =
        findRequest(requestId)?.takeIf { it.identity == identity }

    open suspend fun page(identity: CloudTaskIdentity, offset: Int, limit: Int): List<CloudTaskIntent> {
        require(offset >= 0 && limit in 1..50)
        return rows(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, offset, limit)
    }

    /** 仅返回一次发网许可；提交此标志后即使崩溃也只能查询原键，不能回滚或自动重发。 */
    @Transaction
    open suspend fun claimSubmission(identity: CloudTaskIdentity, requestId: String, now: Long): Boolean {
        require(now >= 0)
        val intent = find(identity, requestId) ?: return false
        if (intent.submissionAttempted || intent.pendingControl != null) return false
        validateConversation(intent, confirmed = true)
        require(intent.memoryRefsJson == null && intent.consentVersion in setOf("prompt_only_v1", "selected_knowledge_v1", "selected_run_v1")) { "含记忆的任务必须核对版本后发送" }
        validateContextMemories(intent)
        return markAttempted(requestId, maxOf(now, intent.updatedAtMillis)) == 1
    }

    /** 只是待控制意图，不表示云已取消/撤回；撤回可覆盖取消，反向不可降级。 */
    @Transaction
    open suspend fun requestControl(identity: CloudTaskIdentity, requestId: String, control: String, now: Long): CloudTaskIntent? {
        require((control == "cancel" || control == "withdraw") && now >= 0)
        val intent = find(identity, requestId) ?: return null
        if (intent.pendingControl == control || intent.pendingControl == "withdraw") return intent
        markControl(requestId, control, maxOf(now, intent.updatedAtMillis))
        if (control == "withdraw") hideResult(requestId)
        return find(identity, requestId)
    }
}
