package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryRef
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudKnowledgeTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val knowledge = """{"version":"curated_knowledge_v1","library":"firefly","allowSpoilers":false}"""
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun 双库选择可绑定快照且不改变剧透许可() {
        for (allowSpoilers in listOf(false, true)) {
            val both = knowledge.replace("firefly", "both").replace("false", allowSpoilers.toString())
            CloudAgentCommands.validate("cloud.prepare", json("""{"prompt":"问题","memoryRefs":[],"knowledge":$both}"""))
            val intent = CloudTaskIntent.confirm(identity, "问题", true, 1000)
                .copy(consentVersion = "selected_knowledge_v1", knowledgeJson = both)
            assertEquals(json(both), intent.submission()["knowledge"])
        }
    }

    @Test fun 选择独立授权并绑定正文且旧任务不扩权() {
        val old = CloudTaskIntent.confirm(identity, "流萤是谁", true, 1000)
        assertFalse(old.submission().containsKey("knowledge"))
        assertTrue(runCatching { old.copy(knowledgeJson = knowledge) }.isFailure)
        val selected = old.copy(consentVersion = "selected_knowledge_v1", knowledgeJson = knowledge)
        assertEquals(json(knowledge), selected.submission()["knowledge"])
        assertEquals("prompt_only_v1", old.consentVersion)
        val withMemory = CloudTaskIntent.confirmSelected(identity, "问题", listOf(MemoryRef("11111111-1111-4111-8111-111111111111", 1)), 1000)
            .copy(consentVersion = "selected_knowledge_v1", knowledgeJson = knowledge)
        assertTrue(runCatching { withMemory.submission() }.isFailure)
        val context = json("""{"version":"selected_memories_v1","approved":true,"items":[{"id":"11111111-1111-4111-8111-111111111111","version":1,"content":"偏好夜跑"}]}""")
        assertEquals(json(knowledge), withMemory.submission(context)["knowledge"])
    }

    @Test fun 拒绝非法选择和健康库剧透以及正文偷渡() {
        CloudAgentCommands.validate("cloud.prepare", json("""{"prompt":"问题","memoryRefs":[],"knowledge":$knowledge}"""))
        for (invalid in listOf("null", "{}", knowledge.replace("false", "\"false\""),
            knowledge.replace("firefly", "all"), knowledge.replace("curated_knowledge_v1", "v2"),
            knowledge.replace("firefly", "health").replace("false", "true"),
            knowledge.dropLast(1) + ",\"url\":\"https://evil.test\"}")) {
            assertTrue(runCatching { CloudAgentCommands.validate("cloud.prepare", json("""{"prompt":"问题","memoryRefs":[],"knowledge":$invalid}""")) }.isFailure)
        }
    }

    @Test fun 结构化来源随回答校验拒绝伪官方和重复() {
        val source = """{"citationId":"official_v1_1","title":"官方资料","url":"https://hsr.hoyoverse.com/zh-tw/character","publishedAt":null,"checkedAtMillis":1000}"""
        fun view(sources: String) = CloudTaskResponse("33333333-3333-4333-8333-333333333333", "completed", null,
            1000, 31000, 2000, "available", json("""{"status":"completed","answer":"回答","actions":[],"sources":$sources}"""))
        view("[$source]").validate()
        for (invalid in listOf("[$source,$source]", "[${source.replace("hsr.hoyoverse.com", "hsr.hoyoverse.com.evil.test")}]",
            "[${source.replace("1000", "-1")}]", "[${source.replace("null", "\"2026-02-30\"")}]") ) {
            assertTrue(runCatching { view(invalid).validate() }.isFailure)
        }
    }
}
