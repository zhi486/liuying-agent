package com.healthyagent.running.build

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAssetPackagingTest {

    @Test
    fun `generated web assets contain index and entry chunks`() {
        val assetsDirectory = System.getProperty("web.assets.dir")
        assertNotNull("Gradle 必须提供 web.assets.dir", assetsDirectory)

        val webDirectory = File(requireNotNull(assetsDirectory), "web")
        val indexFile = File(webDirectory, "index.html")
        assertTrue("缺少生成的 web/index.html", indexFile.isFile)

        val entrySources = Regex("""<script\b[^>]*\bsrc=["']([^"']+)["']""")
            .findAll(indexFile.readText())
            .map { it.groupValues[1] }
            .toList()
        assertFalse("index.html 必须引用入口脚本", entrySources.isEmpty())

        for (source in entrySources) {
            assertFalse("入口脚本不得为远程 URL：$source", source.startsWith("http"))
            val relativePath = source.removePrefix("./").removePrefix("/")
            assertTrue("缺少入口脚本：$relativePath", File(webDirectory, relativePath).isFile)
        }
    }
}
