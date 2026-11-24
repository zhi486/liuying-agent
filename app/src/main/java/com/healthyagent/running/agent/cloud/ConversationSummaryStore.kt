package com.healthyagent.running.agent.cloud

import androidx.room.*
import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryContextState
import kotlinx.serialization.json.*

/** 只保存覆盖元数据与合成摘要，不复制问答；会话删除后迟到结果只能更新零行。 */
@Entity(tableName = "agent_conversation_summaries", foreignKeys = [ForeignKey(
    entity = AgentConversation::class, parentColumns = ["id"], childColumns = ["conversationId"],
    onDelete = ForeignKey.CASCADE,
)])
data class ConversationSummaryRecord(
    @PrimaryKey val conversationId: String,
    val fingerprint: String,
    val attemptId: String,
    val processId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val sourceIdsJson: String,
    val referencesJson: String,
    val state: String,
    val summary: String?,
) {
    override fun toString() = "ConversationSummaryRecord(摘要与来源已隐藏)"
}

@Dao
abstract class ConversationSummaryDao {
    @Query("SELECT * FROM agent_conversation_summaries WHERE conversationId = :id")
    abstract suspend fun find(id: String): ConversationSummaryRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(row: ConversationSummaryRecord): Long

    @Query("UPDATE agent_conversation_summaries SET state = 'invalid', summary = NULL WHERE conversationId = :id AND attemptId = :attemptId")
    protected abstract suspend fun invalidate(id: String, attemptId: String): Int

    @Query("DELETE FROM agent_conversation_summaries WHERE conversationId = :id AND fingerprint != :fingerprint")
    protected abstract suspend fun removeChanged(id: String, fingerprint: String)

    @Query("UPDATE agent_conversation_summaries SET state = 'completed', summary = :text WHERE conversationId = :id AND fingerprint = :fingerprint AND attemptId = :attemptId AND processId = :processId AND state = 'pending'")
    protected abstract suspend fun complete(id: String, fingerprint: String, attemptId: String, processId: String, text: String): Int

    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    protected abstract suspend fun conversation(id: String): AgentConversation?

    private suspend fun available(identity: CloudTaskIdentity, id: String) =
        conversation(id)?.let { it.identity == identity && it.selected && it.archivedAtMillis == null } == true

    private fun matches(row: ConversationSummaryRecord, current: ConversationSummaryPolicy.Candidate) =
        row.fingerprint == current.fingerprint && row.sourceIdsJson == sources(current) && row.referencesJson == references(current)

    private fun sources(candidate: ConversationSummaryPolicy.Candidate) = JsonArray(candidate.sourceIds.map(::JsonPrimitive)).toString()
    private fun references(candidate: ConversationSummaryPolicy.Candidate) = MemoryContextState(candidate.dependencies).row(candidate.sourceIds.first()).referencesJson

    /** current 必须由同一事务内的宿主来源／授权复核取得，不能接受 Web 正文。 */
    @Transaction
    open suspend fun begin(identity: CloudTaskIdentity, id: String, current: ConversationSummaryPolicy.Candidate,
        attemptId: String, processId: String, now: Long, expires: Long): Boolean {
        MemoryCommands.id(attemptId); MemoryCommands.id(processId)
        require(now >= 0 && expires > now)
        if (!available(identity, id)) return false
        removeChanged(id, current.fingerprint)
        return insert(ConversationSummaryRecord(id, current.fingerprint, attemptId, processId, now, expires,
            sources(current), references(current), "pending", null)) != -1L
    }

    @Transaction
    open suspend fun accept(identity: CloudTaskIdentity, id: String, current: ConversationSummaryPolicy.Candidate?,
        attemptId: String, processId: String, text: String, now: Long): Boolean {
        val row = find(id) ?: return false
        if (row.attemptId != attemptId) return false
        if (row.state != "pending") return false
        if (!available(identity, id) || current == null || !matches(row, current) || now < row.createdAtMillis ||
            now >= row.expiresAtMillis || row.processId != processId) {
            invalidate(id, row.attemptId); return false
        }
        if (!CloudTaskIntent.supportedPrompt(text, 2000)) { invalidate(id, row.attemptId); return false }
        return complete(id, current.fingerprint, attemptId, processId, text) == 1
    }

    @Transaction
    open suspend fun read(identity: CloudTaskIdentity, id: String, current: ConversationSummaryPolicy.Candidate?, processId: String): String? {
        val row = find(id) ?: return null
        if (!available(identity, id) || current == null || !matches(row, current) ||
            (row.state == "pending" && row.processId != processId)) {
            invalidate(id, row.attemptId); return null
        }
        return row.summary?.takeIf { row.state == "completed" && CloudTaskIntent.supportedPrompt(it, 2000) }
    }
}
