package com.healthyagent.running.coordinator

import com.healthyagent.running.core.model.*
import com.healthyagent.running.bridge.BridgeErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HistorySeriesQueryTest {
    private val run = RunSession(id = "run-1", startTimeMillis = 0, runState = RunState.FINISHED,
        metricSamples = List(301) { RunMetricSample(it * 5L, it * 5L + 5, 0, null, 145.5, 0, 4) })

    @Test fun `分页保留缺失覆盖量并限制消息大小`() = runTest {
        val first = readHistorySeries("run-1", 0, 300) { run } as CommandOutcome.AcceptedWithData
        val last = readHistorySeries("run-1", 300, 300) { run } as CommandOutcome.AcceptedWithData
        assertEquals(300, first.data.getValue("samples").jsonArray.size)
        assertEquals("301", first.data.getValue("totalItems").jsonPrimitive.content)
        val sample = last.data.getValue("samples").jsonArray.single().jsonObject
        assertEquals("1500", sample.getValue("startElapsedSeconds").jsonPrimitive.content)
        assertEquals(JsonNull, sample["averagePaceSecPerKm"])
        assertEquals("4", sample.getValue("heartRateValidSeconds").jsonPrimitive.content)
        assertTrue(first.data.toString().toByteArray().size < 200_000)
        assertFalse(first.data.toString().contains("routePoints"))
    }

    @Test fun `旧记录返回空序列`() = runTest {
        val result = readHistorySeries("run-1", 0, 300) { run.copy(metricSamples = emptyList()) }
        assertTrue((result as CommandOutcome.AcceptedWithData).data.getValue("samples").jsonArray.isEmpty())
    }

    @Test fun `拒绝无效标识分页未完成和不匹配记录`() = runTest {
        assertEquals(BridgeErrorCode.INVALID_MESSAGE, (readHistorySeries(" ", 0, 300) { error("不应读取") } as CommandOutcome.Rejected).code)
        for ((offset, limit) in listOf(-1 to 300, 0 to 301, 0 to 0)) {
            assertTrue(readHistorySeries("run-1", offset, limit) { error("不应读取") } is CommandOutcome.Rejected)
        }
        for (bad in listOf(run.copy(runState = RunState.RUNNING), run.copy(id = "other"))) {
            assertEquals(BridgeErrorCode.COMMAND_NOT_ALLOWED, (readHistorySeries("run-1", 0, 300) { bad } as CommandOutcome.Rejected).code)
        }
        assertTrue(readHistorySeries("run-1", Int.MAX_VALUE, 300) { run } is CommandOutcome.Rejected)
        assertTrue(readHistorySeries("run-1", 0, 300) { null } is CommandOutcome.Rejected)
    }

    @Test fun `读取错误脱敏且取消继续抛出`() = runTest {
        val error = readHistorySeries("run-1", 0, 300) { error("private") } as CommandOutcome.Rejected
        assertFalse(error.message.contains("private"))
        try {
            readHistorySeries("run-1", 0, 300) { throw CancellationException() }
            fail("取消未抛出")
        } catch (_: CancellationException) { }
    }
}
