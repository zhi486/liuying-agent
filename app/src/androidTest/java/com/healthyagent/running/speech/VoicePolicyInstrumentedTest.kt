package com.healthyagent.running.speech

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 生产语音策略的真机路由测试；除显式试听外，所有播放器和在线端口均为无声替身。 */
@RunWith(AndroidJUnit4::class)
class VoicePolicyInstrumentedTest {
    @Test
    fun 固定提示在线失败后读取APK资源并回退本地() = runBlocking {
        val fixture = Fixture(targetContext())
        fixture.client = RecordingFishClient { throw IOException("测试网络失败") }

        try {
            val delivery = fixture.engine.speak(request("session_paused", SpeechPriority.APP, "不应播报的调用方文本"))
                .getOrThrow()

            assertEquals(SpeechDelivery("跑步已暂停。", played = true), delivery)
            assertEquals(listOf("跑步已暂停。"), requireNotNull(fixture.client).texts)
            fixture.assertSingleBundledPlayback("session_paused")
        } finally {
            fixture.engine.shutdown()
        }
    }

    @Test
    fun 离线公里保持静默且不创建播放器() = runBlocking {
        val fixture = Fixture(targetContext())

        try {
            val delivery = fixture.engine.speak(request("km_split", SpeechPriority.SPLIT, "第七码，用时六分整。"))
                .getOrThrow()

            assertEquals(SpeechDelivery("第七码，用时六分整。", played = false), delivery)
            assertEquals(1, fixture.onlineFactoryCalls)
            assertTrue(fixture.players.isEmpty())
        } finally {
            fixture.engine.shutdown()
        }
    }

    @Test
    fun 离线目标达成仍读取APK资源并本地播放() = runBlocking {
        val fixture = Fixture(targetContext())

        try {
            val delivery = fixture.engine.speak(request("goal_achieved", SpeechPriority.SPLIT, "不应播报的调用方文本"))
                .getOrThrow()

            assertEquals(SpeechDelivery("已达成目标，干得漂亮！", played = true), delivery)
            fixture.assertSingleBundledPlayback("goal_achieved")
        } finally {
            fixture.engine.shutdown()
        }
    }

    @Test
    fun 紧急提示零AI零Fish并直接播放APK资源() = runBlocking {
        val fixture = Fixture(targetContext())
        fixture.client = RecordingFishClient { error("紧急提示不得调用 Fish") }

        try {
            val delivery = fixture.engine.speak(
                request("hr_rapid_rise", SpeechPriority.URGENT, "不应播报的调用方文本", companion = true),
            ).getOrThrow()

            assertEquals(SpeechDelivery("心率上升很快，控制节奏", played = true), delivery)
            assertEquals(0, fixture.onlineFactoryCalls)
            assertEquals(0, fixture.companionCalls)
            assertTrue(requireNotNull(fixture.client).texts.isEmpty())
            fixture.assertSingleBundledPlayback("hr_rapid_rise")
        } finally {
            fixture.engine.shutdown()
        }
    }

    @Test
    fun 紧急抢占后迟到Fish结果不得补播() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val late = CompletableDeferred<File>()
        val workerFinished = CompletableDeferred<Unit>()
        val fixture = Fixture(targetContext())
        fixture.client = RecordingFishClient {
            entered.complete(Unit)
            try {
                withContext(NonCancellable) { late.await() }
            } finally {
                workerFinished.complete(Unit)
            }
        }

        try {
            val old = async {
                fixture.engine.speak(request("session_paused", SpeechPriority.APP, "跑步已暂停。"))
            }
            withTimeout(2_000L) { entered.await() }

            val urgent = fixture.engine.speak(
                request("hr_z5_danger", SpeechPriority.URGENT, "不应播报的调用方文本", companion = true),
            ).getOrThrow()
            assertEquals(SpeechDelivery("心率过高，请立即放慢", played = true), urgent)
            withTimeout(2_000L) { old.join() }
            assertTrue(old.isCancelled)

            late.complete(File(targetContext().cacheDir, "迟到在线结果.mp3"))
            withTimeout(2_000L) { workerFinished.await() }
            yield()

            assertEquals(1, fixture.onlineFactoryCalls)
            assertEquals(0, fixture.companionCalls)
            assertEquals(listOf("跑步已暂停。"), requireNotNull(fixture.client).texts)
            fixture.assertSingleBundledPlayback("hr_z5_danger")
            assertTrue(requireNotNull(fixture.client).cancelCalls > 0)
        } finally {
            late.cancel()
            fixture.engine.shutdown()
        }
    }

    /** 只播放 APK 内置 hr_rapid_rise，不访问云端或 voice_audition 缓存。 */
    @Test
    fun 显式授权后试听真实内置核心且不启动跑步() {
        assumeTrue(
            "内置语音试听必须由用户戴好耳机后显式开启",
            InstrumentationRegistry.getArguments().getString("allowBundledVoicePreview") == "true",
        )
        runBlocking {
            val koin = GlobalContext.get()
            val repository = koin.get<RunRepository>()
            assertNull("有未结束跑步，不执行试听", repository.getActiveSession())

            val logger = koin.get<DiagnosticLogger>()
            var onlineFactoryCalls = 0
            var companionCalls = 0
            val engine = VoicePolicyTtsEngine(
                bundledStore = AndroidBundledVoiceStore(targetContext()),
                onlineFactory = { onlineFactoryCalls++; null },
                companionTextSource = object : CompanionTextSource {
                    override suspend fun create(request: SpeechRequest): String? {
                        companionCalls++
                        return null
                    }
                },
                playerFactory = {
                    PreparedVoicePlayer(
                        audioPlayerFactory = { AndroidAudioPlayerPort() },
                        audioFocusController = koin.get<AudioFocusController>(),
                        diagnosticLogger = logger,
                    )
                },
                diagnosticLogger = logger,
            )
            try {
                val delivery = withTimeout(45_000L) {
                    engine.speak(request("hr_rapid_rise", SpeechPriority.URGENT, "不应播报的调用方文本"))
                }.getOrThrow()
                assertEquals(SpeechDelivery("心率上升很快，控制节奏", played = true), delivery)
                assertEquals(0, onlineFactoryCalls)
                assertEquals(0, companionCalls)
            } finally {
                engine.shutdown()
                logger.flush()
                assertNull("试听不应新增活动跑步", repository.getActiveSession())
            }
        }
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun request(
        ruleId: String,
        priority: SpeechPriority,
        speakText: String,
        companion: Boolean = false,
    ) = SpeechRequest(
        trigger = RuleTrigger(ruleId, Severity.INFO, speakText, priority),
        companionEnabled = companion,
    )

    private class Fixture(private val context: Context) {
        val players = mutableListOf<RecordingPlayer>()
        var client: RecordingFishClient? = null
        var onlineFactoryCalls = 0
        var companionCalls = 0
        val engine = VoicePolicyTtsEngine(
            bundledStore = AndroidBundledVoiceStore(context),
            onlineFactory = {
                onlineFactoryCalls++
                client
            },
            companionTextSource = object : CompanionTextSource {
                override suspend fun create(request: SpeechRequest): String? {
                    companionCalls++
                    return null
                }
            },
            playerFactory = { RecordingPlayer().also(players::add) },
            diagnosticLogger = RecordingDiagnosticLogger(),
        )

        fun assertSingleBundledPlayback(ruleId: String) {
            val playback = players.single().playbacks.single()
            assertEquals(VoiceAudioSource.BUNDLED, playback.source)
            assertEquals("$ruleId.mp3", playback.file.name)
            assertEquals(File(context.filesDir, "voice/liuying/v1").canonicalFile, playback.file.parentFile?.canonicalFile)
            assertTrue("APK固定语音应恢复为非空文件", playback.file.isFile && playback.file.length() > 0L)
        }
    }

    private class RecordingFishClient(
        private val synthesis: suspend (String) -> File,
    ) : FishSynthesisClient {
        val texts = mutableListOf<String>()
        var cancelCalls = 0

        override suspend fun synthesize(text: String): File {
            texts += text
            return synthesis(text)
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun shutdown() = Unit
    }

    private class RecordingPlayer : VoiceFilePlayer {
        val playbacks = mutableListOf<Playback>()
        private lateinit var prepared: Playback

        override suspend fun prepare(file: File, source: VoiceAudioSource): Result<Unit> {
            prepared = Playback(file, source)
            return Result.success(Unit)
        }

        override suspend fun play(): Result<Unit> {
            playbacks += prepared
            return Result.success(Unit)
        }

        override fun stop() = Unit

        override fun shutdown() = Unit
    }

    private data class Playback(val file: File, val source: VoiceAudioSource)

    private class RecordingDiagnosticLogger : DiagnosticLogger {
        val events = mutableListOf<DiagnosticEvent>()

        override fun record(event: DiagnosticEvent) {
            events += event
        }

        override suspend fun awaitIdle() = Unit

        override suspend fun flush() = Unit
    }
}
