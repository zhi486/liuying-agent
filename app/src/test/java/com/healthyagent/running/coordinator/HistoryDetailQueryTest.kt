package com.healthyagent.running.coordinator

import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.Split
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HistoryDetailQueryTest {
    private val run = RunSession(
        id = "run-1", startTimeMillis = 0, endTimeMillis = 720_000,
        totalDistanceMeters = 2000.0, totalDurationSec = 720,
        runState = RunState.FINISHED,
        splits = listOf(Split(1, 1000.0, 350, null, 160, 350.0, null)),
    )

    @Test fun `详情来自指定记录且保留空心率不返回轨迹`() = runTest {
        val outcome = readHistoryDetail("run-1") { id -> assertEquals("run-1", id); run }
        val data = (outcome as CommandOutcome.AcceptedWithData).data
        val record = data.getValue("record").jsonObject
        assertEquals("2000.0", record.getValue("distanceMeters").jsonPrimitive.content)
        assertEquals(JsonNull, record.getValue("averageHeartRate"))
        assertEquals(setOf("record", "splits"), data.keys)
        val split = data.getValue("splits").jsonArray.single().jsonObject
        assertEquals("350", split.getValue("durationSeconds").jsonPrimitive.content)
        assertEquals(JsonNull, split.getValue("averageHeartRate"))
    }

    @Test fun `无分段保持为空`() = runTest {
        val outcome = readHistoryDetail("run-1") { run.copy(splits = emptyList()) }
        assertTrue((outcome as CommandOutcome.AcceptedWithData).data.getValue("splits").jsonArray.isEmpty())
    }

    @Test fun `非法标识不访问仓储`() = runTest {
        for (id in listOf("", " ", " run-1", "x".repeat(129))) {
            val outcome = readHistoryDetail(id) { error("不应查询") } as CommandOutcome.Rejected
            assertEquals(BridgeErrorCode.INVALID_MESSAGE, outcome.code)
        }
    }

    @Test fun `不存在和未结束的记录不能显示为总结`() = runTest {
        assertEquals(BridgeErrorCode.RESOURCE_UNAVAILABLE, (readHistoryDetail("run-1") { null } as CommandOutcome.Rejected).code)
        for (state in listOf(RunState.RUNNING, RunState.PAUSED, RunState.IDLE)) {
            assertEquals(BridgeErrorCode.COMMAND_NOT_ALLOWED, (readHistoryDetail("run-1") { run.copy(runState = state) } as CommandOutcome.Rejected).code)
        }
    }

    @Test fun `仓储异常不暴露内部信息`() = runTest {
        val outcome = readHistoryDetail("run-1") { throw IllegalStateException("private data") } as CommandOutcome.Rejected
        assertEquals(BridgeErrorCode.RESOURCE_UNAVAILABLE, outcome.code)
        assertFalse(outcome.message.contains("private data"))
    }

    @Test(expected = CancellationException::class)
    fun `协程取消不得转成读取失败`() = runTest {
        readHistoryDetail("run-1") { throw CancellationException() }
    }
}
