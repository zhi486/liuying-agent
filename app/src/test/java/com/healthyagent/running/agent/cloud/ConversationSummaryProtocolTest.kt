package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryContextState
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ConversationSummaryProtocolTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val conversation = UUID.randomUUID().toString()
    private val turns = (1..5).map {
        ConversationSummaryPolicy.Turn(UUID.randomUUID().toString(), "合成问题$it", "合成回答$it", MemoryContextState())
    }
    private fun candidate(generation: String = "grant") = ConversationSummaryPolicy.plan(
        ConversationSummaryPolicy.Scope(identity, conversation, generation, null), turns, emptySet()).candidate!!
    private val policy = ConversationSummaryProtocol
    private fun prepared() = policy.prepare(UUID.randomUUID().toString(), candidate())
    private fun attempted() = policy.next(prepared(), candidate()).snapshot

    @Test fun 远端撤回清空已完成正文且迟到完成不恢复() {
        val initial = attempted()
        val usage = ConversationSummaryProtocol.Usage(7, 2)
        val completed = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Completed("合成摘要", usage))
        val withdrawn = policy.receive(completed, initial.requestId, initial.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Withdrawn(false))
        assertEquals(ConversationSummaryProtocol.State.CANCELLED, withdrawn.state)
        assertNull(withdrawn.summary)
        assertEquals(usage, withdrawn.usage)
        val late = policy.receive(withdrawn, initial.requestId, initial.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Completed("不得恢复", usage))
        assertEquals(withdrawn, late)
    }

    @Test fun 版本化快照往返保留终态正文和用量且恢复尝试只查询() {
        val initial = attempted()
        val done = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Completed("合成摘要\n第二行", ConversationSummaryProtocol.Usage(20, 4)))
        for (snapshot in listOf(prepared(), initial, done, policy.cancel(initial))) {
            assertEquals(snapshot, policy.decode(policy.encode(snapshot)))
        }
        assertEquals(ConversationSummaryProtocol.Action.QUERY,
            policy.next(policy.decode(policy.encode(initial)), candidate()).action)
    }

    @Test fun 损坏快照未知字段版本和非法状态拒绝恢复() {
        val encoded = policy.encode(prepared())
        val invalid = listOf(
            "{", "x".repeat(16001),
            encoded.replace("\"version\":1", "\"version\":2"),
            encoded.replace("\"version\":1", "\"version\":\"1\""),
            encoded.replace("\"version\":1", "\"extra\":true,\"version\":1"),
            encoded.replace("PREPARED", "UNKNOWN"),
            encoded.replace("NOT_DISPATCHED", "UNCERTAIN"),
        )
        invalid.forEach { text ->
            try { policy.decode(text); fail("损坏快照应拒绝：$text") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun 已完成回执重放仍复核授权且保留原用量() {
        val initial = attempted()
        val result = ConversationSummaryProtocol.Result.Completed("合成摘要", ConversationSummaryProtocol.Usage(12, 3))
        val done = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(), result)
        for (current in listOf(null, candidate("renewed"))) {
            val invalid = policy.receive(done, done.requestId, done.fingerprint, current, result)
            assertEquals(ConversationSummaryProtocol.State.INVALID, invalid.state)
            assertNull(invalid.summary)
            assertEquals(result.usage, invalid.usage)
        }
    }

    @Test fun 用量字段拒绝字符串布尔和小数而不静默转换() {
        val initial = attempted()
        val done = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Completed("合成摘要", ConversationSummaryProtocol.Usage(20, 4)))
        val encoded = policy.encode(done)
        for (field in listOf("inputTokens", "outputTokens")) {
            val number = if (field == "inputTokens") "20" else "4"
            for (invalid in listOf("\"$number\"", "true", "1.5")) {
                val text = encoded.replace("\"$field\":$number", "\"$field\":$invalid")
                assertNotEquals(encoded, text)
                try { policy.decode(text); fail("用量类型应拒绝：$field=$invalid") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun 恢复快照拒绝完成但无实际用量及其他非法组合() {
        val initial = prepared()
        val invalid = listOf<() -> Unit>(
            { initial.copy(state = ConversationSummaryProtocol.State.COMPLETED, summary = "不合法摘要") },
            { initial.copy(cost = ConversationSummaryProtocol.Cost.USAGE) },
            { initial.copy(summary = "准备态不应有正文") },
            { initial.copy(cost = ConversationSummaryProtocol.Cost.UNCERTAIN) },
            { ConversationSummaryProtocol.Usage(-1, 0) },
        )
        invalid.forEach { build ->
            try { build(); fail("非法快照应拒绝") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun 提交失联恢复快照后只查询原请求() {
        val initial = prepared()
        val first = policy.next(initial, candidate())
        assertEquals(ConversationSummaryProtocol.Action.SUBMIT, first.action)
        val unknown = policy.receive(first.snapshot, initial.requestId, initial.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Unknown)
        val restored = unknown.copy()
        repeat(3) {
            val next = policy.next(restored, candidate())
            assertEquals(ConversationSummaryProtocol.Action.QUERY, next.action)
            assertEquals(initial.requestId, next.snapshot.requestId)
            assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN, next.snapshot.cost)
        }
    }

    @Test fun 重复和其他请求回执不覆盖结果() {
        val initial = attempted()
        val result = ConversationSummaryProtocol.Result.Completed("合成摘要", ConversationSummaryProtocol.Usage(100, 20))
        assertEquals(initial, policy.receive(initial, UUID.randomUUID().toString(), initial.fingerprint, candidate(), result))
        assertEquals(initial, policy.receive(initial, initial.requestId, candidate("new").fingerprint, candidate(), result))
        val done = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(), result)
        assertEquals("合成摘要", done.summary)
        assertNull(policy.next(done, candidate()).action)
        assertEquals(done, policy.receive(done, done.requestId, done.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Completed("重复覆盖", ConversationSummaryProtocol.Usage(1, 1))))
    }

    @Test fun 撤权来源变化和取消拒绝迟到正文但保留实际用量() {
        val initial = attempted()
        val result = ConversationSummaryProtocol.Result.Completed("迟到摘要", ConversationSummaryProtocol.Usage(90, 10))
        for (current in listOf(null, candidate("renewed"))) {
            val invalid = policy.receive(initial, initial.requestId, initial.fingerprint, current, result)
            assertEquals(ConversationSummaryProtocol.State.INVALID, invalid.state)
            assertNull(invalid.summary)
            assertEquals(result.usage, invalid.usage)
            assertNull(policy.next(invalid, candidate()).action)
        }
        val cancelled = policy.receive(policy.cancel(initial), initial.requestId, initial.fingerprint, candidate(), result)
        assertEquals(ConversationSummaryProtocol.State.CANCELLED, cancelled.state)
        assertEquals(result.usage, cancelled.usage)
        assertNull(cancelled.summary)
    }

    @Test fun 未发送和未知费用不混为零且失败不自动重试() {
        val initial = attempted()
        val failed = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(), ConversationSummaryProtocol.Result.Failed())
        assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN, failed.cost)
        assertNull(policy.next(failed, candidate()).action)
        val reconciled = policy.receive(failed, failed.requestId, failed.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Completed("不能恢复正文", ConversationSummaryProtocol.Usage(8, 9)))
        assertEquals(ConversationSummaryProtocol.State.FAILED, reconciled.state)
        assertNull(reconciled.summary)
        assertEquals(ConversationSummaryProtocol.Usage(8, 9), reconciled.usage)
        val notSent = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(), ConversationSummaryProtocol.Result.NotDispatched)
        assertEquals(ConversationSummaryProtocol.Cost.NOT_DISPATCHED, notSent.cost)
        assertNull(policy.next(notSent, candidate()).action)
    }

    @Test fun 非法摘要仍保留用量且准备态不接受回执() {
        val result = ConversationSummaryProtocol.Result.Completed("x".repeat(2001), ConversationSummaryProtocol.Usage(10, 30))
        val prepared = prepared()
        assertEquals(prepared, policy.receive(prepared, prepared.requestId, prepared.fingerprint, candidate(), result))
        val initial = attempted()
        val rejected = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(), result)
        assertEquals(ConversationSummaryProtocol.State.FAILED, rejected.state)
        assertNull(rejected.summary)
        assertEquals(result.usage, rejected.usage)
    }

    @Test fun 来源正文变化拒绝旧结果且新请求不被旧回执覆盖() {
        val old = attempted()
        val changed = ConversationSummaryPolicy.plan(
            ConversationSummaryPolicy.Scope(identity, conversation, "grant", null),
            turns.mapIndexed { index, turn -> if (index == 0) turn.copy(answer = "更正合成回答") else turn },
            emptySet()).candidate!!
        assertNotEquals(old.fingerprint, changed.fingerprint)
        val result = ConversationSummaryProtocol.Result.Completed("旧结果", ConversationSummaryProtocol.Usage(1, 2))
        assertNull(policy.receive(old, old.requestId, old.fingerprint, changed, result).summary)
        val fresh = policy.next(policy.prepare(UUID.randomUUID().toString(), changed), changed).snapshot
        assertEquals(fresh, policy.receive(fresh, old.requestId, old.fingerprint, changed, result))
    }

    @Test fun 发送前取消不计用量且已知用量不会被迟到未知覆盖() {
        val cancelled = policy.cancel(prepared())
        val result = ConversationSummaryProtocol.Result.Completed("不应接纳", ConversationSummaryProtocol.Usage(5, 6))
        assertEquals(cancelled, policy.receive(cancelled, cancelled.requestId, cancelled.fingerprint, candidate(), result))
        assertEquals(ConversationSummaryProtocol.Cost.NOT_DISPATCHED, cancelled.cost)
        val initial = policy.cancel(attempted())
        val settled = policy.receive(initial, initial.requestId, initial.fingerprint, candidate(), result)
        assertEquals(settled, policy.receive(settled, settled.requestId, settled.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.Unknown))
        assertEquals(settled, policy.receive(settled, settled.requestId, settled.fingerprint, candidate(),
            ConversationSummaryProtocol.Result.NotDispatched))
    }

    /** 仅合成传输；存储替身独立于调用方，模拟重建后加载已保存快照。 */
    private class SyntheticStore(initial: ConversationSummaryProtocol.Snapshot, var failSave: Boolean = false) {
        private var encoded = ConversationSummaryProtocol.encode(initial)
        val saved: ConversationSummaryProtocol.Snapshot get() = ConversationSummaryProtocol.decode(encoded)
        fun save(value: ConversationSummaryProtocol.Snapshot) {
            check(!failSave) { "合成保存失败" }
            encoded = ConversationSummaryProtocol.encode(value)
        }
    }

    private class SyntheticTransport {
        val submissions = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var disconnectAfterSubmit = false
        var beforeReply: () -> Unit = {}
        var result: ConversationSummaryProtocol.Result = ConversationSummaryProtocol.Result.Unknown
        fun execute(action: ConversationSummaryProtocol.Action, requestId: String): ConversationSummaryProtocol.Result {
            when (action) {
                ConversationSummaryProtocol.Action.SUBMIT -> {
                    submissions += requestId
                    check(!disconnectAfterSubmit) { "合成发送后断线" }
                }
                ConversationSummaryProtocol.Action.QUERY -> queries += requestId
            }
            beforeReply()
            return result
        }
    }

    private class SyntheticClient(private val store: SyntheticStore, private val transport: SyntheticTransport) {
        fun advance(current: ConversationSummaryPolicy.Candidate?) = advance { current }

        fun advance(current: () -> ConversationSummaryPolicy.Candidate?) {
            val step = ConversationSummaryProtocol.next(store.saved, current())
            store.save(step.snapshot)
            val action = step.action ?: return
            val result = transport.execute(action, step.snapshot.requestId)
            store.save(ConversationSummaryProtocol.receive(store.saved, step.snapshot.requestId,
                step.snapshot.fingerprint, current(), result))
        }
    }

    @Test fun 合成传输发送后断线重建调用方仅查询且完成后不再发网() {
        val store = SyntheticStore(prepared())
        val transport = SyntheticTransport().apply { disconnectAfterSubmit = true }
        try {
            SyntheticClient(store, transport).advance(candidate())
            fail("应模拟发送后断线")
        } catch (_: IllegalStateException) { }
        assertEquals(ConversationSummaryProtocol.State.ATTEMPTED, store.saved.state)
        assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN, store.saved.cost)
        SyntheticClient(store, transport).advance(candidate())
        transport.result = ConversationSummaryProtocol.Result.Completed("恢复查询摘要", ConversationSummaryProtocol.Usage(20, 10))
        SyntheticClient(store, transport).advance(candidate())
        SyntheticClient(store, transport).advance(candidate())
        assertEquals(listOf(store.saved.requestId), transport.submissions)
        assertEquals(listOf(store.saved.requestId, store.saved.requestId), transport.queries)
        assertEquals("恢复查询摘要", store.saved.summary)
    }

    @Test fun 合成保存失败不提交且领取后未发送的恢复也不重发() {
        val store = SyntheticStore(prepared(), failSave = true)
        val transport = SyntheticTransport()
        try {
            SyntheticClient(store, transport).advance(candidate())
            fail("应模拟保存失败")
        } catch (_: IllegalStateException) { }
        assertTrue(transport.submissions.isEmpty())
        store.failSave = false
        store.save(policy.next(store.saved, candidate()).snapshot)
        transport.result = ConversationSummaryProtocol.Result.NotDispatched
        SyntheticClient(store, transport).advance(candidate())
        SyntheticClient(store, transport).advance(candidate())
        assertTrue(transport.submissions.isEmpty())
        assertEquals(listOf(store.saved.requestId), transport.queries)
        assertEquals(ConversationSummaryProtocol.State.FAILED, store.saved.state)
        assertEquals(ConversationSummaryProtocol.Cost.NOT_DISPATCHED, store.saved.cost)
    }

    @Test fun 合成查询途中撤权拒绝正文但落下实际用量且不继续请求() {
        val store = SyntheticStore(attempted())
        var current: ConversationSummaryPolicy.Candidate? = candidate()
        val transport = SyntheticTransport().apply {
            result = ConversationSummaryProtocol.Result.Completed("撤权后迟到摘要", ConversationSummaryProtocol.Usage(30, 8))
            beforeReply = { current = null }
        }
        SyntheticClient(store, transport).advance { current }
        assertEquals(ConversationSummaryProtocol.State.INVALID, store.saved.state)
        assertNull(store.saved.summary)
        assertEquals(ConversationSummaryProtocol.Usage(30, 8), store.saved.usage)
        SyntheticClient(store, transport).advance { current }
        assertEquals(listOf(store.saved.requestId), transport.queries)
        assertTrue(transport.submissions.isEmpty())
    }
}
