package com.healthyagent.running.agent.memory

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.healthyagent.running.agent.cloud.CloudChatConsent
import com.healthyagent.running.agent.cloud.CloudTaskIntent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 原生任务和绑定在同一事务保存；只上传协议代际，不上传本机权限范围哈希。 */
@Entity(tableName = "agent_memory_tool_bindings", foreignKeys = [ForeignKey(
    entity = CloudTaskIntent::class, parentColumns = ["clientRequestId"], childColumns = ["requestId"],
    onDelete = ForeignKey.CASCADE,
)])
data class MemoryToolBinding(
    @PrimaryKey val requestId: String,
    val sourceHash: String,
    val scope: String,
    val grantGeneration: String,
    @ColumnInfo(defaultValue = "0") val factsAllowed: Boolean = false,
) {
    fun matches(intent: CloudTaskIntent) = runCatching {
        this == capture(intent, MemoryToolGrant(scope, grantGeneration, factsAllowed))
    }.getOrDefault(false)

    companion object {
        fun capture(intent: CloudTaskIntent, grant: MemoryToolGrant): MemoryToolBinding {
            require(intent.conversationId != null && grant.scope == CloudChatConsent.context(intent.identity))
            MemoryCommands.id(grant.generation)
            val source = JsonArray(listOf(intent.clientRequestId, intent.localTaskId, intent.identity.origin,
                intent.identity.ownerId, intent.identity.deviceId, intent.identity.credentialRef, intent.prompt)
                .map(::JsonPrimitive) + listOf(JsonPrimitive(intent.consentedAtMillis),
                intent.conversationId?.let(::JsonPrimitive) ?: JsonNull) + listOf(
                intent.consentVersion, intent.memoryRefsJson, intent.knowledgeJson,
                intent.runSummaryJson, intent.conversationJson,
            ).map { it?.let(::JsonPrimitive) ?: JsonNull } +
                // 旧任务继续使用原哈希，新任务把插件授权快照一并冻结。
                listOfNotNull(intent.mcpToolsJson?.let(::JsonPrimitive)) +
                if (grant.factsAllowed) listOf(JsonPrimitive("facts_v1")) else emptyList())
            return MemoryToolBinding(intent.clientRequestId, MemoryToolPolicy.hash(source.toString()), grant.scope, grant.generation, grant.factsAllowed)
        }
    }
}

@Dao
interface MemoryToolBindingDao {
    @Insert suspend fun insert(binding: MemoryToolBinding)
    @Query("SELECT * FROM agent_memory_tool_bindings WHERE requestId = :requestId")
    suspend fun find(requestId: String): MemoryToolBinding?
}
