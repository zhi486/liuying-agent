package com.healthyagent.running.speech

import com.healthyagent.running.core.engine.RuleCatalog
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledVoiceAssetsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `真实规则与会话事件的固定语音资源可由store逐个恢复`() = runBlocking {
        val ruleIds = RuleCatalog.defaultRules().filterNot { it.id == "km_split" }.map { it.id }
        val sessionIds = SessionSpeechEvent.entries.map { SessionSpeechTriggerFactory.create(it).ruleId }
        val requiredIds = ruleIds + sessionIds
        assertEquals(14, requiredIds.size)
        assertEquals(requiredIds.toSet().size, requiredIds.size)
        assertEquals(BundledVoiceCatalog.entries.map { it.ruleId }.toSet(), requiredIds.toSet())

        val store = store(::openRealAsset)
        requiredIds.forEach { ruleId ->
            val voice = requireNotNull(BundledVoiceCatalog.find(ruleId))
            val opened = store.open(ruleId)
            assertNotNull("固定语音资源缺失：$ruleId", opened)
            assertArrayEquals(
                "恢复后的字节必须来自APK资产：$ruleId",
                openRealAsset("voice/liuying/v1/${voice.fileName}").use { it.readBytes() },
                requireNotNull(opened).readBytes(),
            )
        }
    }

    @Test
    fun `缺失音频哈希错误和未知ID统一返回null而不产生替代资源`() = runBlocking {
        val voice = BundledVoiceCatalog.entries.first()
        val missing = store { path ->
            if (path.endsWith("/${voice.fileName}")) throw FileNotFoundException(path)
            openRealAsset(path)
        }
        assertNull(missing.open(voice.ruleId))

        val corrupted = store { path ->
            if (path.endsWith("/${voice.fileName}")) "不是清单中的音频".byteInputStream()
            else openRealAsset(path)
        }
        assertNull(corrupted.open(voice.ruleId))

        var unknownReads = 0
        val unknown = store { path ->
            unknownReads += 1
            openRealAsset(path)
        }
        assertNull(unknown.open("unknown"))
        assertEquals(0, unknownReads)
    }

    private fun store(openAsset: (String) -> InputStream) =
        AndroidBundledVoiceStore(openAsset, temporary.newFolder())

    private fun openRealAsset(path: String): InputStream = File("src/main/assets", path).inputStream()
}
