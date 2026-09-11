package com.healthyagent.running.bridge

import com.healthyagent.running.coordinator.AppSnapshot
import com.healthyagent.running.coordinator.CommandOutcome
import com.healthyagent.running.core.model.RunConfig
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeSessionTest {
    private val sentMessages = mutableListOf<String>()
    private var commandCalls = 0
    private val session = BridgeSession(
        buildId = "test-build",
        snapshotProvider = { AppSnapshot.initial(RunConfig(), 1_787_745_600_000L) },
        commandHandler = { _, _ ->
            commandCalls++
            CommandOutcome.Accepted
        },
        send = sentMessages::add,
        now = { Instant.parse("2026-08-26T12:00:00Z") },
        newId = { UUID.randomUUID().toString() },
    )

    @Test
    fun `hello 返回包含当前快照的 ready`() = runTest {
        session.onMessage(fixture("/v1/fixtures/valid/hello.json"))

        val decoded = BridgeCodec.decode(sentMessages.single()) as BridgeDecodeResult.Accepted
        assertEquals(BridgeMessageType.READY, decoded.envelope.type)
        assertEquals(
            "00000000-0000-4000-8000-000000000001",
            decoded.envelope.payload.getValue("helloId").jsonPrimitive.content,
        )
        assertEquals(
            listOf("snapshot.v1", "history.pagination", "settings.v1", "heart-rate-alerts.v1"),
            decoded.envelope.payload.getValue("capabilities").jsonArray.map {
                it.jsonPrimitive.content
            },
        )
    }

    @Test
    fun `未握手命令被拒绝且结果 ID 可直接关联`() = runTest {
        session.onMessage(fixture("/v1/fixtures/valid/command.run-start.json"))

        val decoded = BridgeCodec.decode(sentMessages.single()) as BridgeDecodeResult.Accepted
        assertEquals(BridgeMessageType.RESULT, decoded.envelope.type)
        assertEquals("00000000-0000-4000-8000-000000000004", decoded.envelope.id)
        assertEquals("rejected", decoded.envelope.payload.getValue("status").jsonPrimitive.content)
        assertEquals(0, commandCalls)
    }

    @Test
    fun `完成后的重复命令只执行一次并复用结果`() = runTest {
        session.onMessage(fixture("/v1/fixtures/valid/hello.json"))
        val command = fixture("/v1/fixtures/valid/command.run-start.json")

        session.onMessage(command)
        session.onMessage(command)

        assertEquals(1, commandCalls)
        assertEquals(sentMessages[1], sentMessages[2])
        assertTrue(BridgeCodec.decode(sentMessages[1]) is BridgeDecodeResult.Accepted)
    }

    @Test
    fun `执行中的重复命令共享同一结果且处理器只调用一次`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val concurrentSession = BridgeSession(
            buildId = "test-build",
            snapshotProvider = { AppSnapshot.initial(RunConfig(), 1_787_745_600_000L) },
            commandHandler = { _, _ ->
                calls++
                gate.await()
                CommandOutcome.Accepted
            },
            send = sentMessages::add,
            now = { Instant.parse("2026-08-26T12:00:00Z") },
            newId = { UUID.randomUUID().toString() },
        )
        concurrentSession.onMessage(fixture("/v1/fixtures/valid/hello.json"))
        val command = fixture("/v1/fixtures/valid/command.run-start.json")

        val first = launch { concurrentSession.onMessage(command) }
        val duplicate = launch { concurrentSession.onMessage(command) }
        runCurrent()

        assertEquals(1, calls)
        assertEquals(1, sentMessages.size)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        duplicate.join()

        assertEquals(3, sentMessages.size)
        assertEquals(sentMessages[1], sentMessages[2])
        val result = BridgeCodec.decode(sentMessages[1]) as BridgeDecodeResult.Accepted
        assertEquals("00000000-0000-4000-8000-000000000004", result.envelope.id)
    }

    @Test
    fun `挂起的历史查询不会阻塞另一条命令且结果按各自 ID 关联`() = runTest {
        val historyGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val concurrentSession = BridgeSession(
            buildId = "test-build",
            snapshotProvider = { AppSnapshot.initial(RunConfig(), 1_787_745_600_000L) },
            commandHandler = { name, _ ->
                calls += name
                if (name == "history.query") historyGate.await()
                CommandOutcome.Accepted
            },
            send = sentMessages::add,
            now = { Instant.parse("2026-08-26T12:00:00Z") },
            newId = { UUID.randomUUID().toString() },
        )
        concurrentSession.onMessage(fixture("/v1/fixtures/valid/hello.json"))

        val history = launch {
            concurrentSession.onMessage(fixture("/v1/fixtures/valid/command.history-query.json"))
        }
        val start = launch {
            concurrentSession.onMessage(fixture("/v1/fixtures/valid/command.run-start.json"))
        }
        runCurrent()

        assertEquals(listOf("history.query", "run.start"), calls)
        assertEquals(2, sentMessages.size)
        val startResult = BridgeCodec.decode(sentMessages.last()) as BridgeDecodeResult.Accepted
        assertEquals("00000000-0000-4000-8000-000000000004", startResult.envelope.id)

        historyGate.complete(Unit)
        advanceUntilIdle()
        history.join()
        start.join()
        val historyResult = BridgeCodec.decode(sentMessages.last()) as BridgeDecodeResult.Accepted
        assertEquals("00000000-0000-4000-8000-000000000005", historyResult.envelope.id)
    }

    @Test
    fun `带数据的 accepted 会保留数据并注入可信 commandId`() = runTest {
        val dataSession = BridgeSession(
            buildId = "test-build",
            snapshotProvider = { AppSnapshot.initial(RunConfig(), 1_787_745_600_000L) },
            commandHandler = { _, _ ->
                CommandOutcome.AcceptedWithData(
                    buildJsonObject {
                        put("commandId", "不可信值")
                        put("page", 1)
                    },
                )
            },
            send = sentMessages::add,
            now = { Instant.parse("2026-08-26T12:00:00Z") },
            newId = { UUID.randomUUID().toString() },
        )
        dataSession.onMessage(fixture("/v1/fixtures/valid/hello.json"))
        dataSession.onMessage(fixture("/v1/fixtures/valid/command.history-query.json"))

        val decoded = BridgeCodec.decode(sentMessages.last()) as BridgeDecodeResult.Accepted
        val data = decoded.envelope.payload.getValue("data").jsonObject
        assertEquals("00000000-0000-4000-8000-000000000005", decoded.envelope.id)
        assertEquals(decoded.envelope.id, data.getValue("commandId").jsonPrimitive.content)
        assertEquals(1, data.getValue("page").jsonPrimitive.content.toInt())
    }

    @Test
    fun `握手后可以推送合同合法的 snapshot`() = runTest {
        session.onMessage(fixture("/v1/fixtures/valid/hello.json"))
        session.pushSnapshot()

        val decoded = BridgeCodec.decode(sentMessages.last()) as BridgeDecodeResult.Accepted
        assertEquals(BridgeMessageType.SNAPSHOT, decoded.envelope.type)
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
