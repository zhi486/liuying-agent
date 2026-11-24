package com.healthyagent.running.speech

import com.healthyagent.running.ai.DeepseekRequestConfig
import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.testing.DeepseekVoiceFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeepseekVoiceIntegrationTest {
    @Test fun `自然提及配速与一步的公里鼓励通过实际过滤后整段合成`() = runTest {
        val core = "第一公里，用时六分整，配速每公里六分整。"
        for (suffix in listOf("不必纠结配速，我会陪在你身边。", "又迈出了一步，我为你开心。", "这六分钟的陪伴，我很珍惜。")) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            val km = request("km_split", SpeechPriority.SPLIT)
            voice.bus.enqueue(km.copy(trigger = km.trigger.copy(speakText = core)))
            runCurrent()
            voice.calls.single().respond(core + suffix)
            runCurrent()
            assertEquals(listOf(core + suffix), voice.fishTexts)
            assertEquals(1, voice.startedFiles.size)
            voice.ports.single().listener.onCompletion()
            runCurrent()
            assertEquals(core + suffix, voice.bus.latestMessage.value?.text)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `公里核定建议整段播放而改数遗漏建议或新增动作只回退核心`() = runTest {
        val core = "第一公里，用时六分整，配速每公里六分整。"
        val guidance = KilometerGuidance.HIGH_HEART_RATE
        val whole = core + guidance.text + "开拓者，我会一直陪着你。"
        for (response in listOf(whole, whole.replace("六分整", "五分整"), core + "我会陪着你。", whole + "加快脚步。", whole + "放慢一点。")) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            val km = request("km_split", SpeechPriority.SPLIT)
            voice.bus.enqueue(km.copy(trigger = km.trigger.copy(speakText = core), kilometerGuidance = guidance))
            runCurrent()
            assertEquals(1, voice.calls.size)
            voice.calls.single().respond(response)
            runCurrent()
            assertEquals(listOf(if (response == whole) whole else core), voice.fishTexts)
            assertEquals(1, voice.startedFiles.size)
            assertTrue(voice.localRules.isEmpty())
            voice.ports.single().listener.onCompletion()
            runCurrent()
            assertEquals(1, voice.focusReleases)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `AI返回完整公里与多句鼓励直接整段合成一次不再拼接`() = runTest {
        val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
        val core = "第一公里，用时六分整，配速每公里六分整。"
        val companion = "开拓者，我们又一起完成了一公里。我会一直陪着你。"
        val kmRequest = request("km_split", SpeechPriority.SPLIT)
        voice.bus.enqueue(kmRequest.copy(trigger = kmRequest.trigger.copy(speakText = core)))
        runCurrent()
        assertEquals(1, voice.calls.size)
        voice.calls.single().respond(core + companion)
        runCurrent()
        assertEquals(listOf(core + companion), voice.fishTexts)
        assertTrue(voice.localRules.isEmpty())
        voice.ports.single().listener.onCompletion()
        runCurrent()
        assertEquals(core + companion, voice.bus.latestMessage.value?.text)
        assertEquals(1, voice.focusReleases)
        voice.bus.shutdown()
        runCurrent()
    }

    @Test fun `有效鼓励和核心仅合成一次且准备阶段不取焦点`() = runTest {
        val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
        voice.bus.enqueueSessionStatus(request())
        runCurrent()
        assertEquals(1, voice.calls.size)
        assertTrue(voice.fishTexts.isEmpty())
        assertEquals(0, voice.focusRequests)
        assertTrue(voice.calls.single().respond().closed)
        runCurrent()
        val whole = "跑步已暂停。能陪着你，我很开心。"
        assertEquals(listOf(whole), voice.fishTexts)
        assertTrue(voice.localRules.isEmpty())
        assertNull(voice.bus.latestMessage.value)
        assertEquals(1, voice.focusRequests)
        voice.ports.single().listener.onCompletion()
        runCurrent()
        assertEquals(whole, voice.bus.latestMessage.value?.text)
        assertEquals(1, voice.focusReleases)
        voice.bus.shutdown()
        runCurrent()
    }

    @Test fun `两秒超时取消真实Call而Fish只合成核心且迟到不得再播`() = runTest {
        val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
        voice.bus.enqueueSessionStatus(request())
        runCurrent()
        advance(voice, 1_999)
        assertTrue(voice.fishTexts.isEmpty())
        advance(voice, 1)
        assertTrue(voice.calls.single().isCanceled())
        assertEquals(listOf("跑步已暂停。"), voice.fishTexts)
        assertTrue(voice.calls.single().respond().closed)
        runCurrent()
        assertEquals(1, voice.fishTexts.size)
        assertEquals(listOf(voice.onlineFile), voice.startedFiles)
        voice.bus.shutdown()
        runCurrent()
    }

    @Test fun `关闭未配置与无效响应保留核心且缺Fish不请求文字`() = runTest {
        for (mode in listOf("off", "missing", "invalid", "auth", "no-fish")) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            voice.fishConfigured = mode != "no-fish"
            voice.bus.enqueueSessionStatus(when (mode) {
                "off" -> request().copy(companionEnabled = false)
                "missing" -> request().copy(deepseekConfig = null)
                else -> request()
            })
            runCurrent()
            if (mode == "invalid" || mode == "auth") {
                assertEquals(1, voice.calls.size)
                voice.calls.single().respond("加速再跑三公里。", if (mode == "auth") 401 else 200)
                runCurrent()
            } else assertTrue(voice.calls.isEmpty())
            assertEquals(if (mode == "no-fish") emptyList<String>() else listOf("跑步已暂停。"), voice.fishTexts)
            assertEquals(listOf(if (mode == "no-fish") voice.localFile else voice.onlineFile), voice.startedFiles)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `紧急与BLE通知即使携带配置也只有内置音频`() = runTest {
        for (rule in listOf("hr_rapid_rise", "data_stale")) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            voice.bus.enqueue(request(rule, if (rule == "hr_rapid_rise") SpeechPriority.URGENT else SpeechPriority.APP))
            runCurrent()
            assertTrue(voice.calls.isEmpty())
            assertTrue(voice.fishClients.isEmpty())
            assertEquals(listOf(rule), voice.localRules)
            assertEquals(listOf(voice.localFile), voice.startedFiles)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `Fish失败时普通仅内置核心而公里静默`() = runTest {
        for (rule in listOf("session_paused", "km_split")) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            voice.fishFails = true
            voice.bus.enqueue(request(rule))
            runCurrent()
            voice.calls.single().respond()
            runCurrent()
            assertEquals(1, voice.fishTexts.size)
            assertEquals(if (rule == "km_split") emptyList<String>() else listOf(rule), voice.localRules)
            assertEquals(if (rule == "km_split") emptyList() else listOf(voice.localFile), voice.startedFiles)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `排队及文字等待包含在普通八秒和公里十五秒总期限内`() = runTest {
        for ((rule, limit) in listOf("session_paused" to 8_000L, "km_split" to 15_000L)) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            voice.holdFish = true
            voice.bus.enqueue(request(rule))
            val textWait = if (rule == "km_split") 6_000L else 2_000L
            // 接纳后模拟排队消耗，文字超时后仅留一秒用于语音合成。
            voice.clock += (limit - textWait - 1_000).milliseconds
            runCurrent()
            assertEquals(1, voice.calls.size)
            advance(voice, textWait)
            assertTrue(voice.calls.single().isCanceled())
            assertEquals(1, voice.fishTexts.size)
            assertEquals(0, voice.focusRequests)
            advance(voice, 999)
            assertTrue(voice.startedFiles.isEmpty())
            advance(voice, 1)
            assertTrue(voice.fishClients.single().cancelCalls > 0)
            assertTrue(voice.calls.single().respond().closed)
            voice.fishClients.single().gate.complete(voice.onlineFile)
            runCurrent()
            assertEquals(if (rule == "km_split") emptyList() else listOf(voice.localFile), voice.startedFiles)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `公里排队后文字等待不能超出剩余三秒期限`() = runTest {
        val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
        voice.bus.enqueue(request("km_split"))
        voice.clock += 12_000.milliseconds
        runCurrent()
        advance(voice, 2_999)
        assertFalse(voice.calls.single().isCanceled())
        advance(voice, 1)
        assertTrue(voice.calls.single().isCanceled())
        assertTrue(voice.calls.single().respond().closed)
        runCurrent()
        assertTrue(voice.fishTexts.isEmpty())
        assertTrue(voice.startedFiles.isEmpty())
        assertEquals(0, voice.focusRequests)
        voice.bus.shutdown()
        runCurrent()
    }

    @Test fun `紧急抢占和停止取消文字且旧响应不播放`() = runTest {
        for (urgent in listOf(false, true)) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            voice.bus.enqueueSessionStatus(request())
            runCurrent()
            if (urgent) voice.bus.enqueue(request("hr_rapid_rise", SpeechPriority.URGENT)) else voice.bus.stop()
            // 无需等待总线 actor，停止或抢占返回时网络必须已经收到取消。
            assertTrue(voice.calls.single().isCanceled())
            runCurrent()
            assertTrue(voice.calls.single().isCanceled())
            assertTrue(voice.calls.single().respond().closed)
            runCurrent()
            assertTrue(voice.fishTexts.isEmpty())
            assertEquals(if (urgent) listOf(voice.localFile) else emptyList(), voice.startedFiles)
            voice.bus.shutdown()
            runCurrent()
        }
    }

    @Test fun `停止或紧急抢占在媒体启动门前返回后旧声音不能启动`() = runTest {
        for (urgent in listOf(false, true)) {
            val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
            voice.bus.enqueueSessionStatus(request())
            runCurrent()
            voice.beforeMediaStart = {
                voice.beforeMediaStart = {}
                if (urgent) voice.bus.enqueue(request("hr_rapid_rise", SpeechPriority.URGENT)) else voice.bus.stop()
            }
            voice.calls.single().respond()
            runCurrent()
            assertEquals(if (urgent) listOf(voice.localFile) else emptyList(), voice.startedFiles)
            assertEquals(1, voice.calls.size)
            voice.bus.shutdown()
            runCurrent()
            assertEquals(voice.focusRequests, voice.focusReleases)
        }
    }

    @Test fun `紧急后的pending保留原接纳预算`() = runTest {
        val voice = DeepseekVoiceFixture(StandardTestDispatcher(testScheduler))
        voice.bus.enqueue(request("hr_rapid_rise", SpeechPriority.URGENT))
        runCurrent()
        voice.bus.enqueueSessionStatus(request())
        advance(voice, 8_000)
        voice.ports.single().listener.onCompletion()
        runCurrent()
        assertTrue(voice.calls.isEmpty())
        assertTrue(voice.fishClients.isEmpty())
        assertEquals(listOf("hr_rapid_rise", "session_paused"), voice.localRules)
        voice.bus.shutdown()
        runCurrent()
    }

    private fun request(ruleId: String = "session_paused", priority: SpeechPriority = SpeechPriority.APP) = SpeechRequest(
        RuleTrigger(ruleId, Severity.NORMAL, if (ruleId == "km_split") "第一公里，用时六分整，配速每公里六分整。" else "占位核心。", priority),
        companionEnabled = true,
        deepseekConfig = DeepseekRequestConfig("test-only-deepseek", CoachTone.BALANCED),
    )

    private fun TestScope.advance(voice: DeepseekVoiceFixture, millis: Long) {
        voice.clock += millis.milliseconds
        advanceTimeBy(millis)
        runCurrent()
    }
}
