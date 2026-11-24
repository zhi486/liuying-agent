package com.healthyagent.running.agent.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.healthyagent.running.agent.cloud.CloudTaskIntent
import kotlinx.serialization.json.*

/** 只保留引用，不复制正文；跟随任务保存，防止记忆经旧回答间接进入后续上下文。 */
@Entity(tableName = "agent_memory_context_dependencies", foreignKeys = [ForeignKey(
    entity = CloudTaskIntent::class, parentColumns = ["clientRequestId"], childColumns = ["requestId"],
    onDelete = ForeignKey.CASCADE,
)])
data class MemoryContextDependencies(@PrimaryKey val requestId: String, val referencesJson: String, val exhausted: Boolean,
    val protectedReferencesJson: String? = null) {
    fun state(): MemoryContextState {
        val current = MemoryContextState.decode(referencesJson, exhausted)
        protectedReferencesJson?.let {
            val baseline = MemoryContextState.decode(it, false)
            require(current.exhausted || current.refs.containsAll(baseline.refs))
        }
        return current
    }

    /** 只推进本轮工具产生的精确引用；继承／手选基线以及来源未知的旧行不放宽。 */
    fun mergeToolReferences(more: Collection<MemoryRef>, updatedFrom: MemoryRef? = null): MemoryContextDependencies {
        val current = state()
        val baseline = protectedReferencesJson?.let { MemoryContextState.decode(it, false) }
        val successor = more.singleOrNull()
        val replaces = !current.exhausted && baseline != null && updatedFrom != null &&
            updatedFrom !in baseline.refs && updatedFrom.version < Int.MAX_VALUE &&
            successor == MemoryRef(updatedFrom.id, updatedFrom.version + 1)
        val next = if (replaces) current.copy(refs = current.refs - updatedFrom!!).merge(more) else current.merge(more)
        return next.row(requestId).copy(protectedReferencesJson = protectedReferencesJson)
    }
    override fun toString() = "MemoryContextDependencies(引用已隐藏)"
}

data class MemoryContextState(val refs: Set<MemoryRef> = emptySet(), val exhausted: Boolean = false) {
    init {
        require(refs.size <= 256 && (!exhausted || refs.isEmpty()))
        refs.forEach { MemoryCommands.id(it.id); require(it.version > 0) }
    }

    // 超限时整条回答不再用于后续上下文，不能截掉一部分引用后误认为仍可用。
    fun merge(more: Collection<MemoryRef>): MemoryContextState {
        val combined = refs + more
        return if (exhausted || combined.size > 256) MemoryContextState(exhausted = true) else MemoryContextState(combined)
    }

    fun row(requestId: String, freezeBaseline: Boolean = false): MemoryContextDependencies {
        MemoryCommands.id(requestId)
        val encoded = JsonArray(refs.sortedWith(compareBy<MemoryRef> { it.id }.thenBy { it.version }).map {
            buildJsonObject { put("id", it.id); put("version", it.version) }
        })
        require(!freezeBaseline || !exhausted)
        return MemoryContextDependencies(requestId, encoded.toString(), exhausted,
            if (freezeBaseline) encoded.toString() else null)
    }

    companion object {
        fun decode(value: String, exhausted: Boolean): MemoryContextState {
            require(value.length <= 20_000)
            val array = Json.parseToJsonElement(value) as? JsonArray ?: throw IllegalArgumentException("引用格式无效")
            require(array.size <= 256)
            val refs = array.map { item ->
                val row = item as? JsonObject ?: throw IllegalArgumentException("引用格式无效")
                require(row.keys == setOf("id", "version"))
                val id = row.getValue("id") as? JsonPrimitive
                val version = row.getValue("version") as? JsonPrimitive
                require(id?.isString == true && version != null && !version.isString)
                MemoryRef(MemoryCommands.id(id.content), requireNotNull(version.intOrNull).also { require(it > 0) })
            }
            require(refs.distinct().size == refs.size)
            return MemoryContextState(refs.toSet(), exhausted)
        }
    }
}

@Dao
interface MemoryContextDependenciesDao {
    @Query("SELECT * FROM agent_memory_context_dependencies WHERE requestId = :requestId")
    suspend fun find(requestId: String): MemoryContextDependencies?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: MemoryContextDependencies)
}
