package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryCommands
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.longOrNull

/** 纯本地协议原型；调用方必须先持久化领取后的快照，再执行动作。没有网络或存储接线。 */
object ConversationSummaryProtocol {
    @Serializable enum class State { PREPARED, ATTEMPTED, COMPLETED, FAILED, CANCELLED, INVALID }
    enum class Action { SUBMIT, QUERY }
    @Serializable enum class Cost { NOT_DISPATCHED, UNCERTAIN, USAGE }

    @Serializable data class Usage(val inputTokens: Long, val outputTokens: Long) {
        init { require(inputTokens >= 0 && outputTokens >= 0) }
    }

    @Serializable data class Snapshot(
        val requestId: String,
        val fingerprint: String,
        val state: State,
        val cost: Cost,
        val usage: Usage? = null,
        val summary: String? = null,
    ) {
        init {
            MemoryCommands.id(requestId)
            require(fingerprint.matches(Regex("[0-9a-f]{64}")))
            require((cost == Cost.USAGE) == (usage != null))
            require((state == State.COMPLETED) == (summary != null))
            require(summary == null || CloudTaskIntent.supportedPrompt(summary, 2000))
            require(state != State.PREPARED || cost == Cost.NOT_DISPATCHED)
            require(state != State.COMPLETED || cost == Cost.USAGE)
        }
        override fun toString() = "Snapshot(摘要及来源已隐藏)"
    }

    data class Step(val snapshot: Snapshot, val action: Action?)

    @Serializable private data class Envelope(val version: Int, val snapshot: Snapshot)
    private val format = Json { encodeDefaults = true }

    fun encode(snapshot: Snapshot): String = format.encodeToString(Envelope(1, snapshot))

    /** 损坏或不兼容时抛错；调用方不得用新准备态替代读取失败。 */
    fun decode(text: String): Snapshot {
        require(text.length <= 16000)
        val root = format.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("快照格式无效")
        require(root["version"] == JsonPrimitive(1))
        val snapshot = root["snapshot"] as? JsonObject ?: throw IllegalArgumentException("快照格式无效")
        val usage = snapshot["usage"]
        if (usage != null && usage != JsonNull) {
            require(usage is JsonObject)
            for (field in listOf("inputTokens", "outputTokens")) {
                val number = usage[field] as? JsonPrimitive ?: throw IllegalArgumentException("用量格式无效")
                require(!number.isString && number.longOrNull?.let { it >= 0 } == true)
            }
        }
        val envelope = format.decodeFromJsonElement<Envelope>(root)
        require(envelope.version == 1)
        return envelope.snapshot
    }

    sealed interface Result {
        data class Completed(val text: String, val usage: Usage) : Result {
            override fun toString() = "Completed(摘要已隐藏)"
        }
        data class Failed(val usage: Usage? = null) : Result
        data class Withdrawn(val remoteFinished: Boolean, val usage: Usage? = null) : Result
        data object Unknown : Result
        data object NotDispatched : Result
    }

    fun prepare(requestId: String, candidate: ConversationSummaryPolicy.Candidate) =
        Snapshot(requestId, candidate.fingerprint, State.PREPARED, Cost.NOT_DISPATCHED)

    /** 只能对最新的权威快照调用；实际并发领取必须由未来存储层条件更新保护。 */
    fun next(snapshot: Snapshot, current: ConversationSummaryPolicy.Candidate?): Step {
        if (current?.fingerprint != snapshot.fingerprint) return Step(invalidate(snapshot), null)
        return when (snapshot.state) {
            State.PREPARED -> Step(snapshot.copy(state = State.ATTEMPTED, cost = Cost.UNCERTAIN), Action.SUBMIT)
            State.ATTEMPTED -> Step(snapshot, Action.QUERY)
            else -> Step(snapshot, null)
        }
    }

    fun cancel(snapshot: Snapshot) = snapshot.copy(state = State.CANCELLED, summary = null)

    private fun invalidate(snapshot: Snapshot) = snapshot.copy(state = State.INVALID, summary = null)

    /** 回执必须绑定原请求和输入；失效只影响正文可用性，不抹掉已发生的用量。 */
    fun receive(snapshot: Snapshot, requestId: String, fingerprint: String,
        current: ConversationSummaryPolicy.Candidate?, result: Result): Snapshot {
        if (requestId != snapshot.requestId || fingerprint != snapshot.fingerprint) return snapshot
        if (snapshot.state == State.COMPLETED && result !is Result.Withdrawn) return if (current?.fingerprint == snapshot.fingerprint)
            snapshot else invalidate(snapshot)
        if (snapshot.cost == Cost.NOT_DISPATCHED) return snapshot
        val usage = when (result) {
            is Result.Completed -> result.usage
            is Result.Failed -> result.usage
            is Result.Withdrawn -> result.usage
            else -> null
        }
        val settled = when {
            snapshot.cost == Cost.USAGE -> snapshot
            usage != null -> snapshot.copy(cost = Cost.USAGE, usage = usage)
            result == Result.NotDispatched -> snapshot.copy(cost = Cost.NOT_DISPATCHED)
            else -> snapshot
        }
        if (result is Result.Withdrawn) return cancel(settled)
        if (snapshot.state in setOf(State.CANCELLED, State.INVALID, State.FAILED)) return settled
        if (current?.fingerprint != snapshot.fingerprint) return invalidate(settled)
        return when (result) {
            is Result.Completed -> if (CloudTaskIntent.supportedPrompt(result.text, 2000))
                settled.copy(state = State.COMPLETED, summary = result.text)
                else settled.copy(state = State.FAILED)
            is Result.Failed, Result.NotDispatched -> settled.copy(state = State.FAILED)
            is Result.Withdrawn -> cancel(settled)
            Result.Unknown -> settled
        }
    }
}
