package com.healthyagent.running.agent.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.serialization.Serializable
import java.util.UUID

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
    @Query("SELECT * FROM agent_memories WHERE ownerId = :ownerId AND deletedAtMillis IS NULL AND source = 'user_explicit' AND instr(lower(content), lower(:query)) > 0 ORDER BY updatedAtMillis DESC, id DESC LIMIT 5")
    suspend fun search(ownerId: String, query: String): List<MemoryEntity>
    @Query("SELECT * FROM agent_memory_tool_receipts WHERE ownerId = :ownerId AND operationId = :operationId")
    suspend fun toolReceipt(ownerId: String, operationId: String): MemoryToolReceipt?
    @Query("SELECT EXISTS(SELECT 1 FROM agent_memory_tool_receipts AS r INNER JOIN agent_memories AS m ON m.id = r.memoryId AND m.ownerId = r.ownerId WHERE r.ownerId = :ownerId AND r.turnId = :turnId AND m.version = r.memoryVersion AND m.deletedAtMillis IS NULL AND m.source = 'user_explicit')")
    suspend fun hasCurrentToolSave(ownerId: String, turnId: String): Boolean
    @Insert suspend fun insertToolReceipt(receipt: MemoryToolReceipt)
    @Query("SELECT MAX(m.deletedAtMillis) FROM agent_memory_tool_receipts AS r INNER JOIN agent_memories AS m ON m.id = r.memoryId AND m.ownerId = r.ownerId WHERE r.ownerId = :ownerId AND r.contentHash = :contentHash")
    suspend fun toolDeletedAt(ownerId: String, contentHash: String): Long?
    @Query("SELECT deletedAtMillis FROM agent_memory_deletion_markers WHERE ownerId = :ownerId AND contentHash = :contentHash")
    suspend fun deletionTime(ownerId: String, contentHash: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeletionMarker(marker: MemoryDeletionMarker)
    @Query("SELECT DISTINCT content FROM agent_memory_revisions WHERE ownerId = :ownerId AND memoryId = :id")
    suspend fun revisionContents(ownerId: String, id: String): List<String>
    @Query("SELECT * FROM agent_memories WHERE ownerId = :ownerId AND deletedAtMillis IS NULL AND source = 'user_explicit' AND content = :content LIMIT 1")
    suspend fun matchingContent(ownerId: String, content: String): MemoryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevision(revision: MemoryRevision): Long
    @Query("SELECT * FROM agent_memory_revisions WHERE ownerId = :ownerId AND memoryId = :id AND version = :version")
    suspend fun revision(ownerId: String, id: String, version: Int): MemoryRevision?
    @Query("SELECT * FROM agent_memory_revisions WHERE ownerId = :ownerId AND memoryId = :id ORDER BY version DESC LIMIT :limit OFFSET :offset")
    suspend fun revisionRows(ownerId: String, id: String, offset: Int, limit: Int): List<MemoryRevision>
    @Query("SELECT COUNT(*) FROM agent_memory_revisions WHERE ownerId = :ownerId AND memoryId = :id")
    suspend fun revisionCount(ownerId: String, id: String): Int
    @Query("DELETE FROM agent_memory_revisions WHERE ownerId = :ownerId AND memoryId = :id")
    suspend fun clearRevisions(ownerId: String, id: String)

    /** 记忆变更与操作回执同一事务；失败或撤权不留下半次成功。 */
    @Transaction
    suspend fun upsertFromTool(
        turn: MemoryToolTurn, operationId: String, requestHash: String,
        call: MemoryToolCall.Upsert, now: Long, authorize: () -> Unit,
    ): MemoryToolResult.Saved {
        authorize()
        val receipt = toolReceipt(turn.ownerId, operationId)
        if (receipt != null) {
            if (receipt.requestHash != requestHash) throw MemoryConflict()
            val current = find(turn.ownerId, receipt.memoryId) ?: throw MemoryConflict()
            if (current.deletedAtMillis != null || current.version != receipt.memoryVersion || current.content != call.evidence)
                throw MemoryConflict()
            authorize()
            return MemoryToolResult.Saved(current.record(), true)
        }
        val contentHash = MemoryToolPolicy.hash(call.evidence)
        val deletedAt = listOfNotNull(toolDeletedAt(turn.ownerId, contentHash), deletionTime(turn.ownerId, contentHash)).maxOrNull()
        if (deletedAt != null && (!turn.userText.trim().startsWith("请记住：") || turn.startedAtMillis <= deletedAt))
            throw MemoryConflict()
        val memory = if (call.target == null) {
            matchingContent(turn.ownerId, call.evidence)?.record()
                ?: create(turn.ownerId, UUID.randomUUID().toString(), call.evidence, now, "agent", turn.turnId)
        } else {
            val current = find(turn.ownerId, call.target.id) ?: throw MemoryConflict()
            // 自动更新不沿用手动编辑的宽松重试规则，只有同一工具回执才允许幂等返回。
            if (current.version != call.target.version || current.deletedAtMillis != null || current.source != "user_explicit") throw MemoryConflict()
            update(turn.ownerId, call.target.id, call.target.version, call.evidence, now, "agent", turn.turnId) ?: throw MemoryConflict()
        }
        authorize()
        insertToolReceipt(MemoryToolReceipt(operationId, turn.ownerId, requestHash, contentHash, turn.turnId,
            call.category, memory.id, memory.version))
        authorize()
        return MemoryToolResult.Saved(memory, false)
    }

    @Query("SELECT * FROM agent_memories WHERE ownerId = :ownerId AND id = :id")
    suspend fun find(ownerId: String, id: String): MemoryEntity?
    @Insert suspend fun insert(memory: MemoryEntity)
    @Query("SELECT COUNT(*) FROM agent_memories WHERE ownerId = :ownerId AND deletedAtMillis IS NULL")
    suspend fun count(ownerId: String): Int
    @Query("SELECT * FROM agent_memories WHERE ownerId = :ownerId AND deletedAtMillis IS NULL ORDER BY updatedAtMillis DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun rows(ownerId: String, offset: Int, limit: Int): List<MemoryEntity>
    // 单条查询取得正文及同版本来源，不能把分页读取期间的不同版本拼成一份文档。
    @Query("""
        SELECT m.id, m.content, m.source, m.createdAtMillis, m.updatedAtMillis, m.version,
            v.changeSource AS changeSource, v.turnId AS sourceTurnId,
            (SELECT r.category FROM agent_memory_tool_receipts AS r
             WHERE r.ownerId = m.ownerId AND r.memoryId = m.id AND r.memoryVersion = m.version
                AND r.turnId = v.turnId AND v.changeSource = 'agent'
             ORDER BY r.operationId LIMIT 1) AS category
        FROM agent_memories AS m
        LEFT JOIN agent_memory_revisions AS v
            ON v.ownerId = m.ownerId AND v.memoryId = m.id AND v.version = m.version
        WHERE m.ownerId = :ownerId AND m.deletedAtMillis IS NULL
            AND m.source = 'user_explicit' AND m.content IS NOT NULL
        ORDER BY m.updatedAtMillis DESC, m.id DESC
    """)
    suspend fun documentRows(ownerId: String): List<MemoryDocumentEntry>

    suspend fun document(ownerId: String, generatedAtMillis: Long): MemoryDocument {
        require(ownerId.isNotBlank() && generatedAtMillis in 0..8_640_000_000_000_000L)
        return MemoryDocument(generatedAtMillis, documentRows(ownerId))
    }

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
    suspend fun create(ownerId: String, id: String, content: String, now: Long,
        changeSource: String = "manual", turnId: String? = null): MemoryRecord {
        validate(ownerId, id, now)
        validateSource(changeSource, turnId)
        val text = MemoryCommands.content(content)
        val existing = find(ownerId, id)
        if (existing != null) {
            if (existing.deletedAtMillis != null || existing.version != 1 || existing.content != text) throw MemoryConflict()
            return existing.record()
        }
        val value = MemoryEntity(id, ownerId, text, "user_explicit", now, now, 1)
        insert(value)
        snapshot(value, changeSource, turnId)
        return value.record()
    }

    @Transaction
    suspend fun update(ownerId: String, id: String, version: Int, content: String, now: Long,
        changeSource: String = "manual", turnId: String? = null): MemoryRecord? {
        validate(ownerId, id, now)
        validateSource(changeSource, turnId)
        require(version in 1 until Int.MAX_VALUE)
        val text = MemoryCommands.content(content)
        val existing = find(ownerId, id) ?: return null
        if (existing.deletedAtMillis != null) throw MemoryConflict()
        if (existing.version == version + 1 && existing.content == text) return existing.record()
        if (existing.version != version) throw MemoryConflict()
        snapshot(existing, "legacy", null)
        if (change(ownerId, id, version, text, maxOf(now, existing.updatedAtMillis), null) != 1) throw MemoryConflict()
        val updated = checkNotNull(find(ownerId, id))
        snapshot(updated, changeSource, turnId)
        return updated.record()
    }

    @Transaction
    suspend fun history(ownerId: String, id: String, offset: Int, limit: Int): MemoryHistoryPage? {
        require(ownerId.isNotBlank() && offset >= 0 && limit in 1..50)
        MemoryCommands.id(id)
        val current = find(ownerId, id)?.takeIf { it.deletedAtMillis == null } ?: return null
        return MemoryHistoryPage(id, current.version, offset, limit, revisionCount(ownerId, id),
            revisionRows(ownerId, id, offset, limit).map { it.record() })
    }

    /** 恢复生成新版本，保留审计顺序；已删除的记忆不允许复活。 */
    @Transaction
    suspend fun restore(ownerId: String, id: String, version: Int, targetVersion: Int, now: Long): MemoryRecord? {
        validate(ownerId, id, now)
        require(version in 1 until Int.MAX_VALUE && targetVersion in 1 until version)
        val existing = find(ownerId, id) ?: return null
        if (existing.deletedAtMillis != null) throw MemoryConflict()
        val target = revision(ownerId, id, targetVersion) ?: throw MemoryConflict()
        return update(ownerId, id, version, target.content, now, "restored")
    }

    @Transaction
    suspend fun delete(ownerId: String, id: String, version: Int, now: Long): MemoryDeleted? {
        validate(ownerId, id, now)
        require(version in 1 until Int.MAX_VALUE)
        val existing = find(ownerId, id) ?: return null
        if (existing.deletedAtMillis != null) {
            if (existing.version != version + 1) throw MemoryConflict()
            clearRevisions(ownerId, id)
            return MemoryDeleted(id)
        }
        if (existing.version != version) throw MemoryConflict()
        val time = maxOf(now, existing.updatedAtMillis)
        // 历史正文即将清除，先在同一事务留下指纹，防止迟到工具换新 ID 恢复手工版本。
        for (hash in (revisionContents(ownerId, id) + listOfNotNull(existing.content)).map(MemoryToolPolicy::hash).distinct()) {
            saveDeletionMarker(MemoryDeletionMarker(ownerId, hash, maxOf(time, deletionTime(ownerId, hash) ?: time)))
        }
        // 清空正文，仅留无正文标记，防止迟到创建/编辑将删除内容恢复。
        if (change(ownerId, id, version, null, time, time) != 1) throw MemoryConflict()
        clearRevisions(ownerId, id)
        return MemoryDeleted(id)
    }

    private suspend fun snapshot(value: MemoryEntity, source: String, turnId: String?) {
        insertRevision(MemoryRevision(value.ownerId, value.id, value.version, checkNotNull(value.content),
            value.updatedAtMillis, source, turnId))
    }

    private fun validateSource(source: String, turnId: String?) {
        require(source in setOf("manual", "agent", "restored"))
        if (source == "agent") MemoryCommands.id(requireNotNull(turnId)) else require(turnId == null)
    }

    private fun validate(ownerId: String, id: String, now: Long) {
        require(ownerId.isNotBlank() && now in 0..8_640_000_000_000_000L)
        MemoryCommands.id(id)
    }
}
