package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryContextState
import com.healthyagent.running.agent.memory.MemoryRef
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.*

/** 仅处理宿主已筛选的有效回合，不读取数据库、不发请求、不授予权限。 */
object ConversationSummaryPolicy {
    data class Turn(val requestId: String, val prompt: String, val answer: String, val dependencies: MemoryContextState) {
        init {
            MemoryCommands.id(requestId)
            require(CloudTaskIntent.supportedPrompt(prompt) && CloudTaskIntent.supportedPrompt(answer, 4000))
            require(!dependencies.exhausted)
        }
        override fun toString() = "Turn(正文已隐藏)"
    }

    data class Scope(val identity: CloudTaskIdentity, val conversationId: String, val chatGeneration: String, val memoryGeneration: String?) {
        init { MemoryCommands.id(conversationId); require(chatGeneration.isNotBlank()); require(memoryGeneration == null || memoryGeneration.isNotBlank()) }
        override fun toString() = "Scope(身份已隐藏)"
    }

    class Candidate internal constructor(val fingerprint: String, sources: List<Turn>, dependencies: Set<MemoryRef>) {
        val sources: List<Turn> = Collections.unmodifiableList(ArrayList(sources))
        val sourceIds: List<String> = Collections.unmodifiableList(sources.map { it.requestId })
        val dependencies: Set<MemoryRef> = Collections.unmodifiableSet(HashSet(dependencies))
        override fun toString() = "Candidate(覆盖与依赖已隐藏)"
    }
    data class Plan(val recentIds: List<String>, val candidate: Candidate?, val uncoveredIds: List<String>)

    /** 输入按时间从旧到新；字符限制按完整问答计，不截断、不跳过超预算的新回合。 */
    fun plan(scope: Scope, turns: List<Turn>, validReferences: Set<MemoryRef>): Plan {
        // 指纹与生成正文使用同一份快照，不保留调用方的可变依赖集合。
        val snapshot = turns.map { turn ->
            turn.copy(dependencies = turn.dependencies.copy(refs = Collections.unmodifiableSet(HashSet(turn.dependencies.refs))))
        }
        require(snapshot.map { it.requestId }.distinct().size == snapshot.size)
        require(snapshot.all { validReferences.containsAll(it.dependencies.refs) })
        fun takeWindow(rows: List<Turn>, limit: Int): List<Turn> {
            val result = mutableListOf<Turn>()
            var chars = 0
            for (turn in rows.asReversed()) {
                val size = turn.prompt.length + turn.answer.length
                if (result.size == limit || chars + size > 12000) break
                result += turn; chars += size
            }
            return result.asReversed()
        }
        val recent = takeWindow(snapshot, 4)
        val older = snapshot.dropLast(recent.size)
        val sources = takeWindow(older, 8)
        val refs = sources.fold(MemoryContextState()) { state, turn -> state.merge(turn.dependencies.refs) }
        val candidate = if (sources.isEmpty() || refs.exhausted) null else {
            fun encoded(rows: List<Turn>) = JsonArray(rows.map { turn ->
                buildJsonArray {
                    add(turn.requestId); add(turn.prompt); add(turn.answer)
                    add(JsonArray(turn.dependencies.refs.sortedWith(compareBy<MemoryRef> { it.id }.thenBy { it.version }).map {
                        buildJsonArray { add(it.id); add(it.version) }
                    }))
                }
            })
            val input = buildJsonArray {
                add("conversation_summary_policy_v1")
                add(scope.identity.origin); add(scope.identity.ownerId); add(scope.identity.deviceId); add(scope.identity.credentialRef)
                add(scope.conversationId); add(scope.chatGeneration)
                add(scope.memoryGeneration?.let(::JsonPrimitive) ?: JsonNull)
                add(encoded(sources)); add(encoded(recent))
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            Candidate(hash, sources, refs.refs)
        }
        val covered = if (candidate == null) emptySet() else candidate.sourceIds.toSet()
        return Plan(recent.map { it.requestId }, candidate, older.map { it.requestId }.filterNot { it in covered })
    }

    /** 单次合成结果的内存接纳门；调用方必须提供使用时重新校验后的当前候选。 */
    class Attempt(candidate: Candidate, private val attemptId: String, private val deadline: Long) {
        private val fingerprint = candidate.fingerprint
        private var closed = false
        init { MemoryCommands.id(attemptId); require(deadline >= 0) }
        @Synchronized fun cancel() { closed = true }
        @Synchronized fun accept(current: Candidate?, resultAttemptId: String, text: String, monotonicNow: Long): String? {
            if (closed) return null
            if (monotonicNow < 0 || monotonicNow >= deadline || current?.fingerprint != fingerprint) {
                closed = true; return null
            }
            if (resultAttemptId != attemptId) return null
            closed = true
            return text.takeIf { CloudTaskIntent.supportedPrompt(it, 2000) }
        }
    }
}
