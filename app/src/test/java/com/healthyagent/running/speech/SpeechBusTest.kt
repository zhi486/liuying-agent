package com.healthyagent.running.speech

import com.healthyagent.running.core.model.CoachMessage
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import com.healthyagent.running.testing.FakeDiagnosticLogger
import com.healthyagent.running.testing.FakeTtsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechBusTest {
    @Test
    fun `真实Policy到Player播放超时使provider和Bus使用相同超时诊断`() = runTest {
        val logger = FakeDiagnosticLogger()
        val engine = VoicePolicyTtsEngine(
            object : BundledVoiceStore {
                override suspend fun open(ruleId: String) = java.io.File("bundled.mp3")
            },
            { null },
            object : CompanionTextSource {
                override suspend fun create(request: SpeechRequest): String? = null
            },
            { PreparedVoicePlayer({ object : AudioPlayerPort {
                override suspend fun prepare(file: java.io.File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener) = Result.success(Unit)
                override suspend fun startPrepared(startIfValid: (() -> Unit) -> Boolean): Result<Unit> {
                    startIfValid {}
                    return Result.success(Unit)
                }
                override fun start(file: java.io.File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener) = Result.success(Unit)
                override fun stop() = Unit
                override fun release() = Unit
            } }, object : AudioFocusController {
                override fun request() = object : AudioFocusLease {
                    override val granted = true
                    override fun release() = Unit
                }
            }, logger) },
            logger,
            timeSource = testScheduler.timeSource,
        )
        val bus = newBus(engine, logger)
        bus.enqueue(trigger("session_paused", "跑步已暂停。"))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(SpeechOutcomeReason.PLAYBACK_TIMEOUT, outcomes(logger).single().reason)
        assertTrue(logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
            .any { it.kind == com.healthyagent.running.diagnostics.TtsEventKind.PLAYBACK_TIMEOUT })
        bus.shutdown()
        runCurrent()
    }

    @Test
    fun `typed引擎返回实际本地文本而非未播陪伴句`() = runTest {
        val tts = DeliveryTtsEngine(SpeechDelivery("固定核心。", true))
        val bus = newBus(tts)
        bus.enqueue(trigger("session_started", "固定核心。未播陪伴句。"))
        runCurrent()
        assertEquals("固定核心。", bus.latestMessage.value?.text)
        assertEquals(1, tts.typedCalls)
    }

    @Test
    fun `公里未播放仍显示核心但不记成功或去重历史`() = runTest {
        val logger = FakeDiagnosticLogger()
        val tts = DeliveryTtsEngine(SpeechDelivery("公里核心。", false))
        val bus = newBus(tts, logger, minIntervalSec = 4)
        val request = trigger("km_split", "公里核心。", SpeechPriority.SPLIT)
        bus.enqueue(request)
        runCurrent()
        assertEquals("公里核心。", bus.latestMessage.value?.text)
        assertFalse(outcomes(logger).any { it.reason == SpeechOutcomeReason.TTS_SUCCESS })
        bus.enqueue(request)
        runCurrent()
        assertEquals(2, tts.typedCalls)
    }

    @Test
    fun `本地播放失败只保留目录核心而不显示未播陪伴句`() = runTest {
        val tts = object : TtsEngine {
            override val isSpeaking = false
            override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> = error("只允许typed入口")
            override suspend fun speak(request: SpeechRequest): Result<SpeechDelivery> =
                Result.failure(IllegalStateException("本地音频损坏"))
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val logger = FakeDiagnosticLogger()
        val bus = newBus(tts, logger)
        bus.enqueue(SpeechRequest(trigger("session_paused", "跑步已暂停。未播陪伴句。"), true))
        runCurrent()
        assertEquals("跑步已暂停。", bus.latestMessage.value?.text)
        assertEquals(SpeechOutcomeReason.TTS_FAILURE, outcomes(logger).single().reason)
    }

    private class DeliveryTtsEngine(val delivery: SpeechDelivery) : TtsEngine {
        var typedCalls = 0
        override val isSpeaking = false
        override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> =
            Result.success(Unit)
        override suspend fun speak(request: SpeechRequest): Result<SpeechDelivery> {
            typedCalls++
            return Result.success(delivery)
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    @Test
    fun `Fake 的迟到 helper 只命中已完成队首且不会完成后续请求`() = runTest {
        val tts = FakeTtsEngine()
        val first = async { tts.speak("A", SpeechPriority.APP) }
        runCurrent()

        tts.stop()
        tts.completeNextSuccessfully()
        runCurrent()
        assertTrue(first.isCancelled)

        val second = async { tts.speak("B", SpeechPriority.APP) }
        runCurrent()
        assertFalse(second.isCompleted)

        tts.completeNextSuccessfully()
        runCurrent()
        assertTrue(second.await().isSuccess)
        assertEquals(listOf("B"), tts.completedTexts)
    }

    @Test
    fun `会话状态在返回前登记 scheduled 且 actor 推进后才调用引擎`() = runTest {
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = newBus(tts, logger)

        bus.enqueueSessionStatus(session(SessionSpeechEvent.STARTED))

        assertEquals(
            listOf(outcome("session_started", SpeechOutcomeReason.SESSION_STATUS_SCHEDULED)),
            outcomes(logger),
        )
        assertTrue(tts.requests.isEmpty())
        runCurrent()
        assertEquals(listOf("流萤已开始记录，出发吧。"), tts.requests.map { it.text })
    }

    @Test
    fun `同一会话提示两次合法完成时绕过文本间隔去重`() = runTest {
        val tts = FakeTtsEngine()
        val bus = newBus(tts, minIntervalSec = 4)
        val paused = session(SessionSpeechEvent.PAUSED)

        bus.enqueueSessionStatus(paused)
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()
        bus.enqueueSessionStatus(paused)
        runCurrent()

        assertEquals(listOf("跑步已暂停。", "跑步已暂停。"), tts.requests.map { it.text })
    }

    @Test
    fun `快速 PAUSED 到 RESUMED 只由 actor 停旧播放并发布 RESUMED`() = runTest {
        val tts = FakeTtsEngine()
        val bus = newBus(tts)

        bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
        runCurrent()
        bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))
        assertEquals(0, tts.stopCalls)
        runCurrent()

        assertEquals(1, tts.stopCalls)
        assertEquals(listOf("跑步已暂停。", "跑步已恢复。"), tts.requests.map { it.text })
        tts.completeNextSuccessfully()
        runCurrent()
        assertEquals("跑步已恢复。", bus.latestMessage.value?.text)
    }

    @Test
    fun `URGENT 的成功失败和超时终态都会提升最新 pending status`() = runTest {
        val terminals = listOf<(FakeTtsEngine) -> Unit>(
            { it.completeNextSuccessfully() },
            { it.completeNextWithFailure() },
            { it.completeNextWithFailure(PlaybackTimeoutException()) },
        )
        val expectedReasons = listOf(
            SpeechOutcomeReason.TTS_SUCCESS,
            SpeechOutcomeReason.TTS_FAILURE,
            SpeechOutcomeReason.PLAYBACK_TIMEOUT,
        )

        terminals.zip(expectedReasons).forEachIndexed { index, (finishUrgent, reason) ->
            val tts = FakeTtsEngine()
            val logger = FakeDiagnosticLogger()
            val bus = newBus(tts, logger)
            bus.enqueue(trigger("urgent-$index", "紧急-$index", SpeechPriority.URGENT))
            runCurrent()
            bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))
            runCurrent()
            assertEquals(1, tts.requests.size)

            finishUrgent(tts)
            runCurrent()

            assertEquals(listOf("紧急-$index", "跑步已恢复。"), tts.requests.map { it.text })
            assertTrue(outcomes(logger).contains(outcome("urgent-$index", reason)))
            tts.completeNextSuccessfully()
            runCurrent()
        }
    }

    @Test
    fun `URGENT 活跃时 pending 替换精确记录旧 ruleId 且只播放最新项`() = runTest {
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = newBus(tts, logger)
        bus.enqueue(trigger("urgent", "紧急", SpeechPriority.URGENT))
        runCurrent()

        bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
        bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))

        assertEquals(
            1,
            outcomes(logger).count {
                it == outcome("session_paused", SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED)
            },
        )
        assertFalse(
            outcomes(logger).contains(
                outcome("session_resumed", SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED),
            ),
        )
        tts.completeNextSuccessfully()
        runCurrent()
        assertEquals(listOf("紧急", "跑步已恢复。"), tts.requests.map { it.text })
    }

    @Test
    fun `actor stop 阻塞期间的新状态使旧 Replace 不得启动或迟到 stop`() = runTest {
        val tts = BlockingStopTtsEngine()
        val bus = SpeechBus(
            ttsEngine = tts,
            initialPolicy = SpeechPolicy(enabled = true, minIntervalSec = 0),
            newMessageId = { "message" },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        bus.enqueue(trigger("A", "A", SpeechPriority.COACH))
        runCurrent()

        val producer1 = thread(name = "较早状态线程") {
            bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
        }
        producer1.join()
        val producer2Returned = CountDownLatch(1)
        val producer2 = thread(name = "较新状态线程") {
            check(tts.firstStopEntered.await(5, TimeUnit.SECONDS))
            bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))
            producer2Returned.countDown()
            tts.releaseFirstStop.countDown()
        }

        try {
            runCurrent()
            assertTrue(producer2Returned.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("A", "跑步已恢复。"), tts.requests)
            val resumedIndex = tts.trace.indexOf("speak:跑步已恢复。")
            assertTrue(resumedIndex >= 0)
            assertTrue(tts.trace.withIndex().filter { it.value == "stop" }.all { it.index < resumedIndex })

            tts.complete("跑步已恢复。")
            runCurrent()
            assertEquals("跑步已恢复。", bus.latestMessage.value?.text)
            assertTrue(tts.trace.drop(resumedIndex + 1).none { it == "stop" })
        } finally {
            tts.releaseFirstStop.countDown()
            producer2.join(5_000)
            check(!producer2.isAlive)
        }
    }

    @Test
    fun `reset stop shutdown 清除 pending 并使旧 finally 无法提升`() = runTest {
        listOf<(SpeechBus) -> Unit>({ it.resetSession() }, { it.stop() }, { it.shutdown() })
            .forEachIndexed { index, hardStop ->
                val tts = FakeTtsEngine()
                val bus = newBus(tts)
                bus.enqueue(trigger("urgent-$index", "紧急-$index", SpeechPriority.URGENT))
                runCurrent()
                bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
                hardStop(bus)
                runCurrent()

                assertEquals(listOf("紧急-$index"), tts.requests.map { it.text })
                assertNull(bus.latestMessage.value)
            }
    }

    @Test
    fun `hard stop 用当前 pending 自身 ruleId 精确记录一次 CANCELLED`() = runTest {
        listOf<(SpeechBus) -> Unit>({ it.resetSession() }, { it.stop() }, { it.shutdown() })
            .forEachIndexed { index, hardStop ->
                val tts = FakeTtsEngine()
                val logger = FakeDiagnosticLogger()
                val bus = newBus(tts, logger)
                bus.enqueue(trigger("urgent-$index", "紧急-$index", SpeechPriority.URGENT))
                runCurrent()
                bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))

                hardStop(bus)
                runCurrent()

                assertEquals(
                    1,
                    outcomes(logger).count {
                        it == outcome("session_paused", SpeechOutcomeReason.CANCELLED)
                    },
                )
            }
    }

    @Test
    fun `被替换 pending 不会在 hard stop 时再次记取消`() = runTest {
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = newBus(tts, logger)
        bus.enqueue(trigger("urgent", "紧急", SpeechPriority.URGENT))
        runCurrent()
        bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
        bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))

        bus.stop()
        runCurrent()

        assertEquals(
            listOf(
                outcome("session_paused", SpeechOutcomeReason.SESSION_STATUS_SCHEDULED),
                outcome("session_resumed", SpeechOutcomeReason.SESSION_STATUS_SCHEDULED),
                outcome("session_paused", SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED),
                outcome("session_resumed", SpeechOutcomeReason.CANCELLED),
            ),
            outcomes(logger).filter { it.ruleId.startsWith("session_") },
        )
    }

    @Test
    fun `pending FINISHED 被任一 hard stop 清除时仍各自完成唯一 terminal flush`() = runTest {
        listOf<(SpeechBus) -> Unit>({ it.resetSession() }, { it.stop() }, { it.shutdown() })
            .forEachIndexed { index, hardStop ->
                val tts = FakeTtsEngine()
                val logger = FakeDiagnosticLogger()
                val bus = newBus(tts, logger)
                bus.enqueue(trigger("urgent-$index", "紧急-$index", SpeechPriority.URGENT))
                runCurrent()
                bus.enqueueSessionStatus(session(SessionSpeechEvent.FINISHED))

                hardStop(bus)
                runCurrent()

                assertEquals(
                    1,
                    outcomes(logger).count {
                        it == outcome("session_finished", SpeechOutcomeReason.CANCELLED)
                    },
                )
                assertEquals(1, logger.flushCalls)
                assertEquals(listOf("紧急-$index"), tts.requests.map { it.text })
            }
    }

    @Test
    fun `真实完成前不更新 latestMessage 且失败取消不写历史`() = runTest {
        val tts = FakeTtsEngine()
        val bus = SpeechBus(
            ttsEngine = tts,
            initialPolicy = SpeechPolicy(true, 4),
            nowMillis = { 10L },
            newMessageId = { "message" },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val item = trigger("normal", "同文", SpeechPriority.COACH)

        bus.enqueue(item)
        runCurrent()
        assertNull(bus.latestMessage.value)
        tts.completeNextWithFailure()
        runCurrent()
        bus.enqueue(item)
        runCurrent()
        tts.cancelNext()
        runCurrent()
        bus.enqueue(item)
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()

        assertEquals(3, tts.requests.size)
        assertEquals(CoachMessage("message", "同文", SpeechPriority.COACH, 10L), bus.latestMessage.value)
    }

    @Test
    fun `旧 operation 的迟到完成不会覆盖新消息或 priority`() = runTest {
        val tts = NonCooperativeTtsEngine()
        val bus = SpeechBus(
            ttsEngine = tts,
            initialPolicy = SpeechPolicy(true, 0),
            newMessageId = { "message-${tts.requests.size}" },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
        runCurrent()
        bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))
        runCurrent()

        tts.complete("跑步已恢复。")
        runCurrent()
        assertEquals("跑步已恢复。", bus.latestMessage.value?.text)
        tts.complete("跑步已暂停。")
        runCurrent()

        assertEquals("跑步已恢复。", bus.latestMessage.value?.text)
        bus.enqueue(trigger("after", "后续", SpeechPriority.APP))
        runCurrent()
        assertEquals(listOf("跑步已暂停。", "跑步已恢复。", "后续"), tts.requests)
    }

    @Test
    fun `拒绝矩阵写入精确 outcome reason`() = runTest {
        var now = 0L
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = SpeechBus(
            ttsEngine = tts,
            initialPolicy = SpeechPolicy(false, 4),
            nowMillis = { now },
            diagnosticLogger = logger,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        bus.enqueue(trigger("disabled", "禁用"))
        bus.configure(SpeechPolicy(true, 4))
        val oldGeneration = bus.sessionGenerationToken()
        bus.resetSession()
        bus.enqueue(trigger("stale", "过期"), oldGeneration)
        bus.enqueue(trigger("first", "重复", SpeechPriority.COACH))
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()
        now = 1_000L
        bus.enqueue(trigger("duplicate", "重复", SpeechPriority.COACH))
        bus.enqueue(trigger("urgent", "紧急", SpeechPriority.URGENT))
        runCurrent()
        bus.enqueue(trigger("blocked", "阻塞", SpeechPriority.APP))

        assertTrue(outcomes(logger).contains(outcome("disabled", SpeechOutcomeReason.POLICY_DISABLED)))
        assertTrue(outcomes(logger).contains(outcome("stale", SpeechOutcomeReason.STALE_SESSION_GENERATION)))
        assertTrue(outcomes(logger).contains(outcome("duplicate", SpeechOutcomeReason.DUPLICATE_INTERVAL)))
        assertTrue(outcomes(logger).contains(outcome("blocked", SpeechOutcomeReason.PRIORITY_BLOCKED)))
    }

    @Test
    fun `普通失败超时与取消映射为不同终态且取消不误记 TTS_FAILURE`() = runTest {
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = newBus(tts, logger)

        bus.enqueue(trigger("failure", "失败"))
        runCurrent()
        tts.completeNextWithFailure()
        runCurrent()
        bus.enqueue(trigger("timeout", "超时"))
        runCurrent()
        tts.completeNextWithFailure(PlaybackTimeoutException())
        runCurrent()
        bus.enqueue(trigger("cancelled", "取消"))
        runCurrent()
        tts.cancelNext()
        runCurrent()

        assertTrue(outcomes(logger).contains(outcome("failure", SpeechOutcomeReason.TTS_FAILURE)))
        assertTrue(outcomes(logger).contains(outcome("timeout", SpeechOutcomeReason.PLAYBACK_TIMEOUT)))
        assertTrue(outcomes(logger).contains(outcome("cancelled", SpeechOutcomeReason.CANCELLED)))
        assertFalse(outcomes(logger).contains(outcome("cancelled", SpeechOutcomeReason.TTS_FAILURE)))
    }

    @Test
    fun `禁用时会话状态不 schedule 且 configure 可动态恢复`() = runTest {
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = newBus(tts, logger, enabled = false)

        bus.enqueueSessionStatus(session(SessionSpeechEvent.PAUSED))
        runCurrent()
        assertEquals(listOf(outcome("session_paused", SpeechOutcomeReason.POLICY_DISABLED)), outcomes(logger))
        assertTrue(tts.requests.isEmpty())

        bus.configure(SpeechPolicy(enabled = true, minIntervalSec = 0))
        bus.enqueueSessionStatus(session(SessionSpeechEvent.RESUMED))
        runCurrent()
        assertEquals(listOf("跑步已恢复。"), tts.requests.map { it.text })
    }

    @Test
    fun `configure 动态更新最短间隔`() = runTest {
        var now = 0L
        val tts = FakeTtsEngine()
        val logger = FakeDiagnosticLogger()
        val bus = SpeechBus(
            ttsEngine = tts,
            initialPolicy = SpeechPolicy(enabled = true, minIntervalSec = 4),
            nowMillis = { now },
            diagnosticLogger = logger,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val repeated = trigger("repeat", "重复")
        bus.enqueue(repeated)
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()
        now = 1_000L
        bus.enqueue(repeated)
        assertTrue(outcomes(logger).contains(outcome("repeat", SpeechOutcomeReason.DUPLICATE_INTERVAL)))

        bus.configure(SpeechPolicy(enabled = true, minIntervalSec = 0))
        bus.enqueue(repeated)
        runCurrent()

        assertEquals(2, tts.requests.size)
    }

    @Test
    fun `FINISHED 的所有终态各自异步 flush 一次且 scheduled 不提前 flush`() = runTest {
        val terminals = listOf<(FakeTtsEngine) -> Unit>(
            { it.completeNextSuccessfully() },
            { it.completeNextWithFailure() },
            { it.completeNextWithFailure(PlaybackTimeoutException()) },
            { it.cancelNext() },
        )
        val expectedReasons = listOf(
            SpeechOutcomeReason.TTS_SUCCESS,
            SpeechOutcomeReason.TTS_FAILURE,
            SpeechOutcomeReason.PLAYBACK_TIMEOUT,
            SpeechOutcomeReason.CANCELLED,
        )
        terminals.zip(expectedReasons).forEach { (terminal, expectedReason) ->
            val flushGate = CompletableDeferred<Unit>()
            val logger = FakeDiagnosticLogger(flushGate)
            val tts = FakeTtsEngine()
            val bus = newBus(tts, logger)
            bus.enqueueSessionStatus(session(SessionSpeechEvent.FINISHED))
            assertEquals(0, logger.flushCalls)
            runCurrent()
            assertEquals(0, logger.flushCalls)

            terminal(tts)
            runCurrent()
            assertEquals(1, logger.flushCalls)
            assertEquals(
                1,
                outcomes(logger).count {
                    it == outcome("session_finished", expectedReason)
                },
            )

            bus.enqueueSessionStatus(session(SessionSpeechEvent.STARTED))
            runCurrent()
            assertEquals(2, tts.requests.size)
            flushGate.complete(Unit)
            runCurrent()
        }

        val disabledLogger = FakeDiagnosticLogger()
        val disabledBus = newBus(FakeTtsEngine(), disabledLogger, enabled = false)
        disabledBus.enqueueSessionStatus(session(SessionSpeechEvent.FINISHED))
        assertEquals(0, disabledLogger.flushCalls)
        runCurrent()
        assertEquals(1, disabledLogger.flushCalls)
        assertEquals(
            listOf(outcome("session_finished", SpeechOutcomeReason.POLICY_DISABLED)),
            outcomes(disabledLogger),
        )
    }

    @Test
    fun `FINISHED flush 最多等待两秒且不阻塞后续 actor 命令`() = runTest {
        val logger = FakeDiagnosticLogger(CompletableDeferred())
        val tts = FakeTtsEngine()
        val bus = newBus(tts, logger)
        bus.enqueueSessionStatus(session(SessionSpeechEvent.FINISHED))
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()
        bus.enqueueSessionStatus(session(SessionSpeechEvent.STARTED))
        runCurrent()

        assertEquals(1, logger.flushCalls)
        assertEquals(2, tts.requests.size)
        advanceTimeBy(2_001L)
        runCurrent()
        assertEquals(1, logger.flushCalls)
    }

    @Test
    fun `reset 清空已发布消息而 stop 不关闭引擎 shutdown 只由 actor 关闭`() = runTest {
        val tts = FakeTtsEngine()
        val bus = newBus(tts)
        bus.enqueue(trigger("done", "已完成"))
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()
        assertEquals("已完成", bus.latestMessage.value?.text)

        bus.resetSession()
        assertNull(bus.latestMessage.value)
        assertEquals(0, tts.stopCalls)
        runCurrent()
        assertEquals(1, tts.stopCalls)
        assertEquals(0, tts.shutdownCalls)

        bus.shutdown()
        assertEquals(0, tts.shutdownCalls)
        runCurrent()
        assertEquals(1, tts.shutdownCalls)
        assertFalse(tts.isSpeaking)
    }

    private fun TestScope.newBus(
        tts: TtsEngine,
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
        minIntervalSec: Int = 0,
        enabled: Boolean = true,
    ) = SpeechBus(
        ttsEngine = tts,
        initialPolicy = SpeechPolicy(enabled, minIntervalSec),
        newMessageId = { "message" },
        diagnosticLogger = logger,
        dispatcher = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
    )

    private fun trigger(
        ruleId: String,
        text: String,
        priority: SpeechPriority = SpeechPriority.APP,
    ) = RuleTrigger(ruleId, Severity.NORMAL, text, priority)

    private fun session(event: SessionSpeechEvent) = SessionSpeechTriggerFactory.create(event)

    private fun outcome(ruleId: String, reason: SpeechOutcomeReason) =
        DiagnosticEvent.SpeechOutcome(ruleId, reason)

    private fun outcomes(logger: FakeDiagnosticLogger) =
        logger.events.filterIsInstance<DiagnosticEvent.SpeechOutcome>()

    private class NonCooperativeTtsEngine : TtsEngine {
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val requests = mutableListOf<String>()
        override val isSpeaking: Boolean
            get() = gates.values.any { !it.isCompleted }

        override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
            requests += text
            val gate = CompletableDeferred<Unit>().also { gates[text] = it }
            withContext(NonCancellable) { gate.await() }
            return Result.success(Unit)
        }

        fun complete(text: String) {
            checkNotNull(gates[text]).complete(Unit)
        }

        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class BlockingStopTtsEngine : TtsEngine {
        private val lock = Any()
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val storedRequests = mutableListOf<String>()
        private val storedTrace = mutableListOf<String>()
        private var stopCalls = 0

        val firstStopEntered = CountDownLatch(1)
        val releaseFirstStop = CountDownLatch(1)
        val requests: List<String>
            get() = synchronized(lock) { storedRequests.toList() }
        val trace: List<String>
            get() = synchronized(lock) { storedTrace.toList() }
        override val isSpeaking: Boolean
            get() = synchronized(lock) { gates.values.any { !it.isCompleted } }

        override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
            val gate = synchronized(lock) {
                storedRequests += text
                storedTrace += "speak:$text"
                CompletableDeferred<Unit>().also { gates[text] = it }
            }
            gate.await()
            return Result.success(Unit)
        }

        fun complete(text: String) {
            synchronized(lock) { checkNotNull(gates[text]) }.complete(Unit)
        }

        override fun stop() {
            val shouldBlock = synchronized(lock) {
                storedTrace += "stop"
                stopCalls += 1
                stopCalls == 1
            }
            if (shouldBlock) {
                firstStopEntered.countDown()
                check(releaseFirstStop.await(5, TimeUnit.SECONDS))
            }
        }

        override fun shutdown() = Unit
    }
}
