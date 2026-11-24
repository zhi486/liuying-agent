package com.healthyagent.running.agent.mcp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpTaskSelectionTest {
    private val f = McpTaskFixtures
    private fun capture(service: JsonObject = f.service(), local: McpPrivateStatus = f.local) =
        McpTaskSelection.capture(listOf(f.id(1)), listOf(service to local))

    @Test fun 只从原生目录和私密元数据生成配置且预览只含本次许可() {
        val snapshot = capture()
        assertEquals(f.configuration, snapshot.configuration.wire())
        val preview = snapshot.preview().single().jsonObject
        assertEquals(setOf("id", "endpoint", "tools"), preview.keys)
        assertEquals(setOf("name", "mode"), preview.getValue("tools").jsonArray.single().jsonObject.keys)
        assertTrue(snapshot.matches(capture()))
        assertFalse(snapshot.toString().contains(f.endpoint))
    }

    @Test fun 停用或未配置或端点不一致不能成为本次插件() {
        val disabled = JsonObject(f.service() + mapOf("enabled" to JsonPrimitive(false), "grants" to JsonArray(emptyList())))
        for ((service, local) in listOf(disabled to f.local, f.service() to f.local.copy(configured = false),
            f.service() to f.local.copy(endpoint = "https://changed.example.com/mcp"))) {
            assertThrows(IllegalArgumentException::class.java) { capture(service, local) }
        }
    }

    @Test fun 换代换版本换许可都使原预览失效() {
        val snapshot = capture()
        assertFalse(snapshot.matches(capture(local = f.local.copy(generation = f.id(9)))))
        assertFalse(snapshot.matches(capture(JsonObject(f.service() + ("revision" to JsonPrimitive(f.id(9)))))))
        assertFalse(snapshot.matches(capture(f.service("read"))))
    }

    @Test fun 空选择重复服务及顺序错位被拒绝() {
        assertThrows(IllegalArgumentException::class.java) { McpTaskSelection.capture(emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { McpTaskSelection.capture(listOf(f.id(1), f.id(1)), listOf(f.service() to f.local, f.service() to f.local)) }
        assertThrows(IllegalArgumentException::class.java) { McpTaskSelection.capture(listOf(f.id(9)), listOf(f.service() to f.local)) }
    }
}
