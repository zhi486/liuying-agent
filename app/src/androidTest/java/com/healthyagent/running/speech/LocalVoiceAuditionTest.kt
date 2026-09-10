package com.healthyagent.running.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.diagnostics.DiagnosticLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 临时试听工具：只播放白名单本地小样，不联网、不定位、不建立跑步记录。 */
@RunWith(AndroidJUnit4::class)
class LocalVoiceAuditionTest {
    @Test
    fun playOnePreparedSample() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("需用户明确准备好才播放", arguments.getString("allowLocalVoiceAudition") == "true")
        val sample = arguments.getString("auditionSample")
        require(sample in setOf("core", "stitched", "companion", "continuous")) { "未知试听小样" }
        runBlocking {
            val koin = GlobalContext.get()
            val repository = koin.get<RunRepository>()
            assertNull("有未结束跑步，不执行试听", repository.getActiveSession())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "voice_audition/sample-$sample.mp3")
            assertTrue("小样不存在或为空", file.isFile && file.length() > 0L)
            val logger = koin.get<DiagnosticLogger>()
            val localSource = object : FishSynthesisClient {
                override suspend fun synthesize(text: String): File = file
                override fun cancel() = Unit
                override fun shutdown() = Unit
            }
            val engine = FishAudioTtsEngine(
                synthesisClient = localSource,
                audioPlayerFactory = { AndroidAudioPlayerPort() },
                audioFocusController = koin.get<AudioFocusController>(),
                diagnosticLogger = logger,
            )
            try {
                assertTrue("本地小样播放未正常完成", withTimeout(45_000L) {
                    engine.speak("本地试听：$sample", SpeechPriority.APP)
                }.isSuccess)
            } finally {
                engine.shutdown()
                logger.flush()
            }
            assertNull("试听不应新增活动跑步", repository.getActiveSession())
        }
    }
}
