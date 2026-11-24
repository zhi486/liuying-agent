package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpTaskConfirmationTest {
    private val f = McpTaskFixtures
    private fun invocation() = McpTaskInvocation.parse(f.invocation(), f.context())

    @Test fun 仅原生当前请求对象能完成一次确认且默认没有批准() = runTest {
        val host = McpTaskConfirmation()
        assertNull(host.requesting.value)
        val answer = async { host.ask(invocation()) }
        runCurrent()
        val opened = host.requesting.value!!
        assertEquals("notes.search", opened.invocation.name)
        assertFalse(answer.isCompleted)
        assertFalse(host.respond(McpTaskConfirmation.Request(invocation()), true))
        assertTrue(host.respond(opened, true)); assertFalse(host.respond(opened, true))
        assertTrue(answer.await()); assertNull(host.requesting.value)
    }

    @Test fun 拒绝明确返回false且旧窗口不能批准下个请求() = runTest {
        val host = McpTaskConfirmation()
        val first = async { host.ask(invocation()) }; runCurrent()
        val previous = host.requesting.value!!
        assertTrue(host.respond(previous, false)); assertFalse(first.await())
        val next = async { host.ask(invocation()) }; runCurrent()
        assertFalse(host.respond(previous, true)); assertFalse(next.isCompleted)
        host.respond(host.requesting.value!!, false)
        assertFalse(next.await())
    }

    @Test fun 并发确认不能覆盖尚未处理的原窗口() = runTest {
        val host = McpTaskConfirmation()
        val first = async { host.ask(invocation()) }; runCurrent()
        val opened = host.requesting.value!!
        try { host.ask(invocation()); fail("应保留原确认") } catch (error: CloudTaskFailure) { assertEquals("busy", error.code) }
        assertSame(opened, host.requesting.value)
        host.respond(opened, false); first.await()
    }

    @Test fun 任务取消清除确认状态且迟到点击不能创建批准() = runTest {
        val host = McpTaskConfirmation()
        val work = launch { host.ask(invocation()) }; runCurrent()
        val opened = host.requesting.value!!
        work.cancelAndJoin()
        assertNull(host.requesting.value); assertFalse(host.respond(opened, true))
        assertFalse(opened.toString().contains("今晚"))
    }
}
