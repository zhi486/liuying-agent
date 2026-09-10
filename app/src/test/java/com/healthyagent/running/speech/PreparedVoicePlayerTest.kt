package com.healthyagent.running.speech

import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.TtsEventKind
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedVoicePlayerTest {
    @Test fun `只有完成回调成功且使用助手语音配置`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.play() }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(SpeechAudioProfile.ASSISTANT_SPEECH, fixture.ports.single().profile)
        fixture.ports.single().listener.onCompletion()
        assertTrue(result.await().isSuccess)
        fixture.assertCleaned()
        assertEquals(listOf("BUNDLED", "BUNDLED"), fixture.logger.events
            .filterIsInstance<DiagnosticEvent.TtsProviderEvent>().map { it.provider.name })
    }

    @Test fun `错误回调失败且迟到完成不能改终态`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.play() }
        runCurrent()
        fixture.ports.single().listener.onError(1, 2)
        fixture.ports.single().listener.onCompletion()
        assertTrue(result.await().isFailure)
        fixture.assertCleaned()
        assertEquals(TtsEventKind.FAILED, fixture.logger.events
            .filterIsInstance<DiagnosticEvent.TtsProviderEvent>().last().kind)
    }

    @Test fun `默认独立播放超时在60000毫秒停止并释放一次`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.play() }
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await().isFailure)
        fixture.assertCleaned(stopCalls = 1)
        assertEquals(TtsEventKind.PLAYBACK_TIMEOUT, fixture.logger.events
            .filterIsInstance<DiagnosticEvent.TtsProviderEvent>().last().kind)
    }

    @Test fun `调用方取消释放一次并忽略迟到错误`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.play() }
        runCurrent()
        result.cancel()
        runCurrent()
        assertTrue(result.isCancelled)
        fixture.ports.single().listener.onError(1, 2)
        fixture.assertCleaned(stopCalls = 1)
    }

    @Test fun `重复停止取消等待且只清理一次`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.play() }
        runCurrent()
        fixture.player.stop()
        fixture.player.stop()
        runCurrent()
        assertTrue(result.isCancelled)
        fixture.assertCleaned(stopCalls = 1)
    }

    @Test fun `同步完成须等start返回才清理`() = runTest {
        val fixture = Fixture()
        fixture.onStart = { port -> port.listener.onCompletion() }
        assertTrue(fixture.play().isSuccess)
        fixture.assertCleaned()
        assertFalse(fixture.ports.single().cleanedWhileStarting)
    }

    @Test fun `start内部停止不死锁且不能在start返回前释放`() = runTest {
        val fixture = Fixture()
        fixture.onStart = { fixture.player.stop() }
        try {
            fixture.play()
            fail("应传播取消")
        } catch (_: CancellationException) { }
        fixture.assertCleaned(stopCalls = 1)
        assertFalse(fixture.ports.single().cleanedWhileStarting)
    }

    @Test fun `另一个线程在start中停止无需等待端口返回`() = runTest {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        fixture.onStart = {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val result = async(Dispatchers.Default) { fixture.play() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.player.stop()
            assertEquals(0, fixture.ports.single().releases)
        } finally { proceed.countDown() }
        try { result.await(); fail("应取消") } catch (_: CancellationException) { }
        fixture.assertCleaned(stopCalls = 1)
        assertFalse(fixture.ports.single().cleanedWhileStarting)
    }

    @Test fun `工厂创建中取消不启动迟到资源并释放焦点和端口`() = runTest {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        fixture.onCreate = {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val result = async(Dispatchers.Default) { fixture.play() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            result.cancel()
        } finally { proceed.countDown() }
        result.join()
        assertEquals(0, fixture.ports.single().starts)
        fixture.assertCleaned()
    }

    @Test fun `旧回调不得释放新操作资源`() = runTest {
        val fixture = Fixture()
        val first = async { fixture.play() }
        runCurrent()
        fixture.ports[0].listener.onCompletion()
        assertTrue(first.await().isSuccess)
        val second = async { fixture.play() }
        runCurrent()
        fixture.ports[0].listener.onError(1, 2)
        fixture.ports[0].listener.onCompletion()
        assertFalse(second.isCompleted)
        assertEquals(0, fixture.ports[1].releases)
        assertEquals(0, fixture.leases[1].releases)
        fixture.ports[1].listener.onCompletion()
        assertTrue(second.await().isSuccess)
        assertEquals(listOf(1, 1), fixture.leases.map { it.releases })
    }

    @Test fun `焦点拒绝仍播放并释放实际lease`() = runTest {
        val fixture = Fixture(granted = false)
        fixture.onStart = { it.listener.onCompletion() }
        assertTrue(fixture.play().isSuccess)
        fixture.assertCleaned()
    }

    @Test fun `release异常仍释放焦点且保留完成结果`() = runTest {
        val fixture = Fixture()
        fixture.onStart = { it.throwOnRelease = true; it.listener.onCompletion() }
        assertTrue(fixture.play().isSuccess)
        fixture.assertCleaned()
    }

    @Test fun `start返回失败不能当作完成且来源为在线`() = runTest {
        val fixture = Fixture()
        fixture.onStart = { it.startResult = Result.failure(IllegalStateException("失败")) }
        assertTrue(fixture.player.play(File("test.mp3"), VoiceAudioSource.ONLINE).isFailure)
        fixture.assertCleaned()
        assertTrue(fixture.logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
            .all { it.provider.name == "FISH" })
    }

    @Test fun `shutdown取消当前且拒绝后续播放`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.play() }
        runCurrent()
        fixture.player.shutdown()
        runCurrent()
        assertTrue(result.isCancelled)
        assertTrue(fixture.play().isFailure)
        fixture.assertCleaned(stopCalls = 1)
        assertEquals(1, fixture.ports.size)
    }

    private class Fixture(granted: Boolean = true) {
        val ports = mutableListOf<Port>()
        val leases = mutableListOf<Lease>()
        val logger = FakeDiagnosticLogger()
        var onStart: (Port) -> Unit = {}
        var onCreate: () -> Unit = {}
        val player = PreparedVoicePlayer(
            audioPlayerFactory = { onCreate(); Port { onStart(it) }.also { ports += it } },
            audioFocusController = object : AudioFocusController {
                override fun request() = Lease(granted).also { leases += it }
            },
            diagnosticLogger = logger,
        )
        suspend fun play() = player.play(File("test.mp3"), VoiceAudioSource.BUNDLED)
        fun assertCleaned(stopCalls: Int = 0) {
            assertEquals(1, ports.single().releases)
            assertEquals(stopCalls, ports.single().stops)
            assertEquals(1, leases.single().releases)
        }
    }

    private class Lease(override val granted: Boolean) : AudioFocusLease {
        var releases = 0
        override fun release() { releases++ }
    }

    private class Port(val onStart: (Port) -> Unit) : AudioPlayerPort {
        lateinit var listener: AudioPlayerPort.Listener
        var profile: SpeechAudioProfile? = null
        var starts = 0
        var stops = 0
        var releases = 0
        var starting = false
        var cleanedWhileStarting = false
        var throwOnRelease = false
        var startResult = Result.success(Unit)
        override fun start(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener): Result<Unit> {
            this.listener = listener
            this.profile = profile
            starts++
            starting = true
            try { onStart(this); return startResult } finally { starting = false }
        }
        override fun stop() { stops++; cleanedWhileStarting = cleanedWhileStarting || starting }
        override fun release() {
            releases++
            cleanedWhileStarting = cleanedWhileStarting || starting
            if (throwOnRelease) throw IllegalStateException("释放失败")
        }
    }
}
