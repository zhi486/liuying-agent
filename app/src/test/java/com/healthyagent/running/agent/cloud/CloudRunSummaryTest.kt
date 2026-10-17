package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryRef
import com.healthyagent.running.data.local.RunSessionEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudRunSummaryTest {
    private val row = RunSessionEntity("run-1", 1000, 721000, 2000.0, 720, null, 160.0, 360.0, "FINISHED")
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val summary get() = CloudRunSummary.from(row)

    @Test fun 原生请求与后端共用契约样本完全一致() {
        val ids = listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222").iterator()
        val value = CloudTaskIntent.confirm(identity, "请复盘本次跑步", true, 1000) { ids.next() }
            .copy(consentVersion = "selected_run_v1", runSummaryJson = summary.toString())
        val fixture = checkNotNull(javaClass.getResource("/agent/selected-run-request.json")).readText()
        assertEquals(Json.parseToJsonElement(fixture), value.submission())
    }

    @Test fun 只序列化五项概要且缺失心率保留为空() {
        assertEquals(setOf("runId", "distanceMeters", "durationSeconds", "averagePaceSecPerKm", "averageHeartRate"), summary.keys)
        assertEquals(JsonNull, summary["averageHeartRate"])
        assertEquals(JsonPrimitive(720), summary["durationSeconds"])
        assertFalse(summary.containsKey("startTimeMillis"))
        assertFalse(summary.containsKey("avgCadence"))
    }

    @Test fun 未结束零时长非法范围及特殊数值不可上传() {
        listOf(row.copy(state = "RUNNING"), row.copy(state = "unknown"), row.copy(endTimeMillis = null),
            row.copy(endTimeMillis = 999), row.copy(id = "../run"), row.copy(totalDurationSec = 0),
            row.copy(totalDurationSec = 604801), row.copy(totalDistanceMeters = -1.0),
            row.copy(totalDistanceMeters = Double.NaN), row.copy(avgHeartRate = 0.0),
            row.copy(avgHeartRate = 401.0), row.copy(avgPaceSecPerKm = Double.POSITIVE_INFINITY)).forEach {
            assertTrue(runCatching { CloudRunSummary.from(it) }.isFailure)
        }
        assertEquals(JsonNull, CloudRunSummary.from(row.copy(avgPaceSecPerKm = null))["averagePaceSecPerKm"])
    }

    @Test fun Web只能给ID不能冒充原生概要或增大授权() {
        val base = buildJsonObject { put("prompt", "复盘"); put("memoryRefs", JsonArray(emptyList())); put("runId", "run-1") }
        CloudAgentCommands.validate("cloud.prepare", base)
        listOf(JsonObject(base + ("runSummary" to summary)), JsonObject(base + ("summaries" to JsonArray(listOf(summary)))),
            JsonObject(base + ("runId" to JsonNull)), JsonObject(base + ("runId" to JsonPrimitive(" ")))).forEach {
            assertTrue(runCatching { CloudAgentCommands.validate("cloud.prepare", it) }.isFailure)
        }
    }

    @Test fun 旧授权不可悄悄附带概要且新授权固定单条快照() {
        val old = CloudTaskIntent.confirm(identity, "复盘", true, 1000)
        assertTrue(runCatching { old.copy(runSummaryJson = summary.toString()) }.isFailure)
        assertTrue(runCatching { old.copy(consentVersion = "selected_run_v1") }.isFailure)
        val selected = old.copy(consentVersion = "selected_run_v1", runSummaryJson = summary.toString())
        val body = selected.submission()
        assertEquals(JsonPrimitive(true), body.getValue("consent").jsonObject["runSummaries"])
        assertEquals(listOf(summary), body.getValue("summaries").jsonArray.toList())
        assertEquals(body, selected.copy(updatedAtMillis = 2000, submissionAttempted = true).submission())
        assertFalse(selected.toString().contains("run-1"))
    }

    @Test fun 持久概要拒绝任意附加字段字符串数值和数组() {
        val old = CloudTaskIntent.confirm(identity, "复盘", true, 1000)
        listOf(JsonObject(summary + ("route" to JsonArray(emptyList()))),
            JsonObject(summary + ("distanceMeters" to JsonPrimitive("2000"))), JsonArray(listOf(summary)),
            JsonObject(summary - "averageHeartRate")).forEach {
            assertTrue(runCatching { old.copy(consentVersion = "selected_run_v1", runSummaryJson = it.toString()) }.isFailure)
        }
    }

    @Test fun 摘要可组合记忆与知识但不能跳过记忆正文核对() {
        val memoryId = "44444444-4444-4444-8444-444444444444"
        val value = CloudTaskIntent.confirmSelected(identity, "复盘", listOf(MemoryRef(memoryId, 1)), 1000)
            .copy(consentVersion = "selected_run_v1", runSummaryJson = summary.toString(),
                knowledgeJson = """{"version":"curated_knowledge_v1","library":"health","allowSpoilers":false}""")
        assertTrue(runCatching { value.submission() }.isFailure)
        val context = Json.parseToJsonElement("""{"version":"selected_memories_v1","approved":true,"items":[{"id":"$memoryId","version":1,"content":"喜欢晚跑"}]}""").jsonObject
        val body = value.submission(context)
        assertEquals(context, body["memoryContext"])
        assertEquals(value.knowledge(), body["knowledge"])
        assertEquals(summary, body.getValue("summaries").jsonArray.single())
    }
}
