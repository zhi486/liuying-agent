package com.healthyagent.running.agent.cloud

import androidx.room.Embedded
import androidx.room.ColumnInfo
import com.healthyagent.running.agent.memory.MemoryRef
import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 只由可信原生配置构造；credentialRef 是不可换绑的凭据代次标识，不是 Bearer 或 current 槽位。 */
data class CloudTaskIdentity(val origin: String, val ownerId: String, val deviceId: String, val credentialRef: String) {
    init {
        val uri = try { URI(origin) } catch (_: Exception) { throw IllegalArgumentException("云端身份无效") }
        require(uri.scheme == "https" && uri.host != null && uri.host == uri.host.lowercase() &&
            uri.rawUserInfo == null && uri.rawPath == "" && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && uri.toASCIIString() == origin) { "云端身份无效" }
        require(listOf(ownerId, deviceId, credentialRef).all { Regex("[a-zA-Z0-9_-]{1,80}").matches(it) }) { "云端身份无效" }
    }
}

/** 独立在线意图，不是旧任务的上传队列；只保存明确授权的概要快照，不保存凭据或原始轨迹。 */
@Entity(
    tableName = "agent_cloud_intents",
    indices = [Index(value = ["localTaskId"], unique = true),
        Index(value = ["origin", "ownerId", "deviceId", "credentialRef", "consentedAtMillis", "clientRequestId"], name = "index_cloud_intents_identity_time")],
)
data class CloudTaskIntent(
    @PrimaryKey val clientRequestId: String,
    val localTaskId: String,
    @Embedded val identity: CloudTaskIdentity,
    val prompt: String,
    val consentVersion: String,
    val consentedAtMillis: Long,
    val updatedAtMillis: Long,
    val submissionAttempted: Boolean,
    val pendingControl: String?,
    @ColumnInfo(defaultValue = "NULL") val memoryRefsJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val knowledgeJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val runSummaryJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val conversationId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val conversationJson: String? = null,
) {
    init {
        require(uuid.matches(clientRequestId) && uuid.matches(localTaskId) && clientRequestId != localTaskId) { "在线任务标识无效" }
        require(memoryRefsJson == null || memoryRefs().isNotEmpty()) { "记忆授权无效" }
        require(when (consentVersion) {
            "prompt_only_v1" -> memoryRefsJson == null && knowledgeJson == null && runSummaryJson == null
            "selected_memories_v1" -> memoryRefsJson != null && knowledgeJson == null && runSummaryJson == null
            "selected_knowledge_v1" -> knowledgeJson != null && runSummaryJson == null
            "selected_run_v1" -> runSummaryJson != null
            else -> false
        }) { "在线任务授权无效" }
        knowledge()
        runSummary()
        require((conversationId == null) == (conversationJson == null))
        conversationId?.let { require(uuid.matches(it)); require(conversation()?.get("id") == JsonPrimitive(it)) }
        require(consentedAtMillis >= 0 && updatedAtMillis >= consentedAtMillis) { "在线任务时间无效" }
        require(pendingControl == null || pendingControl == "cancel" || pendingControl == "withdraw") { "在线任务控制无效" }
        require(supportedPrompt(prompt)) { "在线任务文本无效" }
    }

    /** 恢复时使用落盘原文；身份不进请求正文，授权版本的含义固定且不得扩展。 */
    fun memoryRefs(): List<MemoryRef> = memoryRefsJson?.let {
        (MemoryCommands.decode("memory.preview", JsonObject(mapOf("memoryRefs" to Json.parseToJsonElement(it)))) as MemoryCommand.Preview).memoryRefs
    } ?: emptyList()

    fun knowledge(): JsonObject? = knowledgeJson?.let { CloudKnowledge.selection(Json.parseToJsonElement(it)) }

    fun runSummary(): JsonObject? = runSummaryJson?.let { CloudRunSummary.validate(Json.parseToJsonElement(it)) }

    fun conversation(): JsonObject? = conversationJson?.let { ConversationContext.validate(Json.parseToJsonElement(it)) }

    fun submission(memoryContext: JsonObject? = null): JsonObject {
        require((memoryContext == null) == (memoryRefsJson == null)) { "记忆授权与正文不一致" }
        if (memoryContext != null) {
            require(memoryContext.keys == setOf("version", "approved", "items"))
            require(memoryContext["version"] == JsonPrimitive("selected_memories_v1") && memoryContext["approved"] == JsonPrimitive(true))
            val items = memoryContext.getValue("items").jsonArray
            val refs = memoryRefs()
            require(items.size == refs.size)
            items.forEachIndexed { index, item ->
                val row = item.jsonObject
                require(row.keys == setOf("id", "version", "content") && row["id"] == JsonPrimitive(refs[index].id) && row["version"] == JsonPrimitive(refs[index].version))
                val content = row.getValue("content").jsonPrimitive
                require(content.isString && MemoryCommands.content(content.content) == content.content && supportedPrompt(content.content))
            }
        }
        return JsonObject(mapOf(
        "clientRequestId" to JsonPrimitive(clientRequestId), "localTaskId" to JsonPrimitive(localTaskId),
        "prompt" to JsonPrimitive(prompt),
        "consent" to JsonObject(mapOf("cloudProcessing" to JsonPrimitive(true), "runSummaries" to JsonPrimitive(runSummaryJson != null))),
        "summaries" to JsonArray(listOfNotNull(runSummary())),
        ) + (if (memoryContext == null) emptyMap() else mapOf("memoryContext" to memoryContext)) +
            (knowledge()?.let { mapOf("knowledge" to it) } ?: emptyMap()) +
            (conversation()?.let { mapOf("conversation" to it) } ?: emptyMap()))
    }

    override fun toString() = "CloudTaskIntent(已脱敏)"

    companion object {
        private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        // 与服务端 JS trim 的空白集合一致，不使用 Kotlin 的更宽空白定义。
        private val jsWhitespace = "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"

        fun confirm(
            identity: CloudTaskIdentity, prompt: String, cloudProcessingApproved: Boolean, now: Long,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): CloudTaskIntent {
            require(cloudProcessingApproved) { "需要用户明确授权云端处理" }
            return CloudTaskIntent(newId(), newId(), identity, prompt, "prompt_only_v1", now, now, false, null)
        }

        fun confirmSelected(identity: CloudTaskIdentity, prompt: String, refs: List<MemoryRef>, now: Long): CloudTaskIntent {
            val encoded = JsonArray(refs.map { JsonObject(mapOf("id" to JsonPrimitive(it.id), "version" to JsonPrimitive(it.version))) }).toString()
            return CloudTaskIntent(UUID.randomUUID().toString(), UUID.randomUUID().toString(), identity, prompt, "selected_memories_v1", now, now, false, null, encoded)
        }

        internal fun supportedPrompt(value: String, maxLength: Int = 2000): Boolean {
            if (value.length !in 1..maxLength || value.all { it in jsWhitespace } ||
                value.contains("<｜image｜>") || value.contains("<｜deepseek_image｜>")) return false
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char.isHighSurrogate()) {
                    if (index == value.length || !value[index++].isLowSurrogate()) return false
                } else if (char.isLowSurrogate()) return false
            }
            return true
        }
    }
}

class CloudTaskIntentConflict : IllegalStateException("同一在线请求标识不能更换内容、身份或授权")
