package com.healthyagent.running.speech

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechAcceptanceBudgetTest {
    @Test fun `普通事件接纳后延迟3秒派发只剩5秒在线预算`() = runTest {
        val fixture = Fixture(this, synthesisDelay = 7_000)
        try {
            fixture.bus.enqueue(request())
            advanceTimeBy(3_000)
            assertEquals(0, fixture.clientFactories)
            fixture.dispatcher.release()
            runCurrent()
            assertEquals(1, fixture.synthesisCalls)
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(0, fixture.focusRequests)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("接纳后第8秒必须回退本地", listOf("session_paused.mp3"), fixture.started)
            assertEquals(0L, fixture.bus.latestMessage.value?.timestampMillis)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf("session_paused.mp3"), fixture.started)
        } finally { fixture.close() }
    }

    @Test fun `派发时普通8秒或公里15秒已耗尽则不创建在线客户端也不调用AI`() = runTest {
        for ((rule, elapsed) in listOf("session_paused" to 8_000L, "km_split" to 15_000L)) {
            val fixture = Fixture(this)
            try {
                fixture.bus.enqueue(request(rule, companion = true))
                advanceTimeBy(elapsed)
                fixture.dispatcher.release()
                runCurrent()
                assertEquals("耗尽预算不得创建在线客户端", 0, fixture.clientFactories)
                assertEquals(0, fixture.synthesisCalls)
                assertEquals(0, fixture.aiCalls)
                assertEquals(if (rule == "km_split") emptyList<String>() else listOf("session_paused.mp3"), fixture.started)
            } finally { fixture.close() }
        }
    }

    @Test fun `公里延迟3秒派发只剩12秒且第15秒跳过`() = runTest {
        val fixture = Fixture(this, synthesisDelay = 13_000)
        try {
            fixture.bus.enqueue(request("km_split"))
            advanceTimeBy(3_000)
            fixture.dispatcher.release()
            runCurrent()
            advanceTimeBy(11_999)
            runCurrent()
            assertNull(fixture.bus.latestMessage.value)
            advanceTimeBy(1)
            runCurrent()
            assertNotNull("接纳后第15秒必须完成公里跳过", fixture.bus.latestMessage.value)
            assertEquals(0, fixture.focusRequests)
            assertTrue(fixture.started.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun `pending提升保留最新状态原接纳预算且不改变UI提升时间`() = runTest {
        val fixture = Fixture(this, synthesisDelay = 3_000)
        try {
            fixture.dispatcher.release()
            fixture.bus.enqueue(request("hr_z5_danger", SpeechPriority.URGENT))
            runCurrent()
            advanceTimeBy(1_000)
            fixture.bus.enqueueSessionStatus(request("session_paused"))
            advanceTimeBy(2_000)
            fixture.bus.enqueueSessionStatus(request("session_resumed"))
            advanceTimeBy(6_000)
            fixture.ports.single().listener.onCompletion()
            runCurrent()
            assertEquals(1, fixture.synthesisCalls)
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(listOf("hr_z5_danger.mp3"), fixture.started)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("最新pending在t3接纳，t11必须回本地", listOf("hr_z5_danger.mp3", "session_resumed.mp3"), fixture.started)
            assertEquals(9_000L, fixture.bus.latestMessage.value?.timestampMillis)
        } finally { fixture.close() }
    }

    @Test fun `普通和公里在剩余预算内提前1毫秒prepared仍在线播放`() = runTest {
        for (rule in listOf("session_paused", "km_split")) {
            val remaining = if (rule == "km_split") 12_000L else 5_000L
            val fixture = Fixture(this, synthesisDelay = remaining - 1)
            try {
                fixture.bus.enqueue(request(rule))
                advanceTimeBy(3_000)
                fixture.dispatcher.release()
                runCurrent()
                advanceTimeBy(remaining - 1)
                runCurrent()
                assertEquals(listOf("online.mp3"), fixture.started)
                assertEquals(1, fixture.focusRequests)
            } finally { fixture.close() }
        }
    }

    @Test fun `仅剩1秒时AI不获得新2秒且预算耗尽后不调用Fish`() = runTest {
        val fixture = Fixture(this, aiDelay = 1_500)
        try {
            fixture.bus.enqueue(request(companion = true))
            advanceTimeBy(7_000)
            fixture.dispatcher.release()
            runCurrent()
            assertEquals(1, fixture.aiCalls)
            advanceTimeBy(999)
            runCurrent()
            assertTrue(fixture.started.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(0, fixture.synthesisCalls)
            assertEquals(listOf("session_paused.mp3"), fixture.started)
        } finally { fixture.close() }
    }

    @Test fun `文件或媒体prepared返回时原时钟已过期则不得开播在线`() = runTest {
        for (stage in listOf("file", "media")) {
            val clock = TestTimeSource()
            val fixture = Fixture(this, timeSource = clock,
                onSynthesis = { if (stage == "file") clock += 8.seconds },
                onPrepare = { if (stage == "media") clock += 8.seconds })
            try {
                fixture.bus.enqueue(request())
                fixture.dispatcher.release()
                runCurrent()
                assertEquals(listOf("session_paused.mp3"), fixture.started)
                assertEquals(1, fixture.focusRequests)
                assertTrue(fixture.ports.all { it.releases == 1 })
            } finally { fixture.close() }
        }
    }

    @Test fun `没有Bus标记的直接speak从本次入口获得预算`() = runTest {
        advanceTimeBy(50_000)
        val fixture = Fixture(this, synthesisDelay = 7_999)
        try {
            val result = async { fixture.engine.speak(request()) }
            runCurrent()
            advanceTimeBy(7_999)
            runCurrent()
            assertTrue(result.await().getOrThrow().played)
            assertEquals(listOf("online.mp3"), fixture.started)
        } finally { fixture.close() }
    }

    @Test fun `pending接纳已超过8秒在URGENT完成后直接本地`() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.dispatcher.release()
            fixture.bus.enqueue(request("hr_z5_danger", SpeechPriority.URGENT))
            runCurrent()
            advanceTimeBy(1_000)
            fixture.bus.enqueueSessionStatus(request("session_paused", companion = true))
            advanceTimeBy(8_000)
            fixture.ports.single().listener.onCompletion()
            runCurrent()
            assertEquals(0, fixture.clientFactories)
            assertEquals(0, fixture.aiCalls)
            assertEquals(listOf("hr_z5_danger.mp3", "session_paused.mp3"), fixture.started)
        } finally { fixture.close() }
    }

    @Test fun `迟到派发仍保持URGENT本地以及停止总关闭静音`() = runTest {
        for (mode in listOf("urgent", "stop", "disabled")) {
            val fixture = Fixture(this)
            try {
                fixture.bus.enqueue(if (mode == "urgent") request("hr_z5_danger", SpeechPriority.URGENT, true)
                    else request(companion = true))
                advanceTimeBy(20_000)
                if (mode == "stop") fixture.bus.stop()
                if (mode == "disabled") {
                    fixture.bus.configure(SpeechPolicy(false, 0))
                    fixture.bus.enqueue(request("hr_z5_danger", SpeechPriority.URGENT))
                }
                fixture.dispatcher.release()
                runCurrent()
                assertEquals(0, fixture.clientFactories)
                assertEquals(0, fixture.aiCalls)
                assertEquals(if (mode == "urgent") listOf("hr_z5_danger.mp3") else emptyList<String>(), fixture.started)
            } finally { fixture.close() }
        }
    }

    @Test fun `剩余预算到期取消请求且迟到文件不补播`() = runTest {
        val fixture = Fixture(this)
        val late = CompletableDeferred<File>()
        fixture.synthesis = { withContext(NonCancellable) { late.await() } }
        try {
            fixture.bus.enqueue(request())
            advanceTimeBy(3_000)
            fixture.dispatcher.release()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(fixture.cancelCalls > 0)
            assertEquals(listOf("session_paused.mp3"), fixture.started)
            late.complete(File("online.mp3"))
            runCurrent()
            assertEquals(listOf("session_paused.mp3"), fixture.started)
        } finally { late.complete(File("online.mp3")); fixture.close() }
    }

    private fun request(rule: String = "session_paused", priority: SpeechPriority = SpeechPriority.APP, companion: Boolean = false) =
        SpeechRequest(RuleTrigger(rule, Severity.INFO, "第一公里，用时六分整。", priority), companion)

    private class Fixture(
        private val scope: TestScope,
        synthesisDelay: Long = 0,
        aiDelay: Long = 0,
        timeSource: TimeSource = scope.testScheduler.timeSource,
        onSynthesis: () -> Unit = {},
        onPrepare: () -> Unit = {},
    ) {
        val dispatcher = DispatchGate(StandardTestDispatcher(scope.testScheduler))
        val logger = FakeDiagnosticLogger()
        val started = mutableListOf<String>()
        val ports = mutableListOf<Port>()
        var clientFactories = 0
        var synthesisCalls = 0
        var aiCalls = 0
        var focusRequests = 0
        var cancelCalls = 0
        var synthesis: suspend () -> File = { delay(synthesisDelay); onSynthesis(); File("online.mp3") }
        val engine = VoicePolicyTtsEngine(
            object : BundledVoiceStore {
                override suspend fun open(ruleId: String) = File("$ruleId.mp3")
            },
            {
                clientFactories++
                object : FishSynthesisClient {
                    override suspend fun synthesize(text: String): File {
                        synthesisCalls++
                        return synthesis()
                    }
                    override fun cancel() { cancelCalls++ }
                    override fun shutdown() = Unit
                }
            },
            object : CompanionTextSource {
                override suspend fun create(request: SpeechRequest): String? { aiCalls++; delay(aiDelay); return null }
            },
            { PreparedVoicePlayer({ Port(started, onPrepare).also(ports::add) }, object : AudioFocusController {
                override fun request(): AudioFocusLease {
                    focusRequests++
                    return object : AudioFocusLease {
                        override val granted = true
                        override fun release() = Unit
                    }
                }
            }, logger) },
            logger,
            timeSource = timeSource,
        )
        val bus = SpeechBus(engine, nowMillis = { scope.testScheduler.currentTime },
            diagnosticLogger = logger, dispatcher = dispatcher, timeSource = timeSource)
        fun close() { bus.shutdown(); dispatcher.release(); scope.runCurrent() }
    }

    private class Port(private val started: MutableList<String>, private val onPrepare: () -> Unit) : AudioPlayerPort {
        lateinit var listener: AudioPlayerPort.Listener
        private lateinit var file: File
        var releases = 0
        override suspend fun prepare(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener): Result<Unit> {
            this.file = file
            this.listener = listener
            if (file.name == "online.mp3") onPrepare()
            return Result.success(Unit)
        }
        override suspend fun startPrepared(startIfValid: (() -> Unit) -> Boolean): Result<Unit> {
            startIfValid {
                started += file.name
                if (file.name != "hr_z5_danger.mp3") listener.onCompletion()
            }
            return Result.success(Unit)
        }
        override fun start(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener) = error("只用两阶段端口")
        override fun stop() = Unit
        override fun release() { releases++ }
    }

    @OptIn(InternalCoroutinesApi::class)
    private class DispatchGate(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay {
        private var held = true
        private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) pending += context to block else delegate.dispatch(context, block)
        }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
            delegate.scheduleResumeAfterDelay(timeMillis, continuation)
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            delegate.invokeOnTimeout(timeMillis, block, context)
        fun release() {
            held = false
            val queued = pending.toList()
            pending.clear()
            queued.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }
}
