package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryContextState
import com.healthyagent.running.agent.memory.MemoryRef
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConversationSummaryPolicyTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val scope = ConversationSummaryPolicy.Scope(CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential"), id(99), "chat-1", "memory-1")
    private fun turns(count: Int) = (1..count).map { ConversationSummaryPolicy.Turn(id(it), "问题$it", "回答$it", MemoryContextState()) }
    private fun plan(rows: List<ConversationSummaryPolicy.Turn>, context: ConversationSummaryPolicy.Scope = scope) =
        ConversationSummaryPolicy.plan(context, rows, emptySet())

    @Test fun 四轮内不触发而第五轮产生最早完整问答候选() {
        assertNull(plan(turns(4)).candidate)
        val result = plan(turns(5))
        assertEquals(listOf(id(1)), result.candidate!!.sourceIds)
        assertEquals((2..5).map(::id), result.recentIds)
        assertTrue(result.uncoveredIds.isEmpty())
    }

    @Test fun 只覆盖窗口前最近八轮并明确更早未覆盖范围() {
        val result = plan(turns(15))
        assertEquals((4..11).map(::id), result.candidate!!.sourceIds)
        assertEquals((12..15).map(::id), result.recentIds)
        assertEquals((1..3).map(::id), result.uncoveredIds)
    }

    @Test fun 四轮内字符超限也触发且不截断问答() {
        val rows = turns(3).map { it.copy(prompt = "问".repeat(2000), answer = "答".repeat(4000)) }
        val result = plan(rows)
        assertEquals(listOf(id(1)), result.candidate!!.sourceIds)
        assertEquals(listOf(id(2), id(3)), result.recentIds)
    }

    @Test fun 候选字符预算按整轮停止() {
        val rows = turns(8).map { it.copy(prompt = "问".repeat(2000), answer = "答".repeat(4000)) }
        val result = plan(rows)
        assertEquals(listOf(id(5), id(6)), result.candidate!!.sourceIds)
        assertEquals((1..4).map(::id), result.uncoveredIds)
    }

    @Test fun 候选保留按时间排序的完整来源且不混入近期或未覆盖回合() {
        val rows = turns(15)
        val candidate = plan(rows).candidate!!
        assertEquals(rows.subList(3, 11), candidate.sources)
        assertEquals(candidate.sources.map { it.requestId }, candidate.sourceIds)
    }

    @Test fun 候选正文与依赖不受调用方修改影响且不能从暴露集合修改() {
        val ref = MemoryRef(id(70), 1)
        val refs = mutableSetOf(ref)
        val rows = turns(5).toMutableList()
        rows[0] = rows[0].copy(dependencies = MemoryContextState(refs))
        val candidate = ConversationSummaryPolicy.plan(scope, rows, refs).candidate!!
        val fingerprint = candidate.fingerprint
        refs.clear()
        rows[0] = rows[0].copy(answer = "调用方更改")
        rows.clear()
        assertEquals("回答1", candidate.sources.single().answer)
        assertEquals(setOf(ref), candidate.sources.single().dependencies.refs)
        assertEquals(setOf(ref), candidate.dependencies)
        assertEquals(fingerprint, candidate.fingerprint)
        assertTrue(runCatching { (candidate.sources as MutableList).clear() }.isFailure)
        assertTrue(runCatching { (candidate.sourceIds as MutableList).clear() }.isFailure)
        assertTrue(runCatching { (candidate.dependencies as MutableSet).clear() }.isFailure)
        assertTrue(runCatching { (candidate.sources.single().dependencies.refs as MutableSet).clear() }.isFailure)
    }

    @Test fun 依赖插入顺序不改变指纹但版本变化会改变() {
        val a = MemoryRef(id(70), 1)
        val b = MemoryRef(id(71), 1)
        fun candidate(refs: Set<MemoryRef>) = ConversationSummaryPolicy.plan(scope, turns(5).mapIndexed { i, t ->
            if (i == 0) t.copy(dependencies = MemoryContextState(refs)) else t
        }, refs).candidate!!
        assertEquals(candidate(linkedSetOf(a, b)).fingerprint, candidate(linkedSetOf(b, a)).fingerprint)
        assertNotEquals(candidate(setOf(a, b)).fingerprint, candidate(setOf(a.copy(version = 2), b)).fingerprint)
    }

    @Test fun 指纹确定且原文窗口身份会话授权变化均失效() {
        val rows = turns(5)
        val original = plan(rows).candidate!!.fingerprint
        assertEquals(original, plan(rows).candidate!!.fingerprint)
        assertNotEquals(original, plan(rows.mapIndexed { i, t -> if (i == 0) t.copy(answer = t.answer + " ") else t }).candidate!!.fingerprint)
        assertNotEquals(original, plan(rows + turns(6).last()).candidate!!.fingerprint)
        for (changed in listOf(scope.copy(conversationId = id(98)), scope.copy(chatGeneration = "chat-2"),
            scope.copy(memoryGeneration = null), scope.copy(identity = scope.identity.copy(ownerId = "other")))) {
            assertNotEquals(original, plan(rows, changed).candidate!!.fingerprint)
        }
    }

    @Test fun 间接继承依赖删除或版本改变不能生成可用候选() {
        val ref = MemoryRef(id(70), 1)
        val rows = turns(5).mapIndexed { i, t -> if (i == 0) t.copy(dependencies = MemoryContextState(setOf(ref))) else t }
        val candidate = ConversationSummaryPolicy.plan(scope, rows, setOf(ref)).candidate!!
        assertEquals(setOf(ref), candidate.dependencies)
        assertTrue(runCatching { ConversationSummaryPolicy.plan(scope, rows, emptySet()) }.isFailure)
        assertTrue(runCatching { ConversationSummaryPolicy.plan(scope, rows, setOf(ref.copy(version = 2))) }.isFailure)
    }

    @Test fun 依赖并集耗尽不生成摘要且所有旧回合标为未覆盖() {
        val rows = turns(6).mapIndexed { i, t -> if (i < 2) t.copy(dependencies = MemoryContextState((1..129).map { MemoryRef(id(1000 + i * 129 + it), 1) }.toSet())) else t }
        val result = ConversationSummaryPolicy.plan(scope, rows, rows.flatMap { it.dependencies.refs }.toSet())
        assertNull(result.candidate)
        assertEquals(listOf(id(1), id(2)), result.uncoveredIds)
    }

    @Test fun 重复来源超长文本损坏代理与耗尽来源拒绝() {
        assertTrue(runCatching { plan(turns(5) + turns(1)) }.isFailure)
        for (text in listOf(" ", "问".repeat(2001), "\uD800")) {
            assertTrue(runCatching { turns(1).first().copy(prompt = text) }.isFailure)
        }
        assertTrue(runCatching { turns(1).first().copy(dependencies = MemoryContextState(exhausted = true)) }.isFailure)
    }

    @Test fun 合法结果只接纳一次且错误尝试不能冒用() {
        val current = plan(turns(5)).candidate!!
        val attempt = ConversationSummaryPolicy.Attempt(current, id(50), 100)
        assertNull(attempt.accept(current, id(51), "摘要", 1))
        assertEquals("摘要", attempt.accept(current, id(50), "摘要", 2))
        assertNull(attempt.accept(current, id(50), "摘要", 3))
    }

    @Test fun 摘要字符上限按UTF16计且截止前一刻可接纳() {
        val current = plan(turns(5)).candidate!!
        val text = "\uD83D\uDE00".repeat(1000)
        assertEquals(2000, text.length)
        assertEquals(text, ConversationSummaryPolicy.Attempt(current, id(50), 100).accept(current, id(50), text, 99))
        assertNull(ConversationSummaryPolicy.Attempt(current, id(50), 100).accept(current, id(50), text + "字", 99))
        assertNull(ConversationSummaryPolicy.Attempt(current, id(50), 100).accept(current, id(50), text, 100))
        assertNull(ConversationSummaryPolicy.Attempt(current, id(50), 100).accept(current, id(50), text, -1))
    }

    @Test fun 并发重复结果最多接纳一次() {
        val current = plan(turns(5)).candidate!!
        val attempt = ConversationSummaryPolicy.Attempt(current, id(50), 100)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map { index -> executor.submit(Callable {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                attempt.accept(current, id(50), "摘要$index", 1)
            }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.mapNotNull { it.get(5, TimeUnit.SECONDS) }.size)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun 近期窗口变化即使摘要来源不变也拒绝旧结果() {
        val rows = turns(5)
        val original = plan(rows).candidate!!
        val changed = plan(rows.mapIndexed { index, turn -> if (index == 4) turn.copy(answer = "新的近期回答") else turn }).candidate!!
        assertEquals(original.sourceIds, changed.sourceIds)
        val attempt = ConversationSummaryPolicy.Attempt(original, id(50), 100)
        assertNull(attempt.accept(changed, id(50), "迟到摘要", 1))
        assertNull(attempt.accept(original, id(50), "迟到摘要", 2))
    }

    @Test fun 超时取消失效与无效正文不接纳且关闭尝试() {
        val current = plan(turns(5)).candidate!!
        for (kind in 0..5) {
            val attempt = ConversationSummaryPolicy.Attempt(current, id(50), 100)
            if (kind == 0) attempt.cancel()
            assertNull(attempt.accept(if (kind == 1) null else current, id(50),
                when (kind) { 3 -> " "; 4 -> "字".repeat(2001); 5 -> "\uD800"; else -> "摘要" }, if (kind == 2) 100 else 1))
            assertNull(attempt.accept(current, id(50), "摘要", 2))
        }
    }
}
