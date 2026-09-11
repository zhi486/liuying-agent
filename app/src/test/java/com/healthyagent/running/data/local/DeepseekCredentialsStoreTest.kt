package com.healthyagent.running.data.local

import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.SpeechRequest
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeepseekCredentialsStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val file get() = File(temporaryFolder.root, "deepseek/key")

    @Test fun `不存在时未配置且不会创建文件`() {
        val store = DeepseekCredentialsStore(file)
        assertFalse(store.configured.value)
        assertNull(store.capture(CoachTone.BALANCED))
        assertFalse(file.exists())
    }

    @Test fun `保存裁剪值可重载且更换不影响已捕获请求`() {
        val store = DeepseekCredentialsStore(file)
        assertTrue(store.save("  test-only-first  "))
        assertTrue(store.configured.value)
        val first = store.capture(CoachTone.BALANCED)!!
        assertEquals("test-only-first", first.apiKey)
        assertTrue(store.save("test-only-next"))
        assertEquals("test-only-first", first.apiKey)
        assertEquals("test-only-next", DeepseekCredentialsStore(file).capture(CoachTone.BALANCED)!!.apiKey)
    }

    @Test fun `无效输入保留原凭据而最大长度有效`() {
        val store = DeepseekCredentialsStore(file)
        val valid = "a".repeat(512)
        assertTrue(store.save(valid))
        listOf("", "   ", "bad key", "bad\nkey", "bad\u0000key", "bad\u007fkey", "a".repeat(513)).forEach {
            assertFalse(store.save(it))
            assertEquals(valid, store.capture(CoachTone.BALANCED)!!.apiKey)
            assertEquals(valid, file.readText())
        }
    }

    @Test fun `写入失败不破坏旧文件且重载仍为旧值`() {
        val store = DeepseekCredentialsStore(file)
        assertTrue(store.save("test-only-first"))
        assertTrue(File(file.parentFile, "key.tmp").mkdir())
        assertFalse(store.save("test-only-next"))
        assertEquals("test-only-first", store.capture(CoachTone.BALANCED)!!.apiKey)
        assertEquals("test-only-first", DeepseekCredentialsStore(file).capture(CoachTone.BALANCED)!!.apiKey)
    }

    @Test fun `损坏或过大文件不会被视为已配置`() {
        file.parentFile!!.mkdirs()
        listOf("bad key", "x".repeat(4096)).forEach {
            file.writeText(it)
            val store = DeepseekCredentialsStore(file)
            assertFalse(store.configured.value)
            assertNull(store.capture(CoachTone.BALANCED))
        }
    }

    @Test fun `配置对象及请求字符串隐藏凭据`() {
        val store = DeepseekCredentialsStore(file)
        assertTrue(store.save("test-only-hidden"))
        val config = store.capture(CoachTone.BALANCED)!!
        val request = SpeechRequest(RuleTrigger("session_paused", Severity.NORMAL, "跑步已暂停。", SpeechPriority.APP),
            companionEnabled = true, deepseekConfig = config)
        assertFalse(config.toString().contains("test-only-hidden"))
        assertFalse(request.toString().contains("test-only-hidden"))
    }

    @Test fun `损坏的UTF8不替换成看似有效的凭据`() {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFalse(DeepseekCredentialsStore(file).configured.value)
    }
}
