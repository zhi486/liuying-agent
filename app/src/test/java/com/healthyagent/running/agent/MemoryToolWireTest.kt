package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemoryToolWireTest {
    private val item = MemoryRecord("11111111-1111-4111-8111-111111111111", "我喜欢夜跑", "user_explicit", 1000, 2000, 1)
    private val fixtures = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/memory-tools-v1/observations.json")).readText()).jsonObject

    @Test fun 原生回执编码与服务端共享样例一致且不带本机元数据() {
        val results = mapOf(
            "found" to MemoryToolResult.Found(listOf(item)), "empty" to MemoryToolResult.Found(emptyList()),
            "saved" to MemoryToolResult.Saved(item, false), "replayed" to MemoryToolResult.Saved(item, true),
            "updated" to MemoryToolResult.Saved(item.copy(version = 2), false),
            "sensitive" to MemoryToolResult.Rejected("sensitive_or_instruction"),
            "revoked" to MemoryToolResult.Rejected("consent_required"),
            "storage" to MemoryToolResult.Rejected("storage_unavailable"),
            "invalid" to MemoryToolResult.Rejected("异常正文不上传"),
        )
        assertEquals(fixtures.keys, results.keys)
        results.forEach { (name, result) ->
            val encoded = MemoryToolWire.encode(result)
            assertEquals(name, fixtures.getValue(name).jsonObject.getValue("observation"), encoded)
            assertFalse(encoded.toString().contains("source"))
            assertFalse(encoded.toString().contains("AtMillis"))
        }
    }

    @Test fun 不合规记录不泄漏为伪成功且搜索不悄悄截断() {
        val invalid = fixtures.getValue("invalid").jsonObject.getValue("observation")
        listOf(item.copy(id = "not-an-id"), item.copy(content = " "), item.copy(content = "我\ud800"),
            item.copy(content = "x".repeat(501)), item.copy(content = "<｜image｜>"), item.copy(version = 0)).forEach {
            assertEquals(invalid, MemoryToolWire.encode(MemoryToolResult.Saved(it, false)))
        }
        assertEquals(invalid, MemoryToolWire.encode(MemoryToolResult.Found(List(6) { item })))
        assertEquals(invalid, MemoryToolWire.encode(MemoryToolResult.Found(listOf(item, item))))
    }

    @Test fun 明确拒绝只保留固定原因而存储错误保持未确认() {
        listOf("scope_denied", "task_inactive", "invalid_tool", "evidence_required", "conflict").forEach {
            assertEquals(buildJsonObject { put("status", "rejected"); put("reason", it) },
                MemoryToolWire.encode(MemoryToolResult.Rejected(it)))
        }
    }
}
