package com.healthyagent.running.speech

import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.io.File
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.data.local.SettingsSnapshot
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import com.healthyagent.running.diagnostics.TtsEventKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPreparationGateTest {
    @Test fun `真实Android端口在媒体准备中取消后不得启动且准备不拿焦点`() = runTest {
        for (cancelCaller in listOf(false, true)) {
            val fixture = Fixture()
            val result = async { fixture.engine.speak(request()) }
            runCurrent()
            assertEquals(1, fixture.platforms.single().preparations)
            assertEquals(0, fixture.focusRequests)
            if (cancelCaller) result.cancel() else fixture.engine.stop()
            runCurrent()
            assertTrue(result.isCancelled)
            val platform = fixture.platforms.single()
            platform.callback.onPrepared()
            platform.callback.onError(1, 2)
            platform.callback.onCompletion()
            runCurrent()
            assertEquals("取消后的迟到prepare不得start", 0, platform.starts)
            assertEquals("媒体准备阶段不得获取焦点", 0, fixture.focusRequests)
            assertTrue(fixture.opened.isEmpty())
            assertEquals(1, platform.releases)
            assertEquals(0, platform.stops)
        }
    }

    @Test fun `7999或14999毫秒取得文件但媒体准备跨界则取消在线并本地或跳过`() = runTest {
        for ((rule, deadline) in listOf("session_paused" to 8_000L, "km_split" to 15_000L)) {
            val fixture = Fixture(fileDelay = deadline - 1)
            val result = async { fixture.engine.speak(request(rule)) }
            runCurrent()
            advanceTimeBy(deadline - 1)
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals(1, fixture.platforms.single().preparations)
            assertEquals(0, fixture.platforms.single().starts)
            assertEquals(0, fixture.focusRequests)
            assertFalse(fixture.engine.isSpeaking)
            advanceTimeBy(1)
            runCurrent()
            val delivery = result.await().getOrThrow()
            assertEquals(rule != "km_split", delivery.played)
            val old = fixture.platforms.first()
            old.callback.onPrepared()
            runCurrent()
            assertEquals(0, old.starts)
            assertEquals(1, old.releases)
            assertEquals(0, old.stops)
            assertEquals(if (rule == "km_split") 0 else 1, fixture.focusRequests)
            assertEquals(fixture.focusRequests, fixture.focusReleases)
            assertTrue(fixture.logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
                .any { it.kind == TtsEventKind.PREPARATION_TIMEOUT })
            assertTrue(fixture.platforms.all { it.releases == 1 })
        }
    }

    @Test fun `prepare失败允许固定回退公里跳过而实际开播后错误不重播`() = runTest {
        for (rule in listOf("session_paused", "km_split")) {
            val fixture = Fixture()
            val result = async { fixture.engine.speak(request(rule)) }
            runCurrent()
            fixture.platforms.single().callback.onError(1, 2)
            runCurrent()
            assertEquals(rule != "km_split", result.await().getOrThrow().played)
            assertEquals(0, fixture.platforms.first().starts)
            assertEquals(1, fixture.platforms.first().releases)
        }
        val fixture = Fixture()
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        fixture.platforms.single().callback.onPrepared()
        runCurrent()
        assertEquals(1, fixture.platforms.single().starts)
        fixture.platforms.single().callback.onError(1, 2)
        runCurrent()
        assertTrue(result.await().isFailure)
        assertTrue(fixture.opened.isEmpty())
        assertEquals(1, fixture.platforms.single().releases)
        assertEquals(1, fixture.focusReleases)
    }

    @Test fun `实际prepared后启动才开始60秒且真实PolicyPlayerBus超时诊断一致`() = runTest {
        val fixture = Fixture(fileDelay = 7_000)
        val bus = SpeechBus(fixture.engine, diagnosticLogger = fixture.logger,
            dispatcher = StandardTestDispatcher(testScheduler))
        bus.enqueue(request())
        runCurrent()
        advanceTimeBy(7_999)
        runCurrent()
        fixture.platforms.single().callback.onPrepared()
        runCurrent()
        assertEquals(1, fixture.platforms.single().starts)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(0, fixture.platforms.single().releases)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(SpeechOutcomeReason.PLAYBACK_TIMEOUT), fixture.logger.events
            .filterIsInstance<DiagnosticEvent.SpeechOutcome>().map { it.reason })
        assertTrue(fixture.logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
            .any { it.kind == TtsEventKind.PLAYBACK_TIMEOUT })
        assertEquals(1, fixture.platforms.single().stops)
        assertEquals(1, fixture.platforms.single().releases)
        assertEquals(1, fixture.focusReleases)
        assertTrue(fixture.opened.isEmpty())
        bus.shutdown()
        runCurrent()
    }

    @Test fun `URGENT抢占媒体准备只播本地且总关闭连URGENT也不启动`() = runTest {
        for (disable in listOf(false, true)) {
            val fixture = Fixture()
            val bus = SpeechBus(fixture.engine, diagnosticLogger = fixture.logger,
                dispatcher = StandardTestDispatcher(testScheduler))
            bus.enqueue(request())
            runCurrent()
            if (disable) bus.configure(SpeechPolicy(false, 0))
            bus.enqueue(request("hr_z5_danger", SpeechPriority.URGENT))
            runCurrent()
            val old = fixture.platforms.first()
            old.callback.onPrepared()
            runCurrent()
            assertEquals(0, old.starts)
            assertEquals(1, old.releases)
            assertEquals(1, fixture.synthesisCalls)
            assertEquals(if (disable) emptyList<String>() else listOf("hr_z5_danger"), fixture.opened)
            assertEquals(if (disable) 0 else 1, fixture.focusRequests)
            assertEquals(fixture.focusRequests, fixture.focusReleases)
            bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `仅有Key无声线时固定本地公里静默且没有在线请求`() = runTest {
        val fixture = Fixture(settings = SettingsSnapshot(fishApiKey = "fixture-key"))
        assertTrue(fixture.engine.speak(request()).getOrThrow().played)
        assertFalse(fixture.engine.speak(request("km_split")).getOrThrow().played)
        assertTrue(fixture.engine.speak(request("goal_achieved", SpeechPriority.SPLIT)).getOrThrow().played)
        assertEquals(0, fixture.synthesisCalls)
        assertEquals(listOf("session_paused", "goal_achieved"), fixture.opened)
    }

    @Test fun `本地媒体准备挂起在60秒受控失败且无焦点清理一次`() = runTest {
        val fixture = Fixture(settings = SettingsSnapshot(), bundledReady = false)
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, fixture.focusRequests)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("本地准备故障不得无界等待", result.isCompleted)
        assertTrue(result.await().isFailure)
        assertFalse(result.isCancelled)
        val platform = fixture.platforms.single()
        platform.callback.onPrepared()
        runCurrent()
        assertEquals(0, platform.starts)
        assertEquals(0, platform.stops)
        assertEquals(1, platform.releases)
        assertEquals(0, fixture.focusRequests)
        assertTrue(fixture.logger.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>()
            .any { it.kind == TtsEventKind.PREPARATION_TIMEOUT })
    }

    @Test fun `真实平台实际播放中取消与迟到终态只停止释放一次`() = runTest {
        val fixture = Fixture()
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        val platform = fixture.platforms.single()
        platform.callback.onPrepared()
        runCurrent()
        fixture.engine.stop()
        fixture.engine.shutdown()
        runCurrent()
        platform.callback.onCompletion()
        platform.callback.onError(1, 2)
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, platform.starts)
        assertEquals(1, platform.stops)
        assertEquals(1, platform.releases)
        assertEquals(1, fixture.focusRequests)
        assertEquals(1, fixture.focusReleases)
        assertTrue(fixture.opened.isEmpty())
    }

    private fun request(rule: String = "session_paused", priority: SpeechPriority = SpeechPriority.APP) =
        SpeechRequest(RuleTrigger(rule, Severity.INFO, "第一公里，用时六分整，配速每公里六分整。", priority))

    private class Fixture(
        fileDelay: Long = 0,
        settings: SettingsSnapshot = SettingsSnapshot(fishApiKey = "fixture-key", fishReferenceId = "fixture-reference"),
        bundledReady: Boolean = true,
    ) {
        val platforms = mutableListOf<Platform>()
        val opened = mutableListOf<String>()
        val logger = FakeDiagnosticLogger()
        var focusRequests = 0
        var focusReleases = 0
        var synthesisCalls = 0
        val engine = VoicePolicyTtsEngine(
            object : BundledVoiceStore {
                override suspend fun open(ruleId: String): File { opened += ruleId; return File("bundled.mp3") }
            },
            { if (settings.fishApiKey.isBlank() || settings.fishReferenceId.isBlank()) null else object : FishSynthesisClient {
                override suspend fun synthesize(text: String): File { synthesisCalls++; delay(fileDelay); return File("online.mp3") }
                override fun cancel() = Unit
                override fun shutdown() = Unit
            } },
            object : CompanionTextSource {
                override suspend fun create(request: SpeechRequest): String? = error("测试禁止AI调用")
            },
            { PreparedVoicePlayer({ AndroidAudioPlayerPort(Platform(bundledReady).also(platforms::add)) }, object : AudioFocusController {
                override fun request(): AudioFocusLease {
                    focusRequests++
                    return object : AudioFocusLease {
                        override val granted = true
                        override fun release() { focusReleases++ }
                    }
                }
            }, logger) },
            logger,
        )
    }

    private class Platform(private val bundledReady: Boolean) : AndroidAudioPlayerPlatform {
        lateinit var callback: AndroidAudioPlayerListener
        var bundled = false
        var preparations = 0
        var starts = 0
        var stops = 0
        var releases = 0
        override fun setAudioAttributes(contentType: Int, usage: Int) = Unit
        override fun setDataSource(path: String) { bundled = File(path).name == "bundled.mp3" }
        override fun setListener(listener: AndroidAudioPlayerListener) { this.callback = listener }
        override fun prepareAsync() {
            preparations++
            if (bundled && bundledReady) callback.onPrepared()
        }
        override fun start() {
            starts++
            if (bundled) callback.onCompletion()
        }
        override fun stop() { stops++ }
        override fun release() { releases++ }
    }
}
