package com.healthyagent.running.agent.cloud

import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudTaskEventsTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun packet(seq: Int, kind: String, round: Int = 1, extra: String? = null): String {
        val value = buildJsonObject { put("version", "task_events_v1"); put("taskId", id); put("sequence", seq)
            put("round", round); put("kind", kind)
            if (extra != null) put(when (kind) { "text_delta" -> "text"; "round_end" -> "outcome"; else -> "status" }, extra) }
        return "id: $seq\nevent: $kind\ndata: $value\n\n"
    }
    private fun answer() = packet(1, "round_start") + packet(2, "text_delta", extra = "合成流萤🌟") +
        packet(3, "round_end", extra = "answer") + packet(4, "task_end", extra = "completed")
    private suspend fun parse(text: String, state: CloudTaskStream = CloudTaskStream(id)) {
        CloudTaskEvents.read(text.byteInputStream(), id, state.cursor, state::accept)
    }

    @Test fun 分块UTF8和三种换行不影响序号且终态草稿等待正式回执替换() = runBlocking {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val bytes = ("\uFEFF: waiting\n\n" + answer()).replace("\n", newline).toByteArray()
            val stream = object : ByteArrayInputStream(bytes) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 1))
            }
            val state = CloudTaskStream(id); val drafts = mutableListOf<String?>()
            CloudTaskEvents.read(stream, id, 0) { state.accept(it); drafts += state.draft() }
            assertEquals("合成流萤🌟", drafts[1]); assertEquals("合成流萤🌟", drafts.last())
            assertTrue(state.ended); assertEquals(4, state.cursor)
        }
    }

    @Test fun 工具轮文字丢弃且下一轮不继承临时正文() = runBlocking {
        val state = CloudTaskStream(id)
        val drafts = mutableListOf<String?>()
        CloudTaskEvents.read((packet(1, "round_start") + packet(2, "text_delta", extra = "工具说明") +
            packet(3, "round_end", extra = "tools") + packet(4, "round_start", 2) +
            packet(5, "text_delta", 2, "正式候选") + packet(6, "round_end", 2, "answer") +
            packet(7, "task_end", 2, "completed")).byteInputStream(),
            id, 0) { state.accept(it); drafts += state.draft() }
        assertNull(drafts[2]); assertEquals("正式候选", drafts.last())
        assertEquals(7, state.cursor); assertTrue(state.ended)
    }

    @Test fun 中途断开不接受半截终态并能用原游标只读续传() = runBlocking {
        val state = CloudTaskStream(id)
        try { parse(packet(1, "round_start") + packet(2, "text_delta", extra = "前缀"), state); fail() } catch (_: CloudTaskFailure) {}
        assertEquals(2, state.cursor); assertEquals("前缀", state.draft()); assertFalse(state.ended)
        val texts = mutableListOf<String?>()
        CloudTaskEvents.read((packet(3, "text_delta", extra = "后缀") + packet(4, "round_end", extra = "answer") +
            packet(5, "task_end", extra = "completed")).byteInputStream(), id, state.cursor) { state.accept(it); texts += state.draft() }
        assertEquals("前缀后缀", texts.first()); assertEquals("前缀后缀", state.draft())
    }

    @Test fun 失败取消终态清空临时正文() = runBlocking {
        for (status in listOf("failed", "cancelled", "timed_out", "blocked")) {
            val state = CloudTaskStream(id)
            parse(packet(1, "round_start") + packet(2, "text_delta", extra = "临时正文") +
                packet(3, "round_end", extra = "answer") + packet(4, "task_end", extra = status), state)
            assertTrue("$status 应清空草稿", state.draft() == null)
            assertTrue("$status 应结束", state.ended)
        }
    }

    @Test fun 非法序号任务类型轮次额外字段及过长正文不改变已确认状态() = runBlocking {
        val invalid = listOf(answer().replace("id: 1", "id: 01"), answer().replace("\"sequence\":1", "\"sequence\":2"),
            answer().replace(id, "22222222-2222-4222-8222-222222222222"), answer().replace("round_start", "unknown"),
            answer().replace("\"round\":1", "\"round\":4"), answer().replace("\"version\"", "\"other\":true,\"version\""),
            packet(1, "text_delta", extra = "无开始"), packet(1, "round_start") + packet(2, "text_delta", extra = "字".repeat(4001)))
        for (text in invalid) {
            try { parse(text); fail("应拒绝坏事件") } catch (_: IllegalArgumentException) {} catch (_: CloudTaskFailure) {} catch (_: IllegalStateException) {}
        }
        val state = CloudTaskStream(id); state.accept(CloudTaskEvent(id, 1, 1, "round_start"))
        try { state.accept(CloudTaskEvent(id, 3, 1, "text_delta", text = "不能出现")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1, state.cursor); assertNull(state.draft())
    }

    @Test fun 坏UTF8超长行和服务端错误都不成为聊天正文() = runBlocking {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), ("data: " + "x".repeat(32769)).toByteArray(),
            "event: stream_error\ndata: {\"error\":\"unavailable\"}\n\n".toByteArray())) {
            try { CloudTaskEvents.read(bytes.inputStream(), id, 0) { fail() }; fail() } catch (_: Exception) {}
        }
    }

    @Test fun 真正未结束的输入管道能先交付文字再等待终态() = runBlocking {
        val source = PipedInputStream(); val sink = PipedOutputStream(source); val received = CompletableDeferred<Unit>()
        val state = CloudTaskStream(id)
        val job = launch(Dispatchers.IO) { source.use { CloudTaskEvents.read(it, id, 0) { event ->
            state.accept(event); if (event.kind == "text_delta") received.complete(Unit)
        } } }
        try {
            sink.write((packet(1, "round_start") + packet(2, "text_delta", extra = "尚未结束")).toByteArray()); sink.flush()
            withTimeout(3000) { received.await() }
            assertTrue(job.isActive); assertEquals("尚未结束", state.draft())
            sink.write((packet(3, "round_end", extra = "answer") + packet(4, "task_end", extra = "completed")).toByteArray()); sink.flush()
            withTimeout(3000) { job.join() }; assertTrue(state.ended)
        } finally { sink.close(); source.close(); job.cancelAndJoin() }
    }
}
