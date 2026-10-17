package com.healthyagent.running.speech

import kotlin.time.TimeSource

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePolicyTtsEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun `手环中断通知保持APP优先级且零AI和Fish调用`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val result = fixture.engine.speak(request("data_stale", SpeechPriority.APP, companion = true)).getOrThrow()
        assertEquals(SpeechDelivery("手环数据中断，请检查", true), result)
        assertEquals(0, fixture.onlineCalls)
        assertEquals(0, fixture.aiCalls)
        assertEquals(listOf("data_stale"), fixture.opened)
        assertEquals(VoiceAudioSource.BUNDLED, fixture.players.single().sources.single())
    }

    @Test
    fun `紧急事件零网络直接播放对应固定原文`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val result = fixture.engine.speak(request("hr_z5_danger", SpeechPriority.URGENT, companion = true)).getOrThrow()
        assertEquals(SpeechDelivery("心率过高，请立即放慢", true), result)
        assertEquals(0, fixture.onlineCalls)
        assertEquals(0, fixture.aiCalls)
        assertEquals(listOf("hr_z5_danger"), fixture.opened)
        assertEquals(VoiceAudioSource.BUNDLED, fixture.players.single().sources.single())
    }

    @Test
    fun `公里在线失败保持静默但同优先级目标达成本地播放`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.client.synthesis = { throw IOException("网络失败") }
        assertEquals(SpeechDelivery("真实公里数据", false), fixture.engine.speak(request("km_split", SpeechPriority.SPLIT)).getOrThrow())
        assertTrue(fixture.opened.isEmpty())
        val goal = fixture.engine.speak(request("goal_achieved", SpeechPriority.SPLIT)).getOrThrow()
        assertEquals(SpeechDelivery("已达成目标，干得漂亮！", true), goal)
        assertEquals(listOf("goal_achieved"), fixture.opened)
    }

    @Test
    fun `在线只合成固定核心和可选后句组成的一个文本`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.companion = { "一起继续吧。" }
        val result = fixture.engine.speak(request(companion = true)).getOrThrow()
        assertEquals(listOf("跑步已暂停。一起继续吧。"), fixture.client.texts)
        assertEquals(SpeechDelivery("跑步已暂停。一起继续吧。", true), result)
        assertEquals(VoiceAudioSource.ONLINE, fixture.players.single().sources.single())
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `无句末标点的固定核心与陪伴后句之间补句号`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.companion = { "  开拓者，我会陪着你。  " }
        val result = fixture.engine.speak(request("hr_too_low", companion = true)).getOrThrow()
        val expected = "心率偏低，可以适当加快。开拓者，我会陪着你。"
        assertEquals(listOf(expected), fixture.client.texts)
        assertEquals(SpeechDelivery(expected, true), result)
    }

    @Test
    fun `公里鼓励生成超过两秒仍与数据一次合成播放`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.companion = { delay(5_000); "真实公里数据。开拓者，这段路有我陪着你。" }
        val result = fixture.engine.speak(request("km_split", SpeechPriority.SPLIT, companion = true)).getOrThrow()
        assertEquals(listOf("真实公里数据。开拓者，这段路有我陪着你。"), fixture.client.texts)
        assertTrue(result.played)
        assertEquals(1, fixture.players.size)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `公里鼓励超过六秒只合成数据而普通提示仍只等两秒`() = runTest {
        for (rule in listOf("km_split", "session_paused")) {
            val fixture = Fixture(testScheduler.timeSource)
            fixture.companion = { delay(if (rule == "km_split") 6_001 else 2_001); "迟到鼓励。" }
            assertTrue(fixture.engine.speak(request(rule, companion = true)).getOrThrow().played)
            assertEquals(listOf(if (rule == "km_split") "真实公里数据" else "跑步已暂停。"), fixture.client.texts)
            assertEquals(1, fixture.players.size)
        }
    }

    @Test
    fun `空白陪伴后句不向固定核心增加句号`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.companion = { " \n\t " }
        val result = fixture.engine.speak(request("hr_too_low", companion = true)).getOrThrow()
        assertEquals(listOf("心率偏低，可以适当加快"), fixture.client.texts)
        assertEquals(SpeechDelivery("心率偏低，可以适当加快", true), result)
    }

    @Test
    fun `完整公里文本不再重复追加核心或句末标点`() = runTest {
        for (ending in "。！？.!?") {
            val fixture = Fixture(testScheduler.timeSource)
            val original = request("km_split", companion = true)
            val core = "  真实公里数据$ending"
            fixture.companion = { core + "陪着你。" }
            val result = fixture.engine.speak(original.copy(trigger = original.trigger.copy(speakText = core))).getOrThrow()
            assertEquals(listOf(core.trimStart() + "陪着你。"), fixture.client.texts)
            assertEquals(SpeechDelivery(core.trimStart() + "陪着你。", true), result)
        }
    }

    @Test
    fun `缺少在线配置固定事件回本地公里静默未知规则不猜本地`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.configured = false
        assertTrue(fixture.engine.speak(request()).getOrThrow().played)
        assertFalse(fixture.engine.speak(request("km_split")).getOrThrow().played)
        assertTrue(fixture.engine.speak(request("unknown")).isFailure)
        assertEquals(listOf("session_paused"), fixture.opened)
    }

    @Test
    fun `本地资源缺失受控失败文本兼容入口不猜固定规则`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.localFile = null
        assertTrue(fixture.engine.speak(request("hr_z5_danger", SpeechPriority.URGENT)).isFailure)
        assertTrue(fixture.engine.speak("心率过高，请立即放慢", SpeechPriority.URGENT).isFailure)
        assertTrue(fixture.players.isEmpty())
    }

    @Test
    fun `普通准备在7999未结束8000取消网络后回本地`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val late = CompletableDeferred<File>()
        fixture.client.synthesis = { withContext(NonCancellable) { late.await() } }
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, fixture.client.cancelCalls)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.isCompleted)
        assertTrue(result.await().getOrThrow().played)
        assertTrue(fixture.client.cancelCalls > 0)
        late.complete(File("late.mp3"))
        runCurrent()
        assertEquals(listOf(VoiceAudioSource.BUNDLED), fixture.players.single().sources)
    }

    @Test
    fun `公里准备在14999未结束15000静默并隔离迟到结果`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val late = CompletableDeferred<File>()
        fixture.client.synthesis = { withContext(NonCancellable) { late.await() } }
        val result = async { fixture.engine.speak(request("km_split")) }
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SpeechDelivery("真实公里数据", false), result.await().getOrThrow())
        assertTrue(fixture.client.cancelCalls > 0)
        late.complete(File("late.mp3"))
        runCurrent()
        assertTrue(fixture.players.isEmpty())
    }

    @Test
    fun `AI两秒期限到后仍合成核心且总准备限制包含AI`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val lateAi = CompletableDeferred<String>()
        fixture.companion = { withContext(NonCancellable) { lateAi.await() } }
        fixture.client.synthesis = { delay(6_001); File("online.mp3") }
        val result = async { fixture.engine.speak(request(companion = true)) }
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(fixture.client.texts.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("跑步已暂停。"), fixture.client.texts)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(SpeechDelivery("跑步已暂停。", true), result.await().getOrThrow())
        assertEquals(VoiceAudioSource.BUNDLED, fixture.players.single().sources.single())
        lateAi.complete("迟到后句")
        runCurrent()
        assertEquals(1, fixture.client.texts.size)
    }

    @Test
    fun `AI失败只省略后句在线播放失败不得本地重播`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.companion = { throw IOException("私密AI错误") }
        fixture.playback = { Result.failure(IOException("播放失败")) }
        assertTrue(fixture.engine.speak(request(companion = true)).isFailure)
        assertEquals(listOf("跑步已暂停。"), fixture.client.texts)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `AI服务自身超时仍保留核心合成机会`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.companion = { withTimeout(500) { delay(1_000); "不会返回" } }
        assertTrue(fixture.engine.speak(request(companion = true)).getOrThrow().played)
        assertEquals(listOf("跑步已暂停。"), fixture.client.texts)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `获得文件后的播放不受8秒或15秒准备期限限制`() = runTest {
        for (rule in listOf("session_paused", "km_split")) {
            val fixture = Fixture(testScheduler.timeSource)
            val completion = CompletableDeferred<Result<Unit>>()
            val prepareMillis = if (rule == "km_split") 14_999L else 7_999L
            fixture.client.synthesis = { delay(prepareMillis); File("online.mp3") }
            fixture.playback = { completion.await() }
            val result = async { fixture.engine.speak(request(rule)) }
            runCurrent()
            assertFalse(fixture.engine.isSpeaking)
            advanceTimeBy(prepareMillis)
            runCurrent()
            assertTrue(fixture.engine.isSpeaking)
            advanceTimeBy(59_999)
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals(0, fixture.players.single().stopCalls)
            completion.complete(Result.success(Unit))
            runCurrent()
            assertTrue(result.await().getOrThrow().played)
            assertFalse(fixture.engine.isSpeaking)
        }
    }

    @Test
    fun `父取消及时取消不可取消的网络且迟到异常不回退`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val late = CompletableDeferred<File>()
        fixture.client.synthesis = { withContext(NonCancellable) { late.await() } }
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        result.cancel()
        runCurrent()
        assertTrue(result.isCompleted)
        assertTrue(fixture.client.cancelCalls > 0)
        late.completeExceptionally(IOException("迟到私密错误"))
        runCurrent()
        assertTrue(fixture.opened.isEmpty())
        assertTrue(fixture.players.isEmpty())
        assertFalse(fixture.engine.isSpeaking)
    }

    @Test
    fun `父取消播放时立即清除播报状态并等待旧播放器自身结束`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val late = CompletableDeferred<Result<Unit>>()
        fixture.playback = { withContext(NonCancellable) { late.await() } }
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        assertTrue(fixture.engine.isSpeaking)
        try {
            result.cancel()
            runCurrent()
            assertFalse(fixture.engine.isSpeaking)
        } finally {
            late.complete(Result.success(Unit))
            runCurrent()
        }
        assertTrue(result.isCancelled)
        assertFalse(fixture.engine.isSpeaking)
        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `停止旧请求后紧急播放可立即进入旧清理不能关闭它`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val late = CompletableDeferred<File>()
        val completed = CompletableDeferred<Result<Unit>>()
        fixture.client.synthesis = { withContext(NonCancellable) { late.await() } }
        fixture.playback = { completed.await() }
        val old = async { fixture.engine.speak(request()) }
        runCurrent()
        fixture.engine.stop()
        val urgent = async { fixture.engine.speak(request("hr_z5_danger", SpeechPriority.URGENT)) }
        runCurrent()
        assertTrue(old.isCancelled)
        assertTrue(fixture.engine.isSpeaking)
        late.complete(File("late.mp3"))
        runCurrent()
        assertEquals(0, fixture.players.single().shutdownCalls)
        assertFalse(urgent.isCompleted)
        completed.complete(Result.success(Unit))
        runCurrent()
        assertTrue(urgent.await().getOrThrow().played)
    }

    @Test
    fun `新操作自动使旧播放失效关闭引擎后不再创建资源`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val late = CompletableDeferred<Result<Unit>>()
        fixture.playback = { withContext(NonCancellable) { late.await() } }
        val old = async { fixture.engine.speak(request()) }
        runCurrent()
        fixture.playback = { Result.success(Unit) }
        val urgent = async { fixture.engine.speak(request("hr_z5_danger", SpeechPriority.URGENT)) }
        runCurrent()
        assertTrue(urgent.await().getOrThrow().played)
        assertTrue(fixture.players.first().stopCalls > 0)
        late.complete(Result.success(Unit))
        runCurrent()
        assertTrue(old.isCancelled)
        fixture.engine.shutdown()
        assertTrue(fixture.engine.speak(request()).isFailure)
        assertEquals(2, fixture.players.size)
    }

    @Test
    fun `准备超时和离线公里有准确诊断且不暴露原文或网络异常`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.client.synthesis = { delay(20_000); File("late.mp3") }
        fixture.engine.speak(request("km_split"))
        val kinds = fixture.events.filterIsInstance<DiagnosticEvent.TtsProviderEvent>().map { it.kind.name }
        assertTrue(kinds.contains("PREPARATION_TIMEOUT"))
        assertTrue(kinds.contains("SKIPPED_OFFLINE"))
        fixture.client.synthesis = { throw IOException("私密原始网络错误") }
        fixture.engine.speak(request())
        assertFalse(fixture.events.toString().contains("私密原始网络错误"))
        assertFalse(fixture.events.toString().contains("真实公里数据"))
        assertFalse(fixture.events.toString().contains("ANDROID"))
    }

    @Test
    fun `每次在线操作独享client旧结果不会取消新请求`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val first = Client()
        val second = Client()
        val oldGate = CompletableDeferred<File>()
        val newGate = CompletableDeferred<File>()
        first.synthesis = { withContext(NonCancellable) { oldGate.await() } }
        second.synthesis = { newGate.await() }
        fixture.clientFactory = { if (fixture.onlineCalls == 1) first else second }
        val old = async { fixture.engine.speak(request()) }
        runCurrent()
        val next = async { fixture.engine.speak(request()) }
        runCurrent()
        assertTrue(old.isCancelled)
        oldGate.complete(File("old.mp3"))
        runCurrent()
        assertEquals(0, second.cancelCalls)
        assertEquals(0, second.shutdownCalls)
        newGate.complete(File("new.mp3"))
        runCurrent()
        assertTrue(next.await().getOrThrow().played)
        assertEquals(1, second.shutdownCalls)
        assertEquals(1, fixture.players.size)
    }

    @Test
    fun `阻塞execute在截止时收到显式取消而调用方不等迟到HTTP`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val call = BlockingCall()
        fixture.clientFactory = { OkHttpFishSynthesisClient("fixture", "fixture", temporaryFolder.root, call, readCache = false) }
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        try {
            assertTrue(call.entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(8_000)
            runCurrent()
            assertTrue(call.cancels.get() > 0)
            assertTrue(result.isCompleted)
            assertTrue(result.await().getOrThrow().played)
            assertEquals(VoiceAudioSource.BUNDLED, fixture.players.single().sources.single())
        } finally {
            call.release.countDown()
            assertTrue(call.finished.await(5, TimeUnit.SECONDS))
        }
        runCurrent()
        assertEquals(1, fixture.players.size)
    }

    @Test
    fun `阻塞execute在父取消时立即收到显式取消`() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val call = BlockingCall()
        fixture.clientFactory = { OkHttpFishSynthesisClient("fixture", "fixture", temporaryFolder.root, call, readCache = false) }
        val result = async { fixture.engine.speak(request()) }
        runCurrent()
        try {
            assertTrue(call.entered.await(5, TimeUnit.SECONDS))
            result.cancel()
            runCurrent()
            assertTrue(call.cancels.get() > 0)
            assertTrue(result.isCompleted)
            assertTrue(fixture.opened.isEmpty())
        } finally {
            call.release.countDown()
            assertTrue(call.finished.await(5, TimeUnit.SECONDS))
        }
        runCurrent()
        assertTrue(fixture.players.isEmpty())
    }

    private fun request(rule: String = "session_paused", priority: SpeechPriority = SpeechPriority.APP, companion: Boolean = false) =
        SpeechRequest(RuleTrigger(rule, Severity.INFO, "真实公里数据", priority), companionEnabled = companion)

    private class Fixture(timeSource: TimeSource) {
        val opened = mutableListOf<String>()
        val players = mutableListOf<Player>()
        val events = mutableListOf<DiagnosticEvent>()
        var localFile: File? = File("bundled.mp3")
        var configured = true
        var onlineCalls = 0
        var aiCalls = 0
        var companion: suspend () -> String? = { null }
        var playback: suspend () -> Result<Unit> = { Result.success(Unit) }
        val client = Client()
        var clientFactory: () -> FishSynthesisClient? = { if (configured) client else null }
        val engine = VoicePolicyTtsEngine(
            object : BundledVoiceStore {
                override suspend fun open(ruleId: String): File? { opened += ruleId; return localFile }
            },
            { onlineCalls++; clientFactory() },
            object : CompanionTextSource {
                override suspend fun create(request: SpeechRequest): String? { aiCalls++; return companion() }
            },
            { Player(playback).also(players::add) },
            object : DiagnosticLogger {
                override fun record(event: DiagnosticEvent) { events += event }
                override suspend fun awaitIdle() = Unit
                override suspend fun flush() = Unit
            },
            timeSource = timeSource,
        )
    }

    private class Client : FishSynthesisClient {
        val texts = mutableListOf<String>()
        var cancelCalls = 0
        var shutdownCalls = 0
        var synthesis: suspend () -> File = { File("online.mp3") }
        override suspend fun synthesize(text: String): File { texts += text; return synthesis() }
        override fun cancel() { cancelCalls++ }
        override fun shutdown() { shutdownCalls++ }
    }

    private class Player(val playback: suspend () -> Result<Unit>) : VoiceFilePlayer {
        val sources = mutableListOf<VoiceAudioSource>()
        var stopCalls = 0
        var shutdownCalls = 0
        override suspend fun prepare(file: File, source: VoiceAudioSource): Result<Unit> { sources += source; return Result.success(Unit) }
        override suspend fun play(): Result<Unit> = playback()
        override fun stop() { stopCalls++ }
        override fun shutdown() { shutdownCalls++ }
    }

    private class BlockingCall : Call, Call.Factory {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancels = AtomicInteger()
        private lateinit var original: Request
        override fun newCall(request: Request): Call { original = request; return this }
        override fun request() = original
        override fun execute(): Response {
            entered.countDown()
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS))
                throw IOException("迟到HTTP失败")
            } finally { finished.countDown() }
        }
        override fun cancel() { cancels.incrementAndGet() }
        override fun isCanceled() = cancels.get() > 0
        override fun isExecuted() = entered.count == 0L
        override fun enqueue(responseCallback: Callback) = error("测试不使用异步HTTP")
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = error("测试不复制HTTP")
    }
}
