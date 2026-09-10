package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeTtsEngineTest {
    @Test
    fun `cloud 缺失只记录 SKIPPED 并由 actor 调用 local`() = runTest {
        val local = ScriptedTtsEngine(Step.Success)
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(local = local, cloud = null, logger = logger)

        val call = startSpeak(engine)
        assertTrue(local.requests.isEmpty())
        runCurrent()

        assertTrue(call.await().isSuccess)
        assertEquals(listOf("测试"), local.requests)
        assertEquals(
            listOf(DiagnosticEvent.TtsProviderEvent.missingConfiguration()),
            logger.events,
        )
    }

    @Test
    fun `cloud 普通失败只由 actor 记录一次 fallback 并调用 local 恰好一次`() = runTest {
        val cloud = ScriptedTtsEngine(Step.Failure(IllegalStateException("云端失败")))
        val local = ScriptedTtsEngine(Step.Success)
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(local, cloud, logger)

        val call = startSpeak(engine)
        runCurrent()

        assertTrue(call.await().isSuccess)
        assertEquals(listOf("测试"), cloud.requests)
        assertEquals(listOf("测试"), local.requests)
        assertEquals(
            1,
            logger.events.count { it == DiagnosticEvent.TtsProviderEvent.localFallback() },
        )
    }

    @Test
    fun `cloud 成功时不调用 local`() = runTest {
        val cloud = ScriptedTtsEngine(Step.Success)
        val local = ScriptedTtsEngine()
        val engine = newEngine(local, cloud)

        val call = startSpeak(engine)
        runCurrent()

        assertTrue(call.await().isSuccess)
        assertEquals(listOf("测试"), cloud.requests)
        assertTrue(local.requests.isEmpty())
    }

    @Test
    fun `cloud CancellationException 以同一个实例及时完成且绝不回退`() = runTest {
        val cancellation = CancellationException("cloud cancelled")
        val cloud = ScriptedTtsEngine(Step.Throw(cancellation))
        val local = ScriptedTtsEngine()
        val logger = FakeDiagnosticLogger()
        val engine = CompositeTtsEngine(
            local = local,
            cloud = cloud,
            diagnosticLogger = logger,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertSame(
            cancellation,
            failureOf {
                engine.speak("测试", SpeechPriority.APP)
            },
        )
        assertTrue(local.requests.isEmpty())
        assertFalse(logger.events.contains(DiagnosticEvent.TtsProviderEvent.localFallback()))

        val timely = CompositeTtsEngine(
            local = ScriptedTtsEngine(),
            cloud = ScriptedTtsEngine(Step.Throw(CancellationException("timely cloud cancel"))),
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertTrue(
            failureOf {
                withTimeout(1_000) { timely.speak("及时取消", SpeechPriority.APP) }
            } is CancellationException,
        )
    }

    @Test
    fun `local CancellationException 以同一个实例及时完成`() = runTest {
        val cancellation = CancellationException("local cancelled")
        val local = ScriptedTtsEngine(Step.Throw(cancellation))
        val engine = CompositeTtsEngine(
            local = local,
            cloud = null,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertSame(
            cancellation,
            failureOf {
                engine.speak("测试", SpeechPriority.APP)
            },
        )
        assertEquals(listOf("测试"), local.requests)

        val timely = CompositeTtsEngine(
            local = ScriptedTtsEngine(Step.Throw(CancellationException("timely local cancel"))),
            cloud = null,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertTrue(
            failureOf {
                withTimeout(1_000) { timely.speak("及时取消", SpeechPriority.APP) }
            } is CancellationException,
        )
    }

    @Test
    fun `Start 尚未消费时 caller 取消使两个 provider 都保持零调用`() = runTest {
        val cloud = ScriptedTtsEngine(Step.Success)
        val local = ScriptedTtsEngine(Step.Success)
        val engine = newEngine(local, cloud)

        val call = startSpeak(engine)
        call.cancel()
        runCurrent()

        assertTrue(call.isCancelled)
        assertTrue(cloud.requests.isEmpty())
        assertTrue(local.requests.isEmpty())
    }

    @Test
    fun `cloud terminal 已就绪后 caller 取消共享 invalidated 并阻止 fallback`() = runTest {
        val cloudPending = PendingStep(nonCancellable = true)
        val cloud = ScriptedTtsEngine(Step.Pending(cloudPending))
        val local = ScriptedTtsEngine(Step.Success)
        val engine = newEngine(local, cloud)
        val call = startSpeak(engine)
        runCurrent()

        cloudPending.complete(Result.failure(IllegalStateException("迟到失败")))
        call.cancel()
        runCurrent()

        assertTrue(call.isCancelled)
        assertTrue(local.requests.isEmpty())
    }

    @Test
    fun `cloud terminal 已就绪后 stop 共享 generation 并阻止 fallback`() = runTest {
        val cloudPending = PendingStep(nonCancellable = true)
        val cloud = ScriptedTtsEngine(Step.Pending(cloudPending))
        val local = ScriptedTtsEngine(Step.Success)
        val engine = newEngine(local, cloud)
        val call = startSpeak(engine)
        runCurrent()

        cloudPending.complete(Result.failure(IllegalStateException("迟到失败")))
        engine.stop()
        runCurrent()

        assertTrue(failureOf { call.await() } is CancellationException)
        assertEquals(1, cloud.stopCalls)
        assertTrue(local.requests.isEmpty())
    }

    @Test
    fun `cloud synthesis 与 playback 阶段 stop 都只取消当前 cloud 且迟到不回退`() = runTest {
        listOf("synthesis", "playback").forEach { phase ->
            val pending = PendingStep(nonCancellable = true)
            val cloud = ScriptedTtsEngine(Step.Pending(pending))
            val local = ScriptedTtsEngine(Step.Success)
            val engine = newEngine(local, cloud)
            val call = startSpeak(engine, phase)
            runCurrent()

            engine.stop()
            runCurrent()

            assertTrue(failureOf { call.await() } is CancellationException)
            assertEquals(1, cloud.stopCalls)
            assertEquals(0, local.stopCalls)
            pending.complete(Result.failure(IllegalStateException("$phase 迟到失败")))
            runCurrent()
            assertTrue(local.requests.isEmpty())
        }
    }

    @Test
    fun `local fallback playback 阶段 stop 只取消 local 且迟到不影响新请求`() = runTest {
        val localPending = PendingStep(nonCancellable = true)
        val cloud = ScriptedTtsEngine(
            Step.Failure(IllegalStateException("触发回退")),
            Step.Success,
        )
        val local = ScriptedTtsEngine(Step.Pending(localPending))
        val engine = newEngine(local, cloud)
        val first = startSpeak(engine, "旧请求")
        runCurrent()
        assertEquals(listOf("旧请求"), local.requests)

        engine.stop()
        runCurrent()
        assertTrue(failureOf { first.await() } is CancellationException)
        assertEquals(0, cloud.stopCalls)
        assertEquals(1, local.stopCalls)

        val second = startSpeak(engine, "新请求")
        runCurrent()
        assertTrue(second.await().isSuccess)
        localPending.complete(Result.failure(IllegalStateException("旧 local 迟到")))
        runCurrent()
        assertEquals(listOf("旧请求"), local.requests)
    }

    @Test
    fun `shutdown 在 cloud 与 local fallback 阶段取消当前项且清理 provider 各一次`() = runTest {
        listOf(false, true).forEach { fallbackActive ->
            val pending = PendingStep(nonCancellable = true)
            val cloud = if (fallbackActive) {
                ScriptedTtsEngine(Step.Failure(IllegalStateException("回退")))
            } else {
                ScriptedTtsEngine(Step.Pending(pending))
            }
            val local = if (fallbackActive) {
                ScriptedTtsEngine(Step.Pending(pending))
            } else {
                ScriptedTtsEngine()
            }
            val engine = newEngine(local, cloud)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                engine.speak("测试", SpeechPriority.APP)
            }
            runCurrent()

            engine.shutdown()
            engine.shutdown()
            runCurrent()

            assertTrue(failureOf { call.await() } is CancellationException)
            assertEquals(if (fallbackActive) 0 else 1, cloud.stopCalls)
            assertEquals(if (fallbackActive) 1 else 0, local.stopCalls)
            assertEquals(1, cloud.shutdownCalls)
            assertEquals(1, local.shutdownCalls)
            pending.complete(Result.failure(IllegalStateException("shutdown 后迟到")))
            runCurrent()
            if (!fallbackActive) assertTrue(local.requests.isEmpty())
        }
    }

    @Test
    fun `Start 先入队再 shutdown 时调用及时异常完成且 provider 零调用`() = runTest {
        supervisorScope {
            val cloud = ScriptedTtsEngine(Step.Success)
            val local = ScriptedTtsEngine(Step.Success)
            val engine = newEngine(local, cloud)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                engine.speak("测试", SpeechPriority.APP)
            }

            engine.shutdown()
            runCurrent()

            val failure = failureOf { withTimeout(1_000) { call.await() } }
            assertTrue(failure is CancellationException || failure is IllegalStateException)
            assertTrue(cloud.requests.isEmpty())
            assertTrue(local.requests.isEmpty())
        }
    }

    @Test
    fun `command channel 已关闭后 speak 立即异常完成而不悬挂`() = runTest {
        val cloud = ScriptedTtsEngine(Step.Success)
        val local = ScriptedTtsEngine(Step.Success)
        val engine = newEngine(local, cloud)
        engine.shutdown()
        runCurrent()

        val failure = failureOf {
            withTimeout(1_000) { engine.speak("关闭后", SpeechPriority.APP) }
        }

        assertTrue(failure is IllegalStateException)
        assertTrue(cloud.requests.isEmpty())
        assertTrue(local.requests.isEmpty())
    }

    @Test
    fun `active provider stop 普通异常不会杀死 actor 且诊断安全`() = runTest {
        val secret = "authorization-secret"
        val pending = PendingStep(nonCancellable = true)
        val cloud = ScriptedTtsEngine(
            Step.Pending(pending),
            Step.Success,
            stopFailure = IllegalStateException(secret),
        )
        val local = ScriptedTtsEngine()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(local, cloud, logger)
        val first = startSpeak(engine, "旧请求")
        runCurrent()

        engine.stop()
        runCurrent()
        assertTrue(failureOf { first.await() } is CancellationException)

        val second = startSpeak(engine, "新请求")
        runCurrent()
        assertTrue(second.await().isSuccess)
        val failureEvent = logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
            .single { it.provider == TtsProvider.COMPOSITE && it.kind == TtsEventKind.FAILED }
        assertEquals(SafeDiagnosticSummary.TTS_PLAYBACK_FAILED.text, failureEvent.failure?.summary)
        assertFalse(failureEvent.failure.toString().contains(secret))
        pending.complete(Result.success(Unit))
        runCurrent()
    }

    @Test
    fun `cloud shutdown 普通异常不跳过 local 且重复 shutdown 幂等`() = runTest {
        val cloud = ScriptedTtsEngine(shutdownFailure = IllegalStateException("cloud-secret"))
        val local = ScriptedTtsEngine(shutdownFailure = CancellationException("local cancel"))
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(local, cloud, logger)

        engine.shutdown()
        engine.shutdown()
        runCurrent()

        assertEquals(1, cloud.shutdownCalls)
        assertEquals(1, local.shutdownCalls)
        val failures = logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
            .filter { it.provider == TtsProvider.COMPOSITE && it.kind == TtsEventKind.FAILED }
        assertEquals(1, failures.size)
        assertFalse(failures.single().failure.toString().contains("cloud-secret"))
    }

    @Test
    fun `active provider stop 抛 Error 后旧请求收尾且 actor 仍处理下一请求`() = runTest {
        supervisorScope {
            val pending = PendingStep(nonCancellable = true)
            val fatal = AssertionError("stop fatal")
            val cloud = ScriptedTtsEngine(
                Step.Pending(pending),
                Step.Success,
                stopFailure = fatal,
            )
            val local = ScriptedTtsEngine()
            val logger = FakeDiagnosticLogger()
            val engine = newEngine(local, cloud, logger)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                engine.speak("旧请求", SpeechPriority.APP)
            }
            runCurrent()

            engine.stop()
            runCurrent()

            assertTrue(failureOf { first.await() } is CancellationException)
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                engine.speak("新请求", SpeechPriority.APP)
            }
            runCurrent()
            assertTrue(withTimeout(1_000) { second.await() }.isSuccess)
            assertTrue(
                logger.events.none {
                    it is DiagnosticEvent.TtsProviderEvent &&
                        it.provider == TtsProvider.COMPOSITE &&
                        it.kind == TtsEventKind.FAILED
                },
            )
            pending.complete(Result.success(Unit))
            runCurrent()
        }
    }

    @Test
    fun `cloud shutdown 抛 Error 仍清理 local 和 pending 且重复 shutdown 幂等`() = runTest {
        supervisorScope {
            val pending = PendingStep(nonCancellable = true)
            val cloud = ScriptedTtsEngine(
                Step.Pending(pending),
                shutdownFailure = AssertionError("shutdown fatal"),
            )
            val local = ScriptedTtsEngine()
            val logger = FakeDiagnosticLogger()
            val engine = newEngine(local, cloud, logger)
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                engine.speak("active", SpeechPriority.APP)
            }
            runCurrent()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                engine.speak("queued", SpeechPriority.APP)
            }

            engine.shutdown()
            engine.shutdown()
            runCurrent()

            assertTrue(failureOf { active.await() } is CancellationException)
            assertTrue(
                failureOf { withTimeout(1_000) { queued.await() } } is IllegalStateException,
            )
            assertEquals(1, cloud.shutdownCalls)
            assertEquals(1, local.shutdownCalls)
            assertTrue(
                logger.events.none {
                    it is DiagnosticEvent.TtsProviderEvent &&
                        it.provider == TtsProvider.COMPOSITE &&
                        it.kind == TtsEventKind.FAILED
                },
            )
            assertTrue(
                failureOf {
                    withTimeout(1_000) { engine.speak("关闭后", SpeechPriority.APP) }
                } is IllegalStateException,
            )
            pending.complete(Result.success(Unit))
            runCurrent()
        }
    }

    @Test
    fun `cloud 抛 Error 原对象冒泡不回退且 actor 仍可处理下一请求`() = runTest {
        val fatal = AssertionError("cloud fatal")
        val cloud = ScriptedTtsEngine(Step.Throw(fatal), Step.Success)
        val local = ScriptedTtsEngine(Step.Success)
        val engine = CompositeTtsEngine(
            local = local,
            cloud = cloud,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertSame(
            fatal,
            failureOf { engine.speak("fatal", SpeechPriority.APP) },
        )
        assertTrue(local.requests.isEmpty())
        assertTrue(engine.speak("next", SpeechPriority.APP).isSuccess)
        assertEquals(listOf("fatal", "next"), cloud.requests)
    }

    @Test
    fun `local 抛 Error 原对象冒泡且 actor 仍可处理下一请求`() = runTest {
        val fatal = AssertionError("local fatal")
        val local = ScriptedTtsEngine(Step.Throw(fatal), Step.Success)
        val engine = CompositeTtsEngine(
            local = local,
            cloud = null,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertSame(
            fatal,
            failureOf { engine.speak("fatal", SpeechPriority.APP) },
        )
        assertTrue(engine.speak("next", SpeechPriority.APP).isSuccess)
        assertEquals(listOf("fatal", "next"), local.requests)
    }

    @Test
    fun `active 发布窄窗口的 stale ChildCancelled 不得覆盖 stop 或 shutdown 终态`() = runTest {
        assertPublicationWindowControlTerminal(shutdown = false)
        assertPublicationWindowControlTerminal(shutdown = true)
    }

    @Test
    fun `stop 的迟到 cloud terminal 不会完成或回退到新请求`() = runTest {
        val oldPending = PendingStep(nonCancellable = true)
        val cloud = ScriptedTtsEngine(Step.Pending(oldPending), Step.Success)
        val local = ScriptedTtsEngine(Step.Success)
        val engine = newEngine(local, cloud)
        val oldCall = startSpeak(engine, "旧请求")
        runCurrent()
        engine.stop()
        runCurrent()
        assertTrue(failureOf { oldCall.await() } is CancellationException)

        val newCall = startSpeak(engine, "新请求")
        runCurrent()
        assertTrue(newCall.await().isSuccess)
        oldPending.complete(Result.failure(IllegalStateException("迟到 terminal")))
        runCurrent()

        assertEquals(listOf("旧请求", "新请求"), cloud.requests)
        assertTrue(local.requests.isEmpty())
    }

    @Test
    fun `Fish player start Result failure 会驱动 Composite local fallback 恰好一次`() = runTest {
        val synthesized = File.createTempFile("composite-fish-", ".mp3")
        try {
            val fish = FishAudioTtsEngine(
                synthesisClient = object : FishSynthesisClient {
                    override suspend fun synthesize(text: String) = synthesized
                    override fun cancel() = Unit
                    override fun shutdown() = Unit
                },
                audioPlayerFactory = {
                    object : AudioPlayerPort {
                        override fun start(
                            file: File,
                            profile: SpeechAudioProfile,
                            listener: AudioPlayerPort.Listener,
                        ) = Result.failure<Unit>(IllegalStateException("start failed"))

                        override fun stop() = Unit
                        override fun release() = Unit
                    }
                },
                audioFocusController = object : AudioFocusController {
                    override fun request() = object : AudioFocusLease {
                        override val granted = true
                        override fun release() = Unit
                    }
                },
            )
            val local = ScriptedTtsEngine(Step.Success)
            val logger = FakeDiagnosticLogger()
            val engine = newEngine(local, fish, logger)

            val call = startSpeak(engine)
            runCurrent()

            assertTrue(call.await().isSuccess)
            assertEquals(listOf("测试"), local.requests)
            assertEquals(
                1,
                logger.events.count { it == DiagnosticEvent.TtsProviderEvent.localFallback() },
            )
        } finally {
            synthesized.delete()
        }
    }

    private fun TestScope.newEngine(
        local: TtsEngine,
        cloud: TtsEngine?,
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
    ) = CompositeTtsEngine(
        local = local,
        cloud = cloud,
        diagnosticLogger = logger,
        actorDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.startSpeak(
        engine: CompositeTtsEngine,
        text: String = "测试",
    ) = async(start = CoroutineStart.UNDISPATCHED) {
        engine.speak(text, SpeechPriority.APP)
    }

    private suspend fun TestScope.assertPublicationWindowControlTerminal(shutdown: Boolean) {
        val publicationWindow = CountDownLatch(1)
        val generationAdvanced = CountDownLatch(1)
        val childCancelledSent = CountDownLatch(1)
        val cloud = ScriptedTtsEngine(Step.Success)
        val local = ScriptedTtsEngine()
        val engine = CompositeTtsEngine(
            local = local,
            cloud = cloud,
            diagnosticLogger = FakeDiagnosticLogger(),
            actorDispatcher = StandardTestDispatcher(testScheduler),
            beforeActivePublication = {
                publicationWindow.countDown()
                check(generationAdvanced.await(5, TimeUnit.SECONDS))
            },
            afterGenerationAdvanced = {
                generationAdvanced.countDown()
                check(childCancelledSent.await(5, TimeUnit.SECONDS))
            },
            afterChildCancelledSent = childCancelledSent::countDown,
        )
        val control = thread(name = if (shutdown) "shutdown-window" else "stop-window") {
            check(publicationWindow.await(5, TimeUnit.SECONDS))
            if (shutdown) engine.shutdown() else engine.stop()
        }
        val call = startSpeak(engine)

        runCurrent()
        control.join(5_000)
        check(!control.isAlive)
        runCurrent()

        val failure = failureOf { call.await() }
        assertTrue(failure is CancellationException)
        assertEquals(
            if (shutdown) "Composite TTS 引擎已关闭" else "Composite TTS 引擎已停止",
            failure.message,
        )
        assertTrue(cloud.requests.isEmpty())
        assertTrue(local.requests.isEmpty())
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        }
        return checkNotNull(failure) { "预期调用异常完成" }
    }

    private sealed interface Step {
        data object Success : Step
        data class Failure(val error: Throwable) : Step
        data class Throw(val error: Throwable) : Step
        data class Pending(val value: PendingStep) : Step
    }

    private class PendingStep(
        private val nonCancellable: Boolean,
    ) {
        private val terminal = CompletableDeferred<Result<Unit>>()

        suspend fun await(): Result<Unit> = if (nonCancellable) {
            withContext(NonCancellable) { terminal.await() }
        } else {
            terminal.await()
        }

        fun complete(result: Result<Unit>) {
            check(terminal.complete(result))
        }
    }

    private class ScriptedTtsEngine(
        vararg steps: Step,
        private val stopFailure: Throwable? = null,
        private val shutdownFailure: Throwable? = null,
    ) : TtsEngine {
        private val scripted = ArrayDeque(steps.toList())
        private val storedRequests = mutableListOf<String>()
        var stopCalls = 0
            private set
        var shutdownCalls = 0
            private set

        val requests: List<String>
            get() = storedRequests.toList()

        override val isSpeaking: Boolean
            get() = false

        override suspend fun speak(text: String, priority: SpeechPriority): Result<Unit> {
            storedRequests += text
            val step = if (scripted.isEmpty()) Step.Success else scripted.removeFirst()
            return when (step) {
                Step.Success -> Result.success(Unit)
                is Step.Failure -> Result.failure(step.error)
                is Step.Throw -> throw step.error
                is Step.Pending -> step.value.await()
            }
        }

        override fun stop() {
            stopCalls += 1
            stopFailure?.let { throw it }
        }

        override fun shutdown() {
            shutdownCalls += 1
            shutdownFailure?.let { throw it }
        }
    }
}
