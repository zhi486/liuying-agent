package com.healthyagent.running.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import android.view.WindowManager
import com.healthyagent.running.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.core.engine.rules.KmSplitRule
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.core.model.Split
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.ai.PromptBuilder
import com.healthyagent.running.data.local.DeepseekCredentialsStore
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 仅经用户明确授权并传入开关时合成一句；普通测试运行不访问云端。 */
@RunWith(AndroidJUnit4::class)
class FishVoiceLiveTest {
    /** 可见页面承载试听，避免无界面 instrumentation 被厂商后台冻结；只对该窗口保持亮屏。 */
    private fun foregroundPreview(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            var wasKeepingScreenOn = false
            activity.onActivity {
                wasKeepingScreenOn = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            assertEquals("试听页面必须处于前台", Lifecycle.State.RESUMED, activity.state)
            try { block() } finally {
                activity.onActivity {
                    if (!wasKeepingScreenOn) it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    @Test
    fun foregroundPreviewRemainsResponsiveWithoutNetwork() = foregroundPreview {
        // 超过此前约五秒的冻结点；不录音、不播放、不调用任何模型。
        val started = android.os.SystemClock.elapsedRealtime()
        runBlocking { withTimeout(25_000L) { delay(20_000L) } }
        assertTrue("前台无网络验证超过时限，仍可能发生冻结", android.os.SystemClock.elapsedRealtime() - started < 25_000L)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { }
    }

    /** 只播一条“固定核心＋实时陪伴”；不启动跑步、不写入记录。 */
    @Test
    fun configuredDeepseekAndFishSpeakOneWholePhrase() {
        assumeTrue(
            "整句实听必须显式开启，可能各消耗一次 DeepSeek 与 Fish API 额度",
            InstrumentationRegistry.getArguments().getString("allowDeepseekFishPreview") == "true",
        )
        runBlocking {
            val koin = GlobalContext.get()
            val settings = koin.get<SettingsRepository>().current()
            val repository = koin.get<RunRepository>()
            val sessionsBefore = repository.getAllSessions().size
            assertTrue("手机语音提醒未开启", settings.ttsEnabled)
            assertTrue("AI 陪伴未开启", settings.llmEnabled)
            assertTrue("手机未配置 Fish Key", settings.fishApiKey.isNotBlank())
            assertTrue("手机未配置流萤音色", settings.fishReferenceId.isNotBlank())
            assertTrue("当前存在未结束的跑步，拒绝试听", repository.getActiveSession() == null)
            val deepseekConfig = requireNotNull(
                koin.get<DeepseekCredentialsStore>().capture(settings.coachTone),
            ) { "手机未配置 DeepSeek Key" }
            val core = "跑步已暂停。"
            val engine = koin.get<TtsEngine>()
            val logger = koin.get<DiagnosticLogger>()
            try {
                val result = withTimeout(90_000L) {
                    engine.speak(SpeechRequest(
                        trigger = RuleTrigger(
                            "session_paused", Severity.NORMAL, core, SpeechPriority.APP,
                        ),
                        companionEnabled = true,
                        deepseekConfig = deepseekConfig,
                    ))
                }
                val delivery = result.getOrThrow()
                assertTrue("整句试听未实际完成，请检查脱敏诊断", delivery.played)
                assertTrue("DeepSeek 未返回有效陪伴句", delivery.text.startsWith(core) && delivery.text.length > core.length)
                assertEquals("试听不应创建跑步记录", sessionsBefore, repository.getAllSessions().size)
                assertTrue("试听不应创建活动跑步", repository.getActiveSession() == null)
            } finally {
                engine.stop()
                logger.flush()
            }
        }
    }

    /** 仅模拟一个已完成公里事件，不启动定位、不保存虚拟跑步记录。 */
    @Test
    fun configuredVoiceSpeaksSimulatedKmSplit() {
        assumeTrue(
            "公里实听必须显式开启，可能消耗 Fish API 额度",
            InstrumentationRegistry.getArguments().getString("allowFishKmPreview") == "true",
        )
        val companion = InstrumentationRegistry.getArguments()
            .getString("allowDeepseekFishKmPreview") == "true"
        foregroundPreview { runBlocking {
            val koin = GlobalContext.get()
            val settings = koin.get<SettingsRepository>().current()
            val repository = koin.get<RunRepository>()
            val sessionsBefore = repository.getAllSessions().size
            assertTrue("当前存在未结束的跑步，拒绝试听", repository.getActiveSession() == null)
            assertTrue("手机语音提醒未开启", settings.ttsEnabled)
            assertTrue("手机未配置 Fish Key", settings.fishApiKey.isNotBlank())
            assertTrue("手机未配置流萤音色", settings.fishReferenceId.isNotBlank())
            val deepseekConfig = if (companion) {
                assertTrue("AI 陪伴未开启", settings.llmEnabled)
                requireNotNull(koin.get<DeepseekCredentialsStore>().capture(settings.coachTone)) {
                    "手机未配置 DeepSeek Key"
                }
            } else null
            val split = Split(
                index = 1,
                distanceMeters = 1_000.0,
                durationSec = 360L,
                avgHeartRate = null,
                avgCadence = null,
                avgPaceSecPerKm = 360.0,
                hrZoneDistribution = null,
            )
            val trigger = requireNotNull(
                KmSplitRule().evaluate(RuleContext(completedSplit = split), RunConfig()),
            )
            assertEquals("km_split", trigger.ruleId)
            assertEquals(SpeechPriority.SPLIT, trigger.ttsPriority)
            assertEquals("第一公里，用时六分整，配速每公里六分整。", trigger.speakText)
            val logger = koin.get<DiagnosticLogger>()
            val completion = CompletableDeferred<SpeechOutcomeReason>()
            val bus = SpeechBus(koin.get<TtsEngine>(), diagnosticLogger = object : DiagnosticLogger by logger {
                override fun record(event: DiagnosticEvent) {
                    logger.record(event)
                    if (event is DiagnosticEvent.SpeechOutcome && event.ruleId == "km_split") {
                        completion.complete(event.reason)
                    }
                }
            })
            bus.configure(SpeechPolicy(settings.ttsEnabled, settings.ttsMinIntervalSec))
            try {
                bus.enqueue(SpeechRequest(
                    trigger,
                    companionEnabled = companion,
                    deepseekConfig = deepseekConfig,
                    analysis = if (companion) PromptBuilder.build(
                        RunSession("km-preview", 0L, totalDistanceMeters = 1_000.0,
                            totalDurationSec = 360L, avgPaceSecPerKm = 360.0),
                        split, RunConfig(),
                    ) else null,
                ))
                assertEquals(SpeechOutcomeReason.TTS_SUCCESS, withTimeout(90_000L) { completion.await() })
                val message = withTimeout(90_000L) {
                    bus.latestMessage.first { it != null }
                }
                assertEquals(SpeechPriority.SPLIT, message?.priority)
                val text = requireNotNull(message).text
                assertTrue("播报缺少公里核心", text.startsWith(trigger.speakText))
                if (companion) assertTrue("DeepSeek 未返回有效公里鼓励", text.length > trigger.speakText.length)
                else assertEquals(trigger.speakText, text)
                assertEquals("试听不应创建跑步记录", sessionsBefore, repository.getAllSessions().size)
                assertTrue("试听不应创建活动跑步", repository.getActiveSession() == null)
            } finally {
                bus.shutdown()
                logger.flush()
            }
        } }
    }

    @Test
    fun configuredVoiceSpeaksOnePhrase() {
        assumeTrue(
            "真人试听必须显式开启，可能消耗 Fish API 额度",
            InstrumentationRegistry.getArguments().getString("allowFishVoicePreview") == "true",
        )
        runBlocking {
            val koin = GlobalContext.get()
            val settings = koin.get<SettingsRepository>().current()
            assertTrue("手机语音提醒未开启", settings.ttsEnabled)
            assertTrue("手机未配置 Fish Key", settings.fishApiKey.isNotBlank())
            assertTrue("手机未配置流萤音色", settings.fishReferenceId.isNotBlank())
            val engine = koin.get<TtsEngine>()
            val logger = koin.get<DiagnosticLogger>()
            try {
                val result = withTimeout(90_000L) {
                    engine.speak(SpeechRequest(RuleTrigger(
                        "voice_preview", Severity.NORMAL,
                        "开拓者，我是流萤。准备好一起跑步了吗？", SpeechPriority.APP,
                    )))
                }
                assertTrue("试听未实际完成，请检查脱敏诊断", result.getOrNull()?.played == true)
            } finally {
                engine.stop()
                logger.flush()
            }
        }
    }
}
