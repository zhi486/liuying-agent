package com.healthyagent.running.agent.cloud

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 旧版任务保持独立请求，只为完整原生身份保存历史组的显示状态。 */
@Entity(tableName = "agent_legacy_conversation_state", primaryKeys = ["origin", "ownerId", "deviceId", "credentialRef"])
data class LegacyConversationState(
    @Embedded val identity: CloudTaskIdentity,
    val archivedAtMillis: Long?,
    @ColumnInfo(defaultValue = "1") val legacySelected: Boolean = true,
)

/** 无正文的删除标记；云端确认后仍保留原请求键，防止迟到保存重新产生聊天。 */
@Entity(tableName = "agent_conversation_cleanup", primaryKeys = ["origin", "ownerId", "deviceId", "credentialRef", "requestId"],
    indices = [Index(value = ["requestId"])])
data class ConversationCleanup(
    @Embedded val identity: CloudTaskIdentity,
    val requestId: String,
    val atMillis: Long,
    val pending: Boolean,
)

/** 只保留必要用量，不保存题目、回答、会话标题或工具结果。 */
@Entity(tableName = "agent_conversation_retained_usage", primaryKeys = ["origin", "ownerId", "deviceId", "credentialRef", "requestId"])
data class ConversationRetainedUsage(
    @Embedded val identity: CloudTaskIdentity,
    val requestId: String,
    val atMillis: Long,
    val costJson: String?,
)

@Dao
abstract class ConversationLifecycleDao {
    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    protected abstract suspend fun conversation(id: String): AgentConversation?

    @Query("UPDATE agent_conversations SET archivedAtMillis = :archivedAtMillis, updatedAtMillis = :now, selected = CASE WHEN :archivedAtMillis IS NOT NULL THEN 0 ELSE selected END WHERE id = :id")
    protected abstract suspend fun archiveRow(id: String, archivedAtMillis: Long?, now: Long)

    @Query("SELECT * FROM agent_legacy_conversation_state WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef")
    protected abstract suspend fun legacyState(origin: String, ownerId: String, deviceId: String, credentialRef: String): LegacyConversationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun saveLegacyState(state: LegacyConversationState)

    @Query("SELECT EXISTS(SELECT 1 FROM agent_cloud_intents WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND conversationId IS NULL)")
    protected abstract suspend fun hasLegacy(origin: String, ownerId: String, deviceId: String, credentialRef: String): Boolean

    @Query("SELECT * FROM agent_cloud_intents WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND ((:conversationId IS NULL AND conversationId IS NULL) OR conversationId = :conversationId) ORDER BY consentedAtMillis, clientRequestId")
    protected abstract suspend fun allIntents(origin: String, ownerId: String, deviceId: String, credentialRef: String, conversationId: String?): List<CloudTaskIntent>

    @Query("SELECT * FROM agent_cloud_receipts WHERE clientRequestId = :requestId")
    protected abstract suspend fun receipt(requestId: String): CloudTaskReceipt?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun saveCleanup(row: ConversationCleanup)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun saveUsage(row: ConversationRetainedUsage)

    @Query("DELETE FROM agent_cloud_intents WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND ((:conversationId IS NULL AND conversationId IS NULL) OR conversationId = :conversationId)")
    protected abstract suspend fun deleteIntents(origin: String, ownerId: String, deviceId: String, credentialRef: String, conversationId: String?)

    @Query("DELETE FROM agent_conversations WHERE id = :id")
    protected abstract suspend fun deleteConversation(id: String)

    @Query("UPDATE agent_summary_requests SET conversationId = NULL, snapshotJson = NULL, sourceIdsJson = NULL, referencesJson = NULL, revision = revision + 1 WHERE conversationId = :id")
    protected abstract suspend fun detachSummaryRequests(id: String)

    @Query("SELECT * FROM agent_conversation_cleanup WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND pending = 1 ORDER BY atMillis, requestId")
    protected abstract suspend fun pendingRows(origin: String, ownerId: String, deviceId: String, credentialRef: String): List<ConversationCleanup>

    @Query("UPDATE agent_conversation_cleanup SET pending = 0 WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef AND requestId = :requestId")
    protected abstract suspend fun completeRow(origin: String, ownerId: String, deviceId: String, credentialRef: String, requestId: String)

    @Query("SELECT * FROM agent_conversation_retained_usage WHERE origin = :origin AND ownerId = :ownerId AND deviceId = :deviceId AND credentialRef = :credentialRef ORDER BY atMillis DESC, requestId DESC LIMIT :limit")
    protected abstract suspend fun usageRows(origin: String, ownerId: String, deviceId: String, credentialRef: String, limit: Int): List<ConversationRetainedUsage>

    suspend fun legacyAvailable(identity: CloudTaskIdentity): Boolean =
        hasLegacy(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)

    @Transaction
    open suspend fun legacyArchived(identity: CloudTaskIdentity): Boolean = legacyAvailable(identity) &&
        legacyState(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)?.archivedAtMillis != null

    suspend fun legacySelected(identity: CloudTaskIdentity): Boolean =
        legacyState(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)?.legacySelected ?: true

    @Transaction
    open suspend fun selectLegacy(identity: CloudTaskIdentity, selected: Boolean) {
        val previous = legacyState(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)
        saveLegacyState(LegacyConversationState(identity, previous?.archivedAtMillis, selected))
    }

    @Transaction
    open suspend fun archive(identity: CloudTaskIdentity, id: String?, archived: Boolean, now: Long) {
        require(now >= 0)
        if (id == null) {
            require(legacyAvailable(identity)) { "会话不可用" }
            val previous = legacyState(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)
            saveLegacyState(LegacyConversationState(identity, if (archived) previous?.archivedAtMillis ?: now else null,
                if (archived) false else previous?.legacySelected ?: true))
        } else {
            val row = conversation(id)?.takeIf { it.identity == identity } ?: throw IllegalArgumentException("会话不可用")
            if ((row.archivedAtMillis != null) == archived) return
            archiveRow(id, if (archived) now else null, maxOf(now, row.updatedAtMillis))
            if (archived && row.selected) selectLegacy(identity, false)
        }
    }

    /** 标记、用量转存及正文清除是一个事务；外键只清理聊天附属项，独立记忆与本机任务保留。 */
    @Transaction
    open suspend fun delete(identity: CloudTaskIdentity, id: String?, now: Long): List<ConversationCleanup> {
        require(now >= 0)
        if (id != null) {
            val row = conversation(id) ?: return emptyList()
            require(row.identity == identity) { "会话不可用" }
            if (row.selected) selectLegacy(identity, false)
        }
        val rows = allIntents(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, id)
        val cleanup = rows.map { intent ->
            val received = receipt(intent.clientRequestId)
            val attempted = intent.submissionAttempted || received != null
            val marker = ConversationCleanup(identity, intent.clientRequestId, now, attempted)
            saveCleanup(marker)
            if (attempted) {
                val cost = runCatching {
                    val result = received?.resultJson?.let { Json.parseToJsonElement(it) } as? JsonObject
                    result?.get("cost")?.let(CloudCostEstimate::validate)?.toString()
                }.getOrNull()
                saveUsage(ConversationRetainedUsage(identity, intent.clientRequestId, intent.consentedAtMillis, cost))
            }
            marker
        }
        deleteIntents(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, id)
        if (id == null) saveLegacyState(LegacyConversationState(identity, null, false))
        else {
            detachSummaryRequests(id)
            deleteConversation(id)
        }
        return cleanup
    }

    suspend fun pending(identity: CloudTaskIdentity): List<ConversationCleanup> =
        pendingRows(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)

    suspend fun complete(identity: CloudTaskIdentity, requestId: String) =
        completeRow(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, requestId)

    suspend fun retainedUsage(identity: CloudTaskIdentity, limit: Int = 100): List<ConversationRetainedUsage> {
        require(limit in 1..100)
        return usageRows(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef, limit)
    }
}
