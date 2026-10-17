package com.healthyagent.running.agent.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.serialization.Serializable

@Entity(tableName = "agent_memories", indices = [Index(value = ["ownerId", "updatedAtMillis", "id"])])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val content: String?,
    val source: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val version: Int,
    val deletedAtMillis: Long? = null,
) {
    fun record() = MemoryRecord(id, checkNotNull(content), source, createdAtMillis, updatedAtMillis, version)
    override fun toString() = "MemoryEntity(内容已隐藏)"
}

@Serializable
data class MemoryRecord(val id: String, val content: String, val source: String, val createdAtMillis: Long, val updatedAtMillis: Long, val version: Int) {
    override fun toString() = "MemoryRecord(内容已隐藏)"
}
@Serializable data class MemoryPage(val offset: Int, val limit: Int, val totalItems: Int, val items: List<MemoryRecord>)
@Serializable data class MemoryDetail(val memory: MemoryRecord)
@Serializable data class MemoryDeleted(val memoryId: String, val deleted: Boolean = true)
@Serializable data class MemoryPreview(val items: List<MemoryRecord>, val scope: String = "local_preview")
class MemoryConflict : IllegalStateException("记忆已变化，请刷新后重新操作")

@Dao
interface MemoryDao {
    @Query("SELECT * FROM agent_memories WHERE ownerId = :ownerId AND id = :id")
    suspend fun find(ownerId: String, id: String): MemoryEntity?
    @Insert suspend fun insert(memory: MemoryEntity)
    @Query("SELECT COUNT(*) FROM agent_memories WHERE ownerId = :ownerId AND deletedAtMillis IS NULL")
    suspend fun count(ownerId: String): Int
    @Query("SELECT * FROM agent_memories WHERE ownerId = :ownerId AND deletedAtMillis IS NULL ORDER BY updatedAtMillis DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun rows(ownerId: String, offset: Int, limit: Int): List<MemoryEntity>
    @Query("UPDATE agent_memories SET content = :content, updatedAtMillis = :now, version = version + 1, deletedAtMillis = :deletedAt WHERE ownerId = :ownerId AND id = :id AND version = :version AND deletedAtMillis IS NULL")
    suspend fun change(ownerId: String, id: String, version: Int, content: String?, now: Long, deletedAt: Long?): Int

    /** 只做本机预览；真正发送前必须再次验证同一组版本，不能把预览当作上传许可。 */
    @Transaction
    suspend fun selected(ownerId: String, refs: List<MemoryRef>): MemoryPreview {
        require(ownerId.isNotBlank() && refs.size in 1..5 && refs.map { it.id }.distinct().size == refs.size)
        return MemoryPreview(refs.map { ref ->
            MemoryCommands.id(ref.id)
            require(ref.version > 0)
            val row = find(ownerId, ref.id) ?: throw MemoryConflict()
            if (row.deletedAtMillis != null || row.content == null || row.version != ref.version || row.source != "user_explicit") throw MemoryConflict()
            check(MemoryCommands.content(row.content) == row.content)
            row.record()
        })
    }

    @Transaction
    suspend fun page(ownerId: String, offset: Int, limit: Int): MemoryPage {
        require(ownerId.isNotBlank() && offset >= 0 && limit in 1..50)
        return MemoryPage(offset, limit, count(ownerId), rows(ownerId, offset, limit).map { it.record() })
    }

    @Transaction
    suspend fun create(ownerId: String, id: String, content: String, now: Long): MemoryRecord {
        validate(ownerId, id, now)
        val text = MemoryCommands.content(content)
        val existing = find(ownerId, id)
        if (existing != null) {
            if (existing.deletedAtMillis != null || existing.version != 1 || existing.content != text) throw MemoryConflict()
            return existing.record()
        }
        val value = MemoryEntity(id, ownerId, text, "user_explicit", now, now, 1)
        insert(value)
        return value.record()
    }

    @Transaction
    suspend fun update(ownerId: String, id: String, version: Int, content: String, now: Long): MemoryRecord? {
        validate(ownerId, id, now)
        require(version in 1 until Int.MAX_VALUE)
        val text = MemoryCommands.content(content)
        val existing = find(ownerId, id) ?: return null
        if (existing.deletedAtMillis != null) throw MemoryConflict()
        if (existing.version == version + 1 && existing.content == text) return existing.record()
        if (existing.version != version) throw MemoryConflict()
        if (change(ownerId, id, version, text, maxOf(now, existing.updatedAtMillis), null) != 1) throw MemoryConflict()
        return checkNotNull(find(ownerId, id)).record()
    }

    @Transaction
    suspend fun delete(ownerId: String, id: String, version: Int, now: Long): MemoryDeleted? {
        validate(ownerId, id, now)
        require(version in 1 until Int.MAX_VALUE)
        val existing = find(ownerId, id) ?: return null
        if (existing.deletedAtMillis != null) {
            if (existing.version != version + 1) throw MemoryConflict()
            return MemoryDeleted(id)
        }
        if (existing.version != version) throw MemoryConflict()
        val time = maxOf(now, existing.updatedAtMillis)
        // 清空正文，仅留无正文标记，防止迟到创建/编辑将删除内容恢复。
        if (change(ownerId, id, version, null, time, time) != 1) throw MemoryConflict()
        return MemoryDeleted(id)
    }

    private fun validate(ownerId: String, id: String, now: Long) {
        require(ownerId.isNotBlank() && now in 0..8_640_000_000_000_000L)
        MemoryCommands.id(id)
    }
}
