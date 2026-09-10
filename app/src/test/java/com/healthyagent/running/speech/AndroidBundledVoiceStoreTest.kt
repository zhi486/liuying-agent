package com.healthyagent.running.speech

import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidBundledVoiceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val reads = mutableListOf<String>()
    private val assets = mutableMapOf<String, ByteArray>()

    @Test fun `校验清单哈希后复制至私有目录且修复损坏副本`() = runBlocking {
        installManifest()
        assets["voice/liuying/v1/hr_z5_danger.mp3"] = "abc".toByteArray()
        val directory = temporary.newFolder()
        val store = AndroidBundledVoiceStore({ path -> read(path) }, directory)
        val opened = store.open("hr_z5_danger")
        assertNotNull(opened)
        val first = requireNotNull(opened)
        assertEquals(directory.canonicalFile, first.parentFile!!.canonicalFile)
        assertEquals("abc", first.readText())
        first.writeText("损坏")
        assertEquals("abc", store.open("hr_z5_danger")!!.readText())
        assertTrue(reads.all { it.startsWith("voice/liuying/v1/") })
    }

    @Test fun `未知ID及路径穿越在读取资产前拒绝`() = runBlocking {
        val store = store()
        for (id in listOf("../hr_z5_danger", "km_split", "/etc/passwd", "unknown")) {
            assertNull(store.open(id))
        }
        assertTrue(reads.isEmpty())
    }

    @Test fun `缺失清单或音频受控失败`() = runBlocking {
        val store = store()
        assertNull(store.open("hr_z5_danger"))
        installManifest()
        assertNull(store.open("hr_z5_danger"))
    }

    @Test fun `音频哈希不符不返回文件`() = runBlocking {
        installManifest()
        assets["voice/liuying/v1/hr_z5_danger.mp3"] = "损坏".toByteArray()
        assertNull(store().open("hr_z5_danger"))
    }

    @Test fun `损坏JSON或错误schema拒绝`() = runBlocking {
        val store = store()
        for (manifest in listOf("{", "{}", "{\"version\":2,\"entries\":[]}")) {
            assets["voice/liuying/v1/manifest.json"] = manifest.toByteArray()
            assertNull(store.open("hr_z5_danger"))
        }
    }

    @Test fun `清单文本文件名未知ID重复ID缺条目和哈希格式必须校验`() = runBlocking {
        assets["voice/liuying/v1/hr_z5_danger.mp3"] = "abc".toByteArray()
        val valid = manifest()
        for (invalid in listOf(
            valid.replace("心率过高，请立即放慢", "其他文字"),
            valid.replace("hr_z5_danger.mp3", "../hr_z5_danger.mp3"),
            valid.replace("session_finished", "unknown"),
            valid.replace("session_finished", "session_resumed"),
            valid.replace(HASH, "bad"),
            valid.replace("\"duration\":1.5", "\"duration\":0"),
            manifest(entries = BundledVoiceCatalog.entries.dropLast(1)),
        )) {
            assets["voice/liuying/v1/manifest.json"] = invalid.toByteArray()
            assertNull(store().open("hr_z5_danger"))
        }
    }

    private fun store() = AndroidBundledVoiceStore({ path -> read(path) }, temporary.newFolder())
    private fun read(path: String) = assets[path].also { reads += path }
        ?.inputStream() ?: throw FileNotFoundException(path)
    private fun installManifest() { assets["voice/liuying/v1/manifest.json"] = manifest().toByteArray() }
    private fun manifest(entries: List<BundledVoice> = BundledVoiceCatalog.entries) =
        buildJsonObject {
            put("version", 1)
            put("entries", buildJsonArray {
                entries.forEach { entry -> add(buildJsonObject {
                    put("ruleId", entry.ruleId)
                    put("text", entry.text)
                    put("fileName", entry.fileName)
                    put("sha256", HASH)
                    put("duration", 1.5)
                }) }
            })
        }.toString()

    companion object {
        private const val HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
