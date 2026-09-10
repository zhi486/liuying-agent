package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.AudioFocusResult
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import com.healthyagent.running.testing.FakeDiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTtsEngineTest {

    @Test
    fun `初始化三秒未完成返回安全初始化超时诊断且不触碰焦点`() = runTest {
        val port = FakeTextToSpeechPort().apply {
            initialize = { CompletableDeferred<Result<Unit>>().await() }
        }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(port, focus, logger)

        val result = async { engine.speak("测试", SpeechPriority.APP) }
        advanceTimeBy(3_000L)
        runCurrent()

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is TtsInitializationTimeoutException)
        assertEquals(0, focus.requestCalls)
        assertTrue(port.spoken.isEmpty())
        val event = assertSingleProviderEvent(logger, TtsEventKind.INITIALIZATION_FAILED)
        assertEquals("TtsInitializationTimeoutException", event.failure?.type)
        assertEquals(SafeDiagnosticSummary.TTS_INITIALIZATION_FAILED.text, event.failure?.summary)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `初始化 Result failure 保留普通失败并只记录初始化失败`() = runTest {
        val original = IllegalStateException("Bearer secret-token")
        val port = FakeTextToSpeechPort().apply { initialize = { Result.failure(original) } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)

        assertEquals(original::class, result.exceptionOrNull()!!::class)
        assertEquals(original.message, result.exceptionOrNull()?.message)
        assertEquals(0, focus.requestCalls)
        assertTrue(port.spoken.isEmpty())
        val event = assertSingleProviderEvent(logger, TtsEventKind.INITIALIZATION_FAILED)
        assertEquals("IllegalStateException", event.failure?.type)
        assertEquals(SafeDiagnosticSummary.TTS_INITIALIZATION_FAILED.text, event.failure?.summary)
        assertFalse(event.failure?.summary.orEmpty().contains("secret-token"))
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `初始化直接抛普通异常时规范化为初始化 failure`() = runTest {
        val original = IllegalArgumentException("初始化炸了")
        val port = FakeTextToSpeechPort().apply { initialize = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)

        assertEquals(original::class, result.exceptionOrNull()!!::class)
        assertEquals(original.message, result.exceptionOrNull()?.message)
        assertEquals(0, focus.requestCalls)
        assertTrue(port.spoken.isEmpty())
        assertProviderKinds(logger, TtsEventKind.INITIALIZATION_FAILED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `初始化直接取消时原样抛出且不误记失败`() = runTest {
        val original = CancellationException("初始化取消")
        val port = FakeTextToSpeechPort().apply { initialize = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)
        }

        assertEquals(original.message, thrown.message)
        assertEquals(0, focus.requestCalls)
        assertTrue(port.spoken.isEmpty())
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `初始化 Result 中的取消也越过 Result 边界`() = runTest {
        val original = CancellationException("Result 取消")
        val port = FakeTextToSpeechPort().apply { initialize = { Result.failure(original) } }
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(port, FakeAudioFocusController(), logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `profile 精确设置助手语音且 Result failure 不申请焦点`() = runTest {
        val original = IllegalStateException("profile failure")
        val port = FakeTextToSpeechPort().apply { configureProfile = { Result.failure(original) } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(listOf(SpeechAudioProfile.ASSISTANT_SPEECH), port.profiles)
        assertEquals(0, focus.requestCalls)
        assertTrue(port.spoken.isEmpty())
        assertProviderKinds(logger, TtsEventKind.FAILED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `profile 直接抛普通异常时返回普通 failure 且不申请焦点`() = runTest {
        val original = IllegalArgumentException("profile throw")
        val port = FakeTextToSpeechPort().apply { configureProfile = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(0, focus.requestCalls)
        assertTrue(port.spoken.isEmpty())
        assertProviderKinds(logger, TtsEventKind.FAILED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `profile 取消原样抛出且不误记失败`() = runTest {
        val original = CancellationException("profile cancel")
        val port = FakeTextToSpeechPort().apply { configureProfile = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(0, focus.requestCalls)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `profile Result 中的取消也原样抛出`() = runTest {
        val original = CancellationException("profile result cancel")
        val port = FakeTextToSpeechPort().apply { configureProfile = { Result.failure(original) } }
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(port, FakeAudioFocusController(), logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `焦点普通异常按 denied no-op 继续播放且不伪造 released`() = runTest {
        val focus = FakeAudioFocusController().apply {
            requestBehavior = { throw IllegalStateException("focus unavailable") }
        }
        val port = FakeTextToSpeechPort()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(port, focus, logger)

        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()
        port.done("utterance-1")

        assertTrue(result.await().isSuccess)
        assertEquals(listOf("测试" to "utterance-1"), port.spoken)
        assertEquals(listOf(AudioFocusResult.DENIED), focusResults(logger))
        assertProviderKinds(logger, TtsEventKind.COMPLETED)
    }

    @Test
    fun `onDone 已赢终态但 lease 阻塞跨过 watchdog 时仍返回成功`() = runTest {
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val lease = BlockingAudioFocusLease(releaseEntered, allowRelease)
        val focus = FakeAudioFocusController().apply { requestBehavior = { lease } }
        val port = FakeTextToSpeechPort()
        val logger = FakeDiagnosticLogger()
        val engine = AndroidTtsEngine(
            port = port,
            audioFocusController = focus,
            diagnosticLogger = logger,
            initializationTimeoutMillis = 3_000L,
            playbackTimeoutMillis = 1L,
            newUtteranceId = { "utterance-1" },
        )
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()
        val callback = thread(name = "阻塞 TTS 完成回调") {
            port.done("utterance-1")
        }

        try {
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(1L)
            runCurrent()

            assertTrue(result.await().isSuccess)
            assertEquals(0, port.stopCalls)
        } finally {
            allowRelease.countDown()
            callback.join(5_000L)
            check(!callback.isAlive)
        }
        assertEquals(1, lease.releaseCalls)
        assertProviderKinds(logger, TtsEventKind.COMPLETED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `焦点 Error 原样冒泡且不尝试播放`() = runTest {
        val original = OutOfMemoryError("focus fatal")
        val focus = FakeAudioFocusController().apply { requestBehavior = { throw original } }
        val port = FakeTextToSpeechPort().apply { speakBehavior = { _, _ -> false } }
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<OutOfMemoryError> {
            newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertTrue(port.spoken.isEmpty())
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `焦点取消原样抛出且不播放不释放`() = runTest {
        val original = CancellationException("focus cancel")
        val focus = FakeAudioFocusController().apply { requestBehavior = { throw original } }
        val port = FakeTextToSpeechPort()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertTrue(port.spoken.isEmpty())
        assertTrue(focusResults(logger).isEmpty())
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
    }

    @Test
    fun `焦点 denied 仍播放并在真实完成后释放实际 lease`() = runTest {
        val focus = FakeAudioFocusController(granted = false)
        val port = FakeTextToSpeechPort()
        val logger = FakeDiagnosticLogger()
        val result = async { newEngine(port, focus, logger).speak("测试", SpeechPriority.APP) }
        runCurrent()

        assertEquals(listOf("测试" to "utterance-1"), port.spoken)
        assertFalse(result.isCompleted)
        port.done("utterance-1")

        assertTrue(result.await().isSuccess)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertEquals(listOf(AudioFocusResult.DENIED, AudioFocusResult.RELEASED), focusResults(logger))
    }

    @Test
    fun `speak 返回 false 立即失败而不等待 watchdog 并只释放一次`() = runTest {
        val port = FakeTextToSpeechPort().apply { speakBehavior = { _, _ -> false } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)

        assertTrue(result.isFailure)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `speak 直接抛普通异常时返回 failure 并只释放当前 lease`() = runTest {
        val original = IllegalStateException("speak throw")
        val port = FakeTextToSpeechPort().apply { speakBehavior = { _, _ -> throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `speak 直接取消时原样抛出并只释放当前 lease`() = runTest {
        val original = CancellationException("speak cancel")
        val port = FakeTextToSpeechPort().apply { speakBehavior = { _, _ -> throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(port, focus, logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `speak Error 原样冒泡并清理 lease 状态且后续请求可继续`() = runTest {
        val original = AssertionError("speak fatal")
        var speakCalls = 0
        var utteranceId = 0
        val port = FakeTextToSpeechPort().apply {
            speakBehavior = { _, _ ->
                speakCalls += 1
                if (speakCalls == 1) throw original
                true
            }
        }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = AndroidTtsEngine(
            port = port,
            audioFocusController = focus,
            diagnosticLogger = logger,
            initializationTimeoutMillis = 3_000L,
            playbackTimeoutMillis = 60_000L,
            newUtteranceId = { "utterance-${++utteranceId}" },
        )

        val thrown = assertSuspendThrows<AssertionError> {
            engine.speak("第一次", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(1, focus.leases[0].releaseCalls)
        assertReleasedCount(logger, 1)
        assertFalse(engine.isSpeaking)

        val second = async { engine.speak("第二次", SpeechPriority.APP) }
        runCurrent()
        assertFalse(second.isCompleted)
        port.done("utterance-2")
        assertTrue(second.await().isSuccess)
        assertEquals(1, focus.leases[1].releaseCalls)
    }

    @Test
    fun `底层接受后只在 onDone 成功并释放当前 lease`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(port, focus, logger)

        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        assertTrue(engine.isSpeaking)
        assertFalse(result.isCompleted)
        assertEquals(listOf(AudioFocusResult.GRANTED), focusResults(logger))
        port.done("utterance-1")

        assertTrue(result.await().isSuccess)
        assertFalse(engine.isSpeaking)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.COMPLETED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `onError 返回普通 failure 并完成唯一释放`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val result = async { newEngine(port, focus, logger).speak("测试", SpeechPriority.APP) }
        runCurrent()

        port.error("utterance-1", 17)

        assertTrue(result.await().isFailure)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `onStop 抛 CancellationException 并完成唯一释放`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val result = async { newEngine(port, focus, logger).speak("测试", SpeechPriority.APP) }
        runCurrent()

        port.stopped("utterance-1")

        assertSuspendThrows<CancellationException> { result.await() }
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `六十秒无回调会 stop 并返回 PlaybackTimeoutException`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val result = async { newEngine(port, focus, logger).speak("测试", SpeechPriority.APP) }
        runCurrent()

        advanceTimeBy(60_000L)
        runCurrent()

        assertTrue(result.await().exceptionOrNull() is PlaybackTimeoutException)
        assertEquals(1, port.stopCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.PLAYBACK_TIMEOUT)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `外部 coroutine cancel 结束播放且迟到 done 无效`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(port, focus, logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        result.cancel(CancellationException("caller cancel"))
        runCurrent()
        port.done("utterance-1")

        assertTrue(result.isCancelled)
        assertFalse(engine.isSpeaking)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `engine stop 取消挂起调用且迟到 done 不二次完成`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(port, focus, logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        engine.stop()
        port.done("utterance-1")

        assertSuspendThrows<CancellationException> { result.await() }
        assertEquals(1, port.stopCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `active shutdown 取消调用并各执行一次 stop shutdown release`() = runTest {
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(port, focus, logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        engine.shutdown()
        port.done("utterance-1")

        assertSuspendThrows<CancellationException> { result.await() }
        assertEquals(1, port.stopCalls)
        assertEquals(1, port.shutdownCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `旧 utterance 的迟到 callback 不释放新 request`() = runTest {
        var id = 0
        val port = FakeTextToSpeechPort()
        val focus = FakeAudioFocusController()
        val engine = AndroidTtsEngine(
            port = port,
            audioFocusController = focus,
            initializationTimeoutMillis = 3_000L,
            playbackTimeoutMillis = 60_000L,
            newUtteranceId = { "utterance-${++id}" },
        )
        val first = async { engine.speak("一", SpeechPriority.APP) }
        runCurrent()
        port.done("utterance-1")
        assertTrue(first.await().isSuccess)

        val second = async { engine.speak("二", SpeechPriority.APP) }
        runCurrent()
        port.done("utterance-1")

        assertFalse(second.isCompleted)
        assertEquals(1, focus.leases[0].releaseCalls)
        assertEquals(0, focus.leases[1].releaseCalls)
        port.done("utterance-2")
        assertTrue(second.await().isSuccess)
        assertEquals(1, focus.leases[1].releaseCalls)
    }

    private fun newEngine(
        port: FakeTextToSpeechPort,
        focus: FakeAudioFocusController,
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
    ) = AndroidTtsEngine(
        port = port,
        audioFocusController = focus,
        diagnosticLogger = logger,
        initializationTimeoutMillis = 3_000L,
        playbackTimeoutMillis = 60_000L,
        newUtteranceId = { "utterance-1" },
    )

    private fun assertSingleProviderEvent(
        logger: FakeDiagnosticLogger,
        kind: TtsEventKind,
    ): DiagnosticEvent.TtsProviderEvent {
        val events = logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
        assertEquals(listOf(kind), events.map { it.kind })
        return events.single().also { assertEquals(TtsProvider.ANDROID, it.provider) }
    }

    private fun assertProviderKinds(logger: FakeDiagnosticLogger, vararg kinds: TtsEventKind) {
        val events = logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
        assertEquals(kinds.toList(), events.map { it.kind })
        assertTrue(events.all { it.provider == TtsProvider.ANDROID })
    }

    private fun focusResults(logger: FakeDiagnosticLogger): List<AudioFocusResult> =
        logger.events.filterIsInstance<DiagnosticEvent.AudioFocusEvent>().map { it.result }

    private fun assertReleasedCount(logger: FakeDiagnosticLogger, expected: Int) {
        assertEquals(expected, focusResults(logger).count { it == AudioFocusResult.RELEASED })
    }

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }

    private class FakeTextToSpeechPort : TextToSpeechPort {
        var initialize: suspend () -> Result<Unit> = { Result.success(Unit) }
        var configureProfile: (SpeechAudioProfile) -> Result<Unit> = { Result.success(Unit) }
        var speakBehavior: (String, String) -> Boolean = { _, _ -> true }
        val profiles = mutableListOf<SpeechAudioProfile>()
        val spoken = mutableListOf<Pair<String, String>>()
        var stopCalls = 0
            private set
        var shutdownCalls = 0
            private set
        private var listener: TextToSpeechPort.Listener? = null

        override suspend fun awaitInitialization(): Result<Unit> = initialize()

        override fun setAudioProfile(profile: SpeechAudioProfile): Result<Unit> {
            profiles += profile
            return configureProfile(profile)
        }

        override fun setListener(listener: TextToSpeechPort.Listener) {
            this.listener = listener
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text to utteranceId
            return speakBehavior(text, utteranceId)
        }

        fun done(utteranceId: String) {
            listener!!.onDone(utteranceId)
        }

        fun error(utteranceId: String, errorCode: Int?) {
            listener!!.onError(utteranceId, errorCode)
        }

        fun stopped(utteranceId: String) {
            listener!!.onStop(utteranceId)
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun shutdown() {
            shutdownCalls += 1
        }
    }

    private class FakeAudioFocusController(
        private val granted: Boolean = true,
    ) : AudioFocusController {
        var requestBehavior: () -> AudioFocusLease = {
            FakeAudioFocusLease(granted).also { leases += it }
        }
        var requestCalls = 0
            private set
        val leases = mutableListOf<FakeAudioFocusLease>()

        override fun request(): AudioFocusLease {
            requestCalls += 1
            return requestBehavior()
        }
    }

    private class FakeAudioFocusLease(
        override val granted: Boolean,
    ) : AudioFocusLease {
        var releaseCalls = 0
            private set

        override fun release() {
            releaseCalls += 1
        }
    }

    private class BlockingAudioFocusLease(
        private val releaseEntered: CountDownLatch,
        private val allowRelease: CountDownLatch,
    ) : AudioFocusLease {
        override val granted: Boolean = true
        var releaseCalls = 0
            private set

        override fun release() {
            releaseCalls += 1
            releaseEntered.countDown()
            check(allowRelease.await(5, TimeUnit.SECONDS))
        }
    }
}
