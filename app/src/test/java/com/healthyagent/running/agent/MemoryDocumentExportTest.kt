package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.*
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.coordinator.CommandOutcome
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.io.Writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryDocumentExportTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private fun body(id: String = this.id, confirmed: Boolean = false) = buildJsonObject {
        put("exportId", id)
        if (confirmed) put("confirmed", true)
    }
    private fun phase(result: CommandOutcome) = (result as CommandOutcome.AcceptedWithData).data.getValue("phase").jsonPrimitive.content
    private fun document() = MemoryDocument(3000, List(121) { index ->
        MemoryDocumentEntry(MemoryRecord(id, "合成偏好 $index", "user_explicit", 1000, 2000, 1), "manual", null, null)
    })

    @Test fun 保存命令只接受显式确认和操作标识而不接受路径正文归属() {
        assertEquals(id, MemoryExportCommands.decode("memory.exportBegin", body(confirmed = true)))
        assertEquals(id, MemoryExportCommands.decode("memory.exportStatus", body()))
        assertEquals(id, MemoryExportCommands.decode("memory.exportCancel", body()))
        listOf(
            body(), body(confirmed = true) + ("path" to JsonPrimitive("/private")),
            body(confirmed = true) + ("ownerId" to JsonPrimitive("other")),
            body(confirmed = true) + ("content" to JsonPrimitive("网页正文")),
            body(confirmed = true) + ("uri" to JsonPrimitive("content://target")),
            body(confirmed = true) + ("confirmed" to JsonPrimitive("true")),
            body(confirmed = true) + ("exportId" to JsonPrimitive("bad")),
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { MemoryExportCommands.decode("memory.exportBegin", JsonObject(value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { MemoryExportCommands.decode("memory.exportStatus", body(confirmed = true)) }
    }

    @Test fun 选择器等待不读正文且取消不打开目标或自动重做() = runTest {
        var reads = 0
        var choices = 0
        val exporter = MemoryDocumentExport(this, { choices++ }, { reads++; document() }, StandardTestDispatcher(testScheduler))
        assertEquals("choosing", phase(exporter.handle("memory.exportBegin", body(confirmed = true))))
        assertEquals("choosing", phase(exporter.handle("memory.exportBegin", body(confirmed = true))))
        assertTrue(exporter.handle("memory.exportBegin", body(other, true)) is CommandOutcome.Rejected)
        advanceUntilIdle()
        assertEquals(0, reads)
        assertEquals(1, choices)
        exporter.selected(null)
        assertEquals("cancelled", phase(exporter.handle("memory.exportStatus", body())))
        assertEquals("cancelled", phase(exporter.handle("memory.exportBegin", body(confirmed = true))))
        assertEquals(1, choices)
    }

    @Test fun 原生完整快照覆盖全部条目且关闭目标后才报告成功() = runTest {
        var reads = 0
        var closed = false
        val writer = object : StringWriter() { override fun close() { closed = true; super.close() } }
        val exporter = MemoryDocumentExport(this, {}, { reads++; document() }, StandardTestDispatcher(testScheduler))
        exporter.handle("memory.exportBegin", body(confirmed = true))
        exporter.selected { writer }
        assertEquals("writing", phase(exporter.handle("memory.exportStatus", body())))
        advanceUntilIdle()
        val result = exporter.handle("memory.exportStatus", body()) as CommandOutcome.AcceptedWithData
        assertEquals("saved", phase(result))
        assertTrue(closed)
        assertEquals(1, reads)
        assertEquals(121, result.data.getValue("totalItems").jsonPrimitive.int)
        assertEquals(3000L, result.data.getValue("generatedAtMillis").jsonPrimitive.long)
        assertTrue(writer.toString().contains("合成偏好 120"))
        assertFalse(result.data.toString().contains("合成偏好"))
    }

    @Test fun 快照读取打开目标写入及关闭异常均不冒充成功也不泄露异常() = runTest {
        for (failure in listOf("read", "open", "write", "close")) {
            var closed = false
            val exporter = MemoryDocumentExport(this, {}, {
                if (failure == "read") error("私密异常")
                document()
            }, StandardTestDispatcher(testScheduler))
            exporter.handle("memory.exportBegin", body(confirmed = true))
            exporter.selected {
                if (failure == "open") throw IOException("私密异常")
                object : Writer() {
                    override fun write(value: CharArray, offset: Int, length: Int) { if (failure == "write") throw IOException("私密异常") }
                    override fun flush() {}
                    override fun close() { closed = true; if (failure == "close") throw IOException("私密异常") }
                }
            }
            advanceUntilIdle()
            val result = exporter.handle("memory.exportStatus", body()) as CommandOutcome.AcceptedWithData
            assertEquals("failed", phase(result))
            assertFalse(result.data.toString().contains("私密异常"))
            assertEquals(failure in listOf("write", "close"), closed)
        }
    }

    @Test fun 显式取消或重载后迟到选择结果不写正文且不能串到新操作() = runTest {
        var reads = 0
        val exporter = MemoryDocumentExport(this, {}, { reads++; document() }, StandardTestDispatcher(testScheduler))
        exporter.handle("memory.exportBegin", body(confirmed = true))
        assertEquals("cancelled", phase(exporter.handle("memory.exportCancel", body())))
        assertTrue(exporter.handle("memory.exportBegin", body(other, true)) is CommandOutcome.Rejected)
        exporter.selected { error("不能打开旧目标") }
        assertEquals("choosing", phase(exporter.handle("memory.exportBegin", body(other, true))))
        exporter.reset()
        exporter.selected { error("不能打开重载前目标") }
        advanceUntilIdle()
        assertEquals(0, reads)
        assertTrue(exporter.handle("memory.exportStatus", body(other)) is CommandOutcome.Rejected)
    }

    @Test fun 读取期间取消或宿主销毁不会在恢复后打开文件() = runTest {
        for (reset in listOf(false, true)) {
            val gate = CompletableDeferred<MemoryDocument>()
            var opened = 0
            val exporter = MemoryDocumentExport(this, {}, { gate.await() }, StandardTestDispatcher(testScheduler))
            exporter.handle("memory.exportBegin", body(confirmed = true))
            exporter.selected { opened++; StringWriter() }
            runCurrent()
            if (reset) exporter.reset() else assertEquals("interrupted", phase(exporter.handle("memory.exportCancel", body())))
            gate.complete(document())
            advanceUntilIdle()
            assertEquals(0, opened)
            if (!reset) assertEquals("interrupted", phase(exporter.handle("memory.exportStatus", body())))
        }
    }

    @Test fun 选择器无法启动只返回失败且未知操作不执行() = runTest {
        val exporter = MemoryDocumentExport(this, { error("私密异常") }, { error("不能读正文") })
        assertTrue(exporter.handle("memory.exportStatus", body()) is CommandOutcome.Rejected)
        assertEquals("failed", phase(exporter.handle("memory.exportBegin", body(confirmed = true))))
        exporter.selected { error("不能打开目标") }
        assertEquals("failed", phase(exporter.handle("memory.exportStatus", body())))
    }

    @Test fun 空库和中文表情均完整写入UTF8且副本不改变原生记录() = runTest {
        for (empty in listOf(false, true)) {
            val before = if (empty) MemoryDocument(3000, emptyList()) else document().copy(entries = document().entries.take(1).map {
                it.copy(memory = it.memory.copy(content = "中文与表情🌟\n```text\n原样保留"))
            })
            val bytes = ByteArrayOutputStream()
            val exporter = MemoryDocumentExport(this, {}, { before }, StandardTestDispatcher(testScheduler))
            exporter.handle("memory.exportBegin", body(confirmed = true))
            exporter.selected { bytes.bufferedWriter(Charsets.UTF_8) }
            advanceUntilIdle()
            val expected = StringBuilder().also(before::writeMarkdown).toString()
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), bytes.toByteArray())
            assertEquals(if (empty) 0 else 1, before.entries.size)
            assertEquals("saved", phase(exporter.handle("memory.exportStatus", body())))
        }
    }
}
