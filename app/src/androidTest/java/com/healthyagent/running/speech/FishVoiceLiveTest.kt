package com.healthyagent.running.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.core.engine.rules.KmSplitRule
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.core.model.Split
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 仅经用户明确授权并传入开关时合成一句；普通测试运行不访问云端。 */
@RunWith(AndroidJUnit4::class)
class FishVoiceLiveTest {
    /** 仅模拟一个已完成公里事件，不启动定位、不保存虚拟跑步记录。 */
    @Test
    fun configuredVoiceSpeaksSimulatedKmSplit() {
        assumeTrue(
            "公里实听必须显式开启，可能消耗 Fish API 额度",
            InstrumentationRegistry.getArguments().getString("allowFishKmPreview") == "true",
        )
        runBlocking {
            val koin = GlobalContext.get()
            val settings = koin.get<SettingsRepository>().current()
            assertTrue("手机语音提醒未开启", settings.ttsEnabled)
            assertTrue("手机未配置 Fish Key", settings.fishApiKey.isNotBlank())
            assertTrue("手机未配置流萤音色", settings.fishReferenceId.isNotBlank())
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
                bus.enqueue(SpeechRequest(trigger))
                assertEquals(SpeechOutcomeReason.TTS_SUCCESS, withTimeout(90_000L) { completion.await() })
                val message = withTimeout(90_000L) {
                    bus.latestMessage.first { it?.text == trigger.speakText }
                }
                assertEquals(SpeechPriority.SPLIT, message?.priority)
            } finally {
                bus.stop()
                logger.flush()
            }
        }
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
