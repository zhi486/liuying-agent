package com.healthyagent.running.agent.cloud

import androidx.room.*
import com.healthyagent.running.agent.memory.MemoryContextState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 请求账本独立于单行摘要缓存；删除会话后仅保留请求和结算元数据。 */
@Entity(tableName = "agent_summary_requests", indices = [Index(
    value = ["origin", "ownerId", "deviceId", "credentialRef", "conversationId", "fingerprint"], unique = true)])
data class ConversationSummaryRequest(
    @PrimaryKey val requestId: String,
    @Embedded val identity: CloudTaskIdentity,
    val conversationId: String?,
    val fingerprint: String,
    val snapshotJson: String?,
    val sourceIdsJson: String?,
    val referencesJson: String?,
    val createdAtMillis: Long,
    val revision: Long,
    val attempted: Boolean,
    val remoteFinished: Boolean,
    val cost: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
) {
    override fun toString() = "ConversationSummaryRequest(摘要与来源已隐藏)"
}

@Dao
abstract class ConversationSummaryRequestDao {
    @Query("SELECT * FROM agent_summary_requests WHERE requestId = :id")
    abstract suspend fun find(id: String): ConversationSummaryRequest?

    @Query("SELECT * FROM agent_summary_requests WHERE origin = :origin AND ownerId = :owner AND deviceId = :device AND credentialRef = :credential AND attempted = 1 AND remoteFinished = 0 ORDER BY createdAtMillis, requestId")
    abstract suspend fun recoverable(origin: String, owner: String, device: String, credential: String): List<ConversationSummaryRequest>

    /** 删除后的最小账本也只能领取原请求查询，不重建正文或准备态。 */
    @Transaction
    open suspend fun claimRecovery(identity: CloudTaskIdentity, id: String, revision: Long,
        current: ConversationSummaryPolicy.Candidate?): ConversationSummaryProtocol.Step? {
        val row = find(id) ?: return null
        if (row.identity != identity || row.revision != revision || !row.attempted || row.remoteFinished ||
            revision < 0 || revision == Long.MAX_VALUE) return null
        if (row.snapshotJson != null) {
            val snapshot = decode(row)
            val valid = current.takeIf { available(identity, row.conversationId) }
            val step = ConversationSummaryProtocol.next(snapshot, valid)
            if (update(id, revision, ConversationSummaryProtocol.encode(step.snapshot), true, false,
                    row.cost, row.inputTokens, row.outputTokens) != 1) return null
            return step.copy(action = ConversationSummaryProtocol.Action.QUERY)
        }
        require(row.conversationId == null && row.sourceIdsJson == null && row.referencesJson == null)
        val usage = row.inputTokens?.let { ConversationSummaryProtocol.Usage(it, requireNotNull(row.outputTokens)) }
        require((row.outputTokens != null) == (usage != null))
        val snapshot = ConversationSummaryProtocol.Snapshot(row.requestId, row.fingerprint,
            ConversationSummaryProtocol.State.CANCELLED, ConversationSummaryProtocol.Cost.valueOf(row.cost), usage)
        if (update(id, revision, null, true, false, row.cost, row.inputTokens, row.outputTokens) != 1) return null
        return ConversationSummaryProtocol.Step(snapshot, ConversationSummaryProtocol.Action.QUERY)
    }

    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    protected abstract suspend fun conversation(id: String): AgentConversation?

    @Query("SELECT * FROM agent_summary_requests WHERE conversationId = :id")
    protected abstract suspend fun requests(id: String): List<ConversationSummaryRequest>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(row: ConversationSummaryRequest): Long

    @Query("UPDATE agent_summary_requests SET snapshotJson = :snapshot, revision = revision + 1, attempted = :attempted, remoteFinished = :finished, cost = :cost, inputTokens = :input, outputTokens = :output WHERE requestId = :id AND revision = :revision")
    protected abstract suspend fun update(id: String, revision: Long, snapshot: String?, attempted: Boolean,
        finished: Boolean, cost: String, input: Long?, output: Long?): Int

    private suspend fun available(identity: CloudTaskIdentity, id: String?) = id != null &&
        conversation(id)?.let { it.identity == identity && it.selected && it.archivedAtMillis == null } == true

    private fun decode(row: ConversationSummaryRequest): ConversationSummaryProtocol.Snapshot {
        val snapshot = ConversationSummaryProtocol.decode(requireNotNull(row.snapshotJson))
        require(snapshot.requestId == row.requestId && snapshot.fingerprint == row.fingerprint)
        require(snapshot.cost.name == row.cost && snapshot.usage?.inputTokens == row.inputTokens &&
            snapshot.usage?.outputTokens == row.outputTokens)
        require(row.attempted || snapshot.state in setOf(ConversationSummaryProtocol.State.PREPARED,
            ConversationSummaryProtocol.State.INVALID, ConversationSummaryProtocol.State.CANCELLED))
        require(!row.attempted || snapshot.state != ConversationSummaryProtocol.State.PREPARED)
        return snapshot
    }

    /** 只读正文不领取网络动作；失效永久清正文，结算字段保持不变。 */
    @Transaction
    open suspend fun readCompleted(identity: CloudTaskIdentity, id: String,
        current: ConversationSummaryPolicy.Candidate?): String? {
        val valid = current.takeIf { available(identity, id) }
        var text: String? = null
        for (row in requests(id).filter { it.identity == identity }) {
            val snapshot = decode(row)
            if (snapshot.state != ConversationSummaryProtocol.State.COMPLETED) continue
            require(row.attempted && row.remoteFinished)
            val next = ConversationSummaryProtocol.next(snapshot, valid).snapshot
            if (next != snapshot) {
                check(update(row.requestId, row.revision, ConversationSummaryProtocol.encode(next),
                    row.attempted, row.remoteFinished, row.cost, row.inputTokens, row.outputTokens) == 1)
            } else {
                require(row.sourceIdsJson == JsonArray(requireNotNull(valid).sourceIds.map(::JsonPrimitive)).toString())
                require(row.referencesJson == MemoryContextState(valid.dependencies).row(valid.sourceIds.first()).referencesJson)
                check(text == null)
                text = snapshot.summary
            }
        }
        return text
    }

    /** current 由可信宿主在同一外层事务内复核；不得接收 Web 提供的有效标记。 */
    @Transaction
    open suspend fun prepare(identity: CloudTaskIdentity, id: String, requestId: String,
        current: ConversationSummaryPolicy.Candidate, now: Long): Boolean {
        require(now >= 0)
        if (!available(identity, id)) return false
        val rows = requests(id)
        rows.forEach { decode(it) }
        if (rows.any { it.identity != identity || it.fingerprint == current.fingerprint || (it.attempted && !it.remoteFinished) }) return false
        val snapshot = ConversationSummaryProtocol.prepare(requestId, current)
        return insert(ConversationSummaryRequest(requestId, identity, id, current.fingerprint,
            ConversationSummaryProtocol.encode(snapshot), JsonArray(current.sourceIds.map(::JsonPrimitive)).toString(),
            MemoryContextState(current.dependencies).row(current.sourceIds.first()).referencesJson, now, 0, false, false,
            snapshot.cost.name, null, null)) != -1L
    }

    @Transaction
    open suspend fun next(identity: CloudTaskIdentity, id: String, expectedRevision: Long,
        current: ConversationSummaryPolicy.Candidate?): ConversationSummaryProtocol.Step? {
        val row = find(id) ?: return null
        if (row.identity != identity || row.revision != expectedRevision || row.snapshotJson == null) return null
        val snapshot = decode(row)
        val valid = current.takeIf { available(identity, row.conversationId) }
        val step = ConversationSummaryProtocol.next(snapshot, valid)
        if (step.action == ConversationSummaryProtocol.Action.SUBMIT &&
            requests(requireNotNull(row.conversationId)).any { it.requestId != id && it.attempted && !it.remoteFinished }) return null
        val attempted = row.attempted || step.action == ConversationSummaryProtocol.Action.SUBMIT
        if (update(id, row.revision, ConversationSummaryProtocol.encode(step.snapshot), attempted, row.remoteFinished,
                step.snapshot.cost.name, step.snapshot.usage?.inputTokens, step.snapshot.usage?.outputTokens) != 1) return null
        return if (row.remoteFinished) step.copy(action = null) else if (attempted &&
            step.snapshot.state in setOf(ConversationSummaryProtocol.State.INVALID, ConversationSummaryProtocol.State.CANCELLED))
            step.copy(action = ConversationSummaryProtocol.Action.QUERY) else step
    }

    /** 已失效正文不会复活；远端结束与实际用量分开保存，不用本地终态冒充云端确认。 */
    @Transaction
    open suspend fun receive(identity: CloudTaskIdentity, id: String, expectedRevision: Long, fingerprint: String,
        current: ConversationSummaryPolicy.Candidate?, result: ConversationSummaryProtocol.Result): Boolean {
        val row = find(id) ?: return false
        if (row.identity != identity || row.revision != expectedRevision || row.fingerprint != fingerprint || !row.attempted) return false
        val finished = row.remoteFinished || result is ConversationSummaryProtocol.Result.Completed ||
            result is ConversationSummaryProtocol.Result.Failed || result == ConversationSummaryProtocol.Result.NotDispatched ||
            (result is ConversationSummaryProtocol.Result.Withdrawn && result.remoteFinished)
        if (row.snapshotJson == null) {
            val usage = when (result) {
                is ConversationSummaryProtocol.Result.Completed -> result.usage
                is ConversationSummaryProtocol.Result.Failed -> result.usage
                is ConversationSummaryProtocol.Result.Withdrawn -> result.usage
                else -> null
            }
            val input = row.inputTokens ?: usage?.inputTokens
            val output = row.outputTokens ?: usage?.outputTokens
            val cost = if (input != null) ConversationSummaryProtocol.Cost.USAGE.name
                else if (result == ConversationSummaryProtocol.Result.NotDispatched) ConversationSummaryProtocol.Cost.NOT_DISPATCHED.name else row.cost
            return update(id, row.revision, null, true, finished, cost, input, output) == 1
        }
        val valid = current.takeIf { available(identity, row.conversationId) }
        val snapshot = ConversationSummaryProtocol.receive(decode(row), id, fingerprint, valid, result)
        return update(id, row.revision, ConversationSummaryProtocol.encode(snapshot), true, finished,
            snapshot.cost.name, snapshot.usage?.inputTokens, snapshot.usage?.outputTokens) == 1
    }
}
