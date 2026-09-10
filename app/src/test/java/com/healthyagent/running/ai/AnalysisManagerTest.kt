package com.healthyagent.running.ai

import com.healthyagent.running.speech.SpeechBus
import com.healthyagent.running.speech.SpeechPolicy
import com.healthyagent.running.testing.FakeTtsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisManagerTest {
    @Test
    fun `取消后旧会话的非协作式结果不会进入新会话播报`() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val client = object : LlmClient {
            override suspend fun analyze(request: LlmAnalyzeRequest): Result<LlmAnalyzeResponse> {
                val gate = if (request.session_id == "old") oldGate else newGate
                withContext(NonCancellable) { gate.await() }
                return Result.success(LlmAnalyzeResponse("${request.session_id}-建议"))
            }
        }
        val tts = FakeTtsEngine()
        val manager = AnalysisManager(client, scope = backgroundScope).apply {
            speechBus = SpeechBus(
                ttsEngine = tts,
                initialPolicy = SpeechPolicy(enabled = true, minIntervalSec = 0),
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        }

        manager.analyze(request("old"))
        runCurrent()
        manager.cancel()
        manager.analyze(request("new"))
        runCurrent()

        newGate.complete(Unit)
        runCurrent()
        tts.completeNextSuccessfully()
        runCurrent()
        oldGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("new-建议"), tts.completedTexts)
    }

    @Test
    fun `分析提交后语音会话切换会拒绝旧结果`() = runTest {
        val responseGate = CompletableDeferred<Unit>()
        val client = object : LlmClient {
            override suspend fun analyze(request: LlmAnalyzeRequest): Result<LlmAnalyzeResponse> {
                responseGate.await()
                return Result.success(LlmAnalyzeResponse("旧会话建议"))
            }
        }
        val tts = FakeTtsEngine()
        val bus = SpeechBus(
            ttsEngine = tts,
            initialPolicy = SpeechPolicy(enabled = true, minIntervalSec = 0),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val manager = AnalysisManager(client, scope = backgroundScope).apply {
            speechBus = bus
        }

        manager.analyze(request("old"))
        runCurrent()
        bus.resetSession()
        responseGate.complete(Unit)
        runCurrent()

        assertEquals(emptyList<String>(), tts.completedTexts)
    }

    private fun request(sessionId: String) = LlmAnalyzeRequest(
        session_id = sessionId,
        split_index = 1,
        split_data = LlmSplitData(
            split_index = 1,
            distance_km = 1.0,
            duration_sec = 360,
            avg_pace_sec_per_km = 360.0,
        ),
        session_summary = LlmSessionSummary(
            total_distance_km = 1.0,
            total_duration_sec = 360,
            avg_pace_sec_per_km = 360.0,
        ),
    )
}
