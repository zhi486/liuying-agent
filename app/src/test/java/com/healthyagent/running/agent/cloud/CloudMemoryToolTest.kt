package com.healthyagent.running.agent.cloud

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudMemoryToolTest {
    private val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/memory-tools-v1/transport.json")).readText()).jsonObject
    private val envelope = fixture.getValue("request").jsonObject
    private val request = envelope.getValue("memory").jsonObject
    private val taskId = request.getValue("taskId").jsonPrimitive.content
    private val generation = request.getValue("generation").jsonPrimitive.content
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")

    @Test fun 共享协议完整解析与回传且日志隐藏正文() {
        val tool = CloudMemoryTool.parse(envelope.toString(), taskId)!!
        assertEquals(fixture.getValue("reply"), tool.reply(fixture.getValue("reply").jsonObject.getValue("observation").jsonObject))
        assertEquals(request.getValue("requestId").jsonPrimitive.content, tool.requestId)
        assertEquals("memory.upsert", tool.name)
        assertFalse(tool.toString().contains("夜跑"))
        assertNull(CloudMemoryTool.parse("""{"memory":null}""", taskId))
    }

    @Test fun 任务路由代际工具参数都严格校验不接受任意授权字段() {
        val invalid = listOf(
            request + ("taskId" to JsonPrimitive("44444444-4444-4444-8444-444444444444")),
            request + ("generation" to JsonPrimitive("bad")), request + ("approved" to JsonPrimitive(true)),
            request + ("call" to JsonObject(request.getValue("call").jsonObject + ("ownerId" to JsonPrimitive("other")))),
            request + ("call" to JsonObject(request.getValue("call").jsonObject + ("name" to JsonPrimitive("runs.list")))),
            request + ("call" to JsonObject(request.getValue("call").jsonObject + ("id" to JsonPrimitive("../call")))),
        )
        invalid.forEach { row ->
            assertEquals("invalid_response", assertThrows(CloudTaskFailure::class.java) {
                CloudMemoryTool.parse(buildJsonObject { put("memory", JsonObject(row)) }.toString(), taskId)
            }.code)
        }
        listOf("{}", """{"memory":null,"extra":true}""", "[]").forEach { value ->
            assertThrows(CloudTaskFailure::class.java) { CloudMemoryTool.parse(value, taskId) }
        }
    }

    @Test fun 只有原生授权绑定的会话提交才携带代际且不上传本机身份() {
        val base = CloudTaskIntent.confirm(identity, "我喜欢夜跑", true, 1000)
        val id = "44444444-4444-4444-8444-444444444444"
        val intent = base.copy(conversationId = id,
            conversationJson = """{"version":"conversation_v1","id":"$id","approved":true,"messages":[]}""")
        val wire = CloudMemoryTool.submission(intent, null, generation)
        assertEquals(intent.submission(), JsonObject(wire - "memoryTools"))
        assertEquals(setOf("version", "generation"), wire.getValue("memoryTools").jsonObject.keys)
        val facts = CloudMemoryTool.submission(intent, null, generation, true)
        assertEquals(buildJsonObject { put("version", "native_memory_v2"); put("generation", generation); put("factsAllowed", true) },
            facts.getValue("memoryTools"))
        assertEquals(intent.submission(), JsonObject(facts - "memoryTools"))
        listOf("scope", "ownerId", "deviceId", "credentialRef", "sourceHash").forEach { assertFalse(wire.containsKey(it)) }
        assertThrows(IllegalArgumentException::class.java) { CloudMemoryTool.submission(base, null, generation) }
        assertThrows(IllegalArgumentException::class.java) { CloudMemoryTool.submission(intent, null, "bad") }
    }
}
