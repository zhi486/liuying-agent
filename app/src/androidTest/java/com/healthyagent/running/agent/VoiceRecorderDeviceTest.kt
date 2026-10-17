package com.healthyagent.running.agent

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.voice.AndroidVoiceRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅在关闭宿主音频的隔离模拟器验证 AudioRecord，不上传音频。 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderDeviceTest {
    @Test fun 原生PCM可停止且取消后释放录音资源() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        withTimeout(10000) {
            val recorder = AndroidVoiceRecorder()
            val cancelled = async { recorder.record(AtomicBoolean(false)) }
            delay(500)
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
            val stop = AtomicBoolean(false)
            val pending = async { recorder.record(stop) }
            delay(1000)
            stop.set(true)
            val pcm = pending.await()
            try {
                assertTrue(pcm.size in 3200..960000)
                assertEquals(0, pcm.size % 2)
            } finally { pcm.fill(0) }
        }
    }
}
