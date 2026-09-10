package com.healthyagent.running.speech

import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.AudioFocusResult
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SafeDiagnosticSummary
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.diagnostics.TtsProvider
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FishAudioTtsEngineTest {

    @Test
    fun `合成挂起期间不申请焦点且 caller 取消只取消 client 不创建 player`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val synthesis = FakeFishSynthesisClient().apply {
            behavior = {
                entered.complete(Unit)
                CompletableDeferred<File>().await()
            }
        }
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, focus, logger)
        val original = CancellationException("caller cancel")

        val result = async { engine.speak("测试", SpeechPriority.APP) }
        entered.await()
        assertEquals(0, focus.requestCalls)
        assertTrue(players.players.isEmpty())
        assertFalse(engine.isSpeaking)

        result.cancel(original)
        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(1, synthesis.cancelCalls)
        assertTrue(players.players.isEmpty())
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `合成完成后才申请独立 lease 并以助手语音启动且只在 completion 成功`() = runTest {
        val trace = mutableListOf<String>()
        val synthesis = FakeFishSynthesisClient().apply {
            behavior = {
                trace += "synthesize"
                audioFile
            }
        }
        val focus = FakeAudioFocusController().apply { onRequest = { trace += "focus" } }
        val players = FakePlayerFactory().apply { onCreate = { trace += "factory" } }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, focus, logger)

        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        val player = players.players.single()
        assertEquals(listOf("synthesize", "focus", "factory", "start"), trace + player.trace)
        assertEquals(audioFile, player.startedFile)
        assertEquals(SpeechAudioProfile.ASSISTANT_SPEECH, player.startedProfile)
        assertTrue(engine.isSpeaking)
        assertFalse(result.isCompleted)
        assertEquals(listOf(AudioFocusResult.GRANTED), focusResults(logger))

        player.complete()

        assertTrue(result.await().isSuccess)
        assertFalse(engine.isSpeaking)
        assertEquals(1, player.releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.COMPLETED)
        assertEquals(listOf(AudioFocusResult.GRANTED, AudioFocusResult.RELEASED), focusResults(logger))
    }

    @Test
    fun `合成普通异常转 Result failure 且只记录安全失败`() = runTest {
        val original = IllegalStateException("Bearer fish-secret")
        val synthesis = FakeFishSynthesisClient().apply { behavior = { throw original } }
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(synthesis, players, focus, logger).speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(0, focus.requestCalls)
        assertTrue(players.players.isEmpty())
        val event = assertLastProviderEvent(logger, TtsEventKind.FAILED)
        assertEquals("IllegalStateException", event.failure?.type)
        assertEquals(SafeDiagnosticSummary.FISH_SYNTHESIS_FAILED.text, event.failure?.summary)
        assertFalse(event.failure?.summary.orEmpty().contains("fish-secret"))
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `合成 Error 原样冒泡且不取得播放资源`() = runTest {
        val original = AssertionError("synthesis fatal")
        val synthesis = FakeFishSynthesisClient().apply { behavior = { throw original } }
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<AssertionError> {
            newEngine(synthesis, players, focus, logger).speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(0, focus.requestCalls)
        assertTrue(players.players.isEmpty())
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertReleasedCount(logger, 0)
    }

    @Test
    fun `player factory 普通异常转 failure 并释放已取得 lease`() = runTest {
        val original = IllegalArgumentException("factory failed")
        val synthesis = FakeFishSynthesisClient()
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply { createBehavior = { throw original } }
        val logger = FakeDiagnosticLogger()

        val result = newEngine(synthesis, players, focus, logger).speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `player factory Error 原样冒泡并释放已取得 lease`() = runTest {
        val original = AssertionError("factory fatal")
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply { createBehavior = { throw original } }
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<AssertionError> {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `player start Result failure 立即失败并唯一释放 player 与 lease`() = runTest {
        val original = IllegalStateException("start rejected")
        val players = FakePlayerFactory().apply { nextStartBehavior = { Result.failure(original) } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(FakeFishSynthesisClient(), players, focus, logger)
            .speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(0, players.players.single().stopCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `player start 直接普通异常转 failure 并唯一清理`() = runTest {
        val original = IllegalArgumentException("start throw")
        val players = FakePlayerFactory().apply { nextStartBehavior = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val result = newEngine(FakeFishSynthesisClient(), players, focus, logger)
            .speak("测试", SpeechPriority.APP)

        assertSame(original, result.exceptionOrNull())
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
    }

    @Test
    fun `player start 取消原样传播且不伪装失败并唯一清理`() = runTest {
        val original = CancellationException("start cancel")
        val players = FakePlayerFactory().apply { nextStartBehavior = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `player start Error 原样冒泡且资源仍唯一清理`() = runTest {
        val original = AssertionError("start fatal")
        val players = FakePlayerFactory().apply { nextStartBehavior = { throw original } }
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<AssertionError> {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `焦点 denied 仍播放并释放实际 lease`() = runTest {
        val focus = FakeAudioFocusController(granted = false)
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val result = async {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }
        runCurrent()

        assertEquals(SpeechAudioProfile.ASSISTANT_SPEECH, players.players.single().startedProfile)
        players.players.single().complete()

        assertTrue(result.await().isSuccess)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertEquals(listOf(AudioFocusResult.DENIED, AudioFocusResult.RELEASED), focusResults(logger))
    }

    @Test
    fun `焦点普通异常按 denied no-op 继续播放且不伪造 released`() = runTest {
        val focus = FakeAudioFocusController().apply {
            requestBehavior = { throw IllegalStateException("focus unavailable") }
        }
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val result = async {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }
        runCurrent()

        players.players.single().complete()

        assertTrue(result.await().isSuccess)
        assertEquals(listOf(AudioFocusResult.DENIED), focusResults(logger))
        assertEquals(1, players.players.single().releaseCalls)
    }

    @Test
    fun `焦点取消原样传播且 player 不创建`() = runTest {
        val original = CancellationException("focus cancel")
        val focus = FakeAudioFocusController().apply { requestBehavior = { throw original } }
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<CancellationException> {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertTrue(players.players.isEmpty())
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertTrue(focusResults(logger).isEmpty())
    }

    @Test
    fun `焦点 Error 原样冒泡且 player 不创建`() = runTest {
        val original = OutOfMemoryError("focus fatal")
        val focus = FakeAudioFocusController().apply { requestBehavior = { throw original } }
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()

        val thrown = assertSuspendThrows<OutOfMemoryError> {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }

        assertSame(original, thrown)
        assertTrue(players.players.isEmpty())
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertTrue(focusResults(logger).isEmpty())
    }

    @Test
    fun `onError 返回普通 failure 并完成唯一释放`() = runTest {
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val result = async {
            newEngine(FakeFishSynthesisClient(), players, focus, logger)
                .speak("测试", SpeechPriority.APP)
        }
        runCurrent()

        players.players.single().fail(17, 23)

        assertTrue(result.await().isFailure)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.FAILED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `播放超时 stop release lease 各一次并返回 PlaybackTimeoutException`() = runTest {
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val result = async {
            newEngine(
                FakeFishSynthesisClient(),
                players,
                focus,
                logger,
                playbackTimeoutMillis = 10L,
            ).speak("测试", SpeechPriority.APP)
        }
        runCurrent()

        advanceTimeBy(10L)
        runCurrent()

        assertTrue(result.await().exceptionOrNull() is PlaybackTimeoutException)
        assertEquals(1, players.players.single().stopCalls)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.PLAYBACK_TIMEOUT)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `completion 已赢终态但 release 阻塞跨过 watchdog 仍返回成功`() = runTest {
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply {
            onPlayerCreated = {
                it.releaseBehavior = {
                    releaseEntered.countDown()
                    check(allowRelease.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(
            FakeFishSynthesisClient(),
            players,
            focus,
            logger,
            playbackTimeoutMillis = 1L,
        )
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()
        val callback = thread(name = "阻塞 Fish 完成回调") { players.players.single().complete() }

        try {
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(1L)
            runCurrent()

            assertTrue(result.await().isSuccess)
            assertEquals(0, players.players.single().stopCalls)
        } finally {
            allowRelease.countDown()
            callback.join(5_000L)
            check(!callback.isAlive)
        }

        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.COMPLETED)
    }

    @Test
    fun `外部 coroutine cancel 停止并释放播放资源且迟到 completion 无效`() = runTest {
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(FakeFishSynthesisClient(), players, focus, logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        result.cancel(CancellationException("caller cancel"))
        runCurrent()
        players.players.single().complete()

        assertTrue(result.isCancelled)
        assertFalse(engine.isSpeaking)
        assertEquals(1, players.players.single().stopCalls)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `engine stop 在合成阶段取消 client 并及时终止挂起调用`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val synthesis = FakeFishSynthesisClient().apply {
            behavior = {
                entered.complete(Unit)
                CompletableDeferred<File>().await()
            }
        }
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, FakeAudioFocusController(), logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        entered.await()

        engine.stop()
        runCurrent()

        assertSuspendThrows<CancellationException> { result.await() }
        assertEquals(1, synthesis.cancelCalls)
        assertTrue(players.players.isEmpty())
        assertFalse(engine.isSpeaking)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
    }

    @Test
    fun `合成期 stop 的 cancel Error 原样同步冒泡但 owning speak 仍取消且 active 可复用`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val original = AssertionError("synthesis cancel fatal")
        var synthesisCalls = 0
        val synthesis = FakeFishSynthesisClient().apply {
            behavior = {
                synthesisCalls += 1
                if (synthesisCalls == 1) {
                    entered.complete(Unit)
                    CompletableDeferred<File>().await()
                } else {
                    audioFile
                }
            }
            cancelBehavior = { throw original }
        }
        val players = FakePlayerFactory()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, focus, logger)
        val first = async { engine.speak("第一次", SpeechPriority.APP) }
        entered.await()

        try {
            val thrown = assertSyncThrows<AssertionError> { engine.stop() }
            assertSame(original, thrown)
            runCurrent()

            assertTrue(first.isCompleted)
            assertSuspendThrows<CancellationException> { first.await() }
            assertFalse(engine.isSpeaking)
            assertEquals(1, synthesis.cancelCalls)
            assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)

            val second = async { engine.speak("第二次", SpeechPriority.APP) }
            runCurrent()
            players.players.single().complete()
            assertTrue(second.await().isSuccess)
            assertProviderKinds(
                logger,
                TtsEventKind.ATTEMPTED,
                TtsEventKind.CANCELLED,
                TtsEventKind.ATTEMPTED,
                TtsEventKind.COMPLETED,
            )
            assertReleasedCount(logger, 1)
        } finally {
            first.cancel()
            runCurrent()
        }
    }

    @Test
    fun `caller cancel 正在执行 client cancel 时 stop 等待同一 Error 发布且不丢终态`() = runTest {
        val synthesisEntered = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val allowCancel = CountDownLatch(1)
        val allowSynthesisCompletion = CompletableDeferred<Unit>()
        val original = AssertionError("concurrent synthesis cancel fatal")
        var synthesisCalls = 0
        val synthesis = FakeFishSynthesisClient().apply {
            behavior = {
                synthesisCalls += 1
                if (synthesisCalls == 1) {
                    synthesisEntered.countDown()
                    try {
                        CompletableDeferred<File>().await()
                    } finally {
                        // 保持合成子任务未完成，确保 cancel 来自 awaitSynthesis，而非已抢占终态的 finish。
                        withContext(NonCancellable) {
                            withTimeout(5_000L) { allowSynthesisCompletion.await() }
                        }
                    }
                } else {
                    audioFile
                }
            }
            cancelBehavior = {
                cancelEntered.countDown()
                check(allowCancel.await(5, TimeUnit.SECONDS))
                throw original
            }
        }
        val players = FakePlayerFactory()
        val focus = FakeAudioFocusController()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, focus, logger)
        val first = async(Dispatchers.Default) { engine.speak("第一次", SpeechPriority.APP) }
        assertTrue(synthesisEntered.await(5, TimeUnit.SECONDS))

        first.cancel(CancellationException("caller cancel"))
        assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))

        val stopCaller = Thread.currentThread()
        val releaseCancel = thread(name = "release concurrent Fish cancel") {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                stopCaller.state != Thread.State.WAITING &&
                stopCaller.state != Thread.State.TIMED_WAITING &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            allowCancel.countDown()
        }
        try {
            val thrown = assertSyncThrows<AssertionError> { engine.stop() }
            assertSame(original, thrown)
        } finally {
            allowCancel.countDown()
            allowSynthesisCompletion.complete(Unit)
            releaseCancel.join(5_000L)
            check(!releaseCancel.isAlive)
        }

        assertSuspendThrows<CancellationException> { first.await() }
        assertTrue(first.isCancelled)
        assertEquals(1, synthesis.cancelCalls)
        assertFalse(engine.isSpeaking)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)

        val second = async { engine.speak("第二次", SpeechPriority.APP) }
        runCurrent()
        players.players.single().complete()
        assertTrue(second.await().isSuccess)
        assertProviderKinds(
            logger,
            TtsEventKind.ATTEMPTED,
            TtsEventKind.CANCELLED,
            TtsEventKind.ATTEMPTED,
            TtsEventKind.COMPLETED,
        )
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `engine stop 在播放阶段各清理一次且迟到 callback 不二次完成`() = runTest {
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(FakeFishSynthesisClient(), players, focus, logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        engine.stop()
        players.players.single().complete()

        assertSuspendThrows<CancellationException> { result.await() }
        assertEquals(1, players.players.single().stopCalls)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `engine stop 与 player start 线性化且绝不并发 stop release 或释放后 start`() = runTest {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply {
            onPlayerCreated = {
                it.startEntered = startEntered
                it.allowStart = allowStart
            }
        }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(FakeFishSynthesisClient(), players, focus, logger)
        val result = async(Dispatchers.Default) { engine.speak("测试", SpeechPriority.APP) }

        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            engine.stop()
        } finally {
            allowStart.countDown()
        }

        assertSuspendThrows<CancellationException> { result.await() }
        val player = players.players.single()
        assertFalse(player.startAfterRelease)
        assertFalse(player.stopWhileStarting)
        assertFalse(player.releaseWhileStarting)
        assertEquals(1, player.stopCalls)
        assertEquals(1, player.releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
        player.complete()
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
    }

    @Test
    fun `start gate 后 stop 的首个 Error 原样冒泡但全部清理且后续请求成功`() = runTest {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val stopError = AssertionError("stop fatal")
        val playerReleaseError = LinkageError("player release fatal")
        val leaseReleaseError = OutOfMemoryError("lease release fatal")
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply {
            onPlayerCreated = { player ->
                if (this.players.size == 1) {
                    player.startEntered = startEntered
                    player.allowStart = allowStart
                    player.stopBehavior = { throw stopError }
                    player.releaseBehavior = { throw playerReleaseError }
                }
            }
        }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(FakeFishSynthesisClient(), players, focus, logger)

        val firstResult = AtomicReference<Result<Unit>?>()
        val firstFailure = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        val speaker = thread(name = "Fish cleanup Error owning speaker") {
            try {
                runBlocking {
                    firstResult.set(engine.speak("第一次", SpeechPriority.APP))
                }
            } catch (error: Throwable) {
                firstFailure.set(error)
            } finally {
                firstDone.countDown()
            }
        }
        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            focus.leases.single().releaseBehavior = { throw leaseReleaseError }
            engine.stop()
        } finally {
            allowStart.countDown()
        }

        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        speaker.join(5_000L)
        check(!speaker.isAlive)
        assertEquals(null, firstResult.get())
        val thrown = firstFailure.get()
        assertSame(stopError, thrown)
        assertTrue(checkNotNull(thrown).suppressed.contains(playerReleaseError))
        assertTrue(thrown.suppressed.contains(leaseReleaseError))

        val firstPlayer = players.players.single()
        assertEquals(1, firstPlayer.stopCalls)
        assertEquals(1, firstPlayer.releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertFalse(engine.isSpeaking)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)

        val second = async { engine.speak("第二次", SpeechPriority.APP) }
        runCurrent()
        players.players[1].complete()
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun `completion 的 release Error 原样同步冒泡但不等待 watchdog 并清 active`() = runTest {
        val original = AssertionError("completion release fatal")
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply {
            onPlayerCreated = { player ->
                if (this.players.size == 1) player.releaseBehavior = { throw original }
            }
        }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(FakeFishSynthesisClient(), players, focus, logger)
        val first = async { engine.speak("第一次", SpeechPriority.APP) }
        runCurrent()

        val thrown = assertSyncThrows<AssertionError> { players.players.single().complete() }

        assertSame(original, thrown)
        runCurrent()
        assertTrue(first.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(first.await().isSuccess)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertFalse(engine.isSpeaking)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.COMPLETED)
        assertReleasedCount(logger, 1)

        val second = async { engine.speak("第二次", SpeechPriority.APP) }
        runCurrent()
        players.players[1].complete()
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun `shutdown 终止播放并只调用一次 client shutdown 与资源清理`() = runTest {
        val synthesis = FakeFishSynthesisClient()
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, focus, logger)
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        runCurrent()

        engine.shutdown()
        engine.shutdown()
        players.players.single().complete()

        assertSuspendThrows<CancellationException> { result.await() }
        assertEquals(1, synthesis.shutdownCalls)
        assertEquals(1, players.players.single().stopCalls)
        assertEquals(1, players.players.single().releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
    }

    @Test
    fun `合成期 shutdown 的 cancel Error 不阻断 terminal complete 与 client shutdown`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val original = AssertionError("shutdown cancel fatal")
        val synthesis = FakeFishSynthesisClient().apply {
            behavior = {
                entered.complete(Unit)
                CompletableDeferred<File>().await()
            }
            cancelBehavior = { throw original }
        }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(
            synthesis,
            FakePlayerFactory(),
            FakeAudioFocusController(),
            logger,
        )
        val result = async { engine.speak("测试", SpeechPriority.APP) }
        entered.await()

        try {
            val thrown = assertSyncThrows<AssertionError> { engine.shutdown() }
            assertSame(original, thrown)
            runCurrent()

            assertTrue(result.isCompleted)
            assertSuspendThrows<CancellationException> { result.await() }
            assertEquals(1, synthesis.cancelCalls)
            assertEquals(1, synthesis.shutdownCalls)
            assertFalse(engine.isSpeaking)
            assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
            assertReleasedCount(logger, 0)
        } finally {
            result.cancel()
            runCurrent()
        }
    }

    @Test
    fun `shutdown 与 player start 线性化且返回后没有 start release 竞态`() = runTest {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val synthesis = FakeFishSynthesisClient()
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory().apply {
            onPlayerCreated = {
                it.startEntered = startEntered
                it.allowStart = allowStart
            }
        }
        val logger = FakeDiagnosticLogger()
        val engine = newEngine(synthesis, players, focus, logger)
        val result = async(Dispatchers.Default) { engine.speak("测试", SpeechPriority.APP) }

        try {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            engine.shutdown()
        } finally {
            allowStart.countDown()
        }

        assertSuspendThrows<CancellationException> { result.await() }
        val player = players.players.single()
        assertFalse(player.startAfterRelease)
        assertFalse(player.stopWhileStarting)
        assertFalse(player.releaseWhileStarting)
        assertEquals(1, synthesis.shutdownCalls)
        assertEquals(1, player.stopCalls)
        assertEquals(1, player.releaseCalls)
        assertEquals(1, focus.leases.single().releaseCalls)
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
        assertReleasedCount(logger, 1)
        player.complete()
        assertProviderKinds(logger, TtsEventKind.ATTEMPTED, TtsEventKind.CANCELLED)
    }

    @Test
    fun `旧 player 迟到 callback 不释放连续新请求资源`() = runTest {
        val focus = FakeAudioFocusController()
        val players = FakePlayerFactory()
        val engine = newEngine(FakeFishSynthesisClient(), players, focus)
        val first = async { engine.speak("一", SpeechPriority.APP) }
        runCurrent()
        players.players[0].complete()
        assertTrue(first.await().isSuccess)

        val second = async { engine.speak("二", SpeechPriority.APP) }
        runCurrent()
        players.players[0].complete()

        assertFalse(second.isCompleted)
        assertEquals(1, focus.leases[0].releaseCalls)
        assertEquals(0, focus.leases[1].releaseCalls)
        assertEquals(0, players.players[1].releaseCalls)
        players.players[1].complete()
        assertTrue(second.await().isSuccess)
        assertEquals(1, focus.leases[1].releaseCalls)
        assertEquals(1, players.players[1].releaseCalls)
    }

    private fun newEngine(
        synthesisClient: FakeFishSynthesisClient,
        players: FakePlayerFactory,
        focus: FakeAudioFocusController,
        logger: FakeDiagnosticLogger = FakeDiagnosticLogger(),
        playbackTimeoutMillis: Long = 60_000L,
    ) = FishAudioTtsEngine(
        synthesisClient = synthesisClient,
        audioPlayerFactory = players,
        audioFocusController = focus,
        diagnosticLogger = logger,
        playbackTimeoutMillis = playbackTimeoutMillis,
    )

    private fun assertLastProviderEvent(
        logger: FakeDiagnosticLogger,
        kind: TtsEventKind,
    ): DiagnosticEvent.TtsProviderEvent =
        logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>().last().also {
            assertEquals(TtsProvider.FISH, it.provider)
            assertEquals(kind, it.kind)
        }

    private fun assertProviderKinds(logger: FakeDiagnosticLogger, vararg kinds: TtsEventKind) {
        val events = logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
        assertEquals(kinds.toList(), events.map { it.kind })
        assertTrue(events.all { it.provider == TtsProvider.FISH })
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

    private inline fun <reified T : Throwable> assertSyncThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }

    private class FakeFishSynthesisClient : FishSynthesisClient {
        var behavior: suspend (String) -> File = { audioFile }
        var cancelBehavior: () -> Unit = {}
        val requestedTexts = mutableListOf<String>()
        var cancelCalls = 0
            private set
        var shutdownCalls = 0
            private set

        override suspend fun synthesize(text: String): File {
            requestedTexts += text
            return behavior(text)
        }

        override fun cancel() {
            cancelCalls += 1
            cancelBehavior()
        }

        override fun shutdown() {
            shutdownCalls += 1
        }
    }

    private class FakePlayerFactory : () -> AudioPlayerPort {
        val players = mutableListOf<FakeAudioPlayer>()
        var onCreate: () -> Unit = {}
        var onPlayerCreated: (FakeAudioPlayer) -> Unit = {}
        var nextStartBehavior: () -> Result<Unit> = { Result.success(Unit) }
        var createBehavior: () -> AudioPlayerPort = {
            FakeAudioPlayer(nextStartBehavior).also {
                players += it
                onPlayerCreated(it)
            }
        }

        override fun invoke(): AudioPlayerPort {
            onCreate()
            return createBehavior()
        }
    }

    private class FakeAudioPlayer(
        var startBehavior: () -> Result<Unit>,
    ) : AudioPlayerPort {
        var startedFile: File? = null
            private set
        var startedProfile: SpeechAudioProfile? = null
            private set
        var stopCalls = 0
            private set
        var releaseCalls = 0
            private set
        var stopBehavior: () -> Unit = {}
        var releaseBehavior: () -> Unit = {}
        var startEntered: CountDownLatch? = null
        var allowStart: CountDownLatch? = null
        @Volatile
        var startAfterRelease = false
            private set
        @Volatile
        var stopWhileStarting = false
            private set
        @Volatile
        var releaseWhileStarting = false
            private set
        val trace = mutableListOf<String>()
        private var listener: AudioPlayerPort.Listener? = null
        private val lifecycleLock = Any()
        private var starting = false
        private var released = false

        override fun start(
            file: File,
            profile: SpeechAudioProfile,
            listener: AudioPlayerPort.Listener,
        ): Result<Unit> {
            trace += "start"
            startedFile = file
            startedProfile = profile
            this.listener = listener
            synchronized(lifecycleLock) {
                starting = true
                if (released) startAfterRelease = true
            }
            startEntered?.countDown()
            return try {
                allowStart?.let { check(it.await(5, TimeUnit.SECONDS)) }
                synchronized(lifecycleLock) {
                    if (released) startAfterRelease = true
                }
                startBehavior()
            } finally {
                synchronized(lifecycleLock) {
                    starting = false
                }
            }
        }

        fun complete() {
            listener!!.onCompletion()
        }

        fun fail(what: Int, extra: Int) {
            listener!!.onError(what, extra)
        }

        override fun stop() {
            stopCalls += 1
            synchronized(lifecycleLock) {
                if (starting) stopWhileStarting = true
            }
            stopBehavior()
        }

        override fun release() {
            releaseCalls += 1
            synchronized(lifecycleLock) {
                if (starting) releaseWhileStarting = true
                released = true
            }
            releaseBehavior()
        }
    }

    private class FakeAudioFocusController(
        private val granted: Boolean = true,
    ) : AudioFocusController {
        var requestBehavior: () -> AudioFocusLease = {
            FakeAudioFocusLease(granted).also { leases += it }
        }
        var onRequest: () -> Unit = {}
        var requestCalls = 0
            private set
        val leases = mutableListOf<FakeAudioFocusLease>()

        override fun request(): AudioFocusLease {
            requestCalls += 1
            onRequest()
            return requestBehavior()
        }
    }

    private class FakeAudioFocusLease(
        override val granted: Boolean,
    ) : AudioFocusLease {
        var releaseCalls = 0
            private set
        var releaseBehavior: () -> Unit = {}

        override fun release() {
            releaseCalls += 1
            releaseBehavior()
        }
    }

    companion object {
        private val audioFile = File("fish-test.mp3")
    }
}
