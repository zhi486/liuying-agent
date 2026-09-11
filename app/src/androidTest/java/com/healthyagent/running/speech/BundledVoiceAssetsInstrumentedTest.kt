package com.healthyagent.running.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.core.engine.RuleCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledVoiceAssetsInstrumentedTest {
    @Test
    fun apk内固定语音覆盖真实规则和会话事件并可恢复到私有目录() = runBlocking {
        val ruleIds = RuleCatalog.defaultRules().filterNot { it.id == "km_split" }.map { it.id }
        val sessionIds = SessionSpeechEvent.entries.map { SessionSpeechTriggerFactory.create(it).ruleId }
        val requiredIds = ruleIds + sessionIds + WearableLinkSpeech.create().ruleId
        assertEquals(14, requiredIds.size)
        assertEquals(BundledVoiceCatalog.entries.map { it.ruleId }.toSet(), requiredIds.toSet())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBundledVoiceStore(context)
        requiredIds.forEach { ruleId ->
            val opened = store.open(ruleId)
            assertNotNull("APK固定语音资源缺失或损坏：$ruleId", opened)
            requireNotNull(opened)
            assertEquals("${ruleId}.mp3", opened.name)
            assertEquals(File(context.filesDir, "voice/liuying/v1").canonicalFile, opened.parentFile?.canonicalFile)
        }
    }
}
