package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.memory.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpTaskContractTest {
    private val f = McpTaskFixtures
    private fun rejects(block: () -> Unit) { try { block() } catch (_: Exception) { return }; fail("应拒绝不匹配的插件任务") }

    @Test fun 共用正式任务夹具在Android还原相同提交和固定确认正文() {
        val data = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/mcp-phone-v1/task.json")).readText()).jsonObject
        val submission = data.getValue("submission").jsonObject
        val intent = f.intent.copy(clientRequestId = submission.getValue("clientRequestId").jsonPrimitive.content,
            localTaskId = submission.getValue("localTaskId").jsonPrimitive.content)
        assertEquals(submission, intent.submission())
        val context = McpTaskContext.capture(intent, f.receipt.copy(clientRequestId = intent.clientRequestId), listOf(f.service() to f.local))
        val invocation = McpTaskInvocation.parse(data.getValue("confirmation"), context)
        assertEquals(data.getValue("decision"), invocation.decision(context, "approve"))
    }

    @Test fun 插件配置随原任务提交且旧任务正文不增加默认字段() {
        val intent = f.intent
        assertEquals(f.configuration, intent.submission()["mcpTools"])
        val memory = CloudMemoryTool.submission(intent, null, f.id(8))
        assertEquals(f.configuration, memory["mcpTools"]); assertNotNull(memory["memoryTools"])
        assertFalse(intent.copy(mcpToolsJson = null).submission().containsKey("mcpTools"))
        assertFalse(intent.submission().toString().contains(f.endpoint))
        assertFalse(intent.toString().contains(intent.prompt))
        rejects { intent.copy(conversationId = null, conversationJson = null) }
    }

    @Test fun 配置严格限制版本数量重复服务和未知字段() {
        val configuration = f.configuration
        val service = configuration.getValue("services").jsonArray.single()
        for (invalid in listOf(JsonObject(configuration + ("secret" to JsonPrimitive("synthetic"))),
            JsonObject(configuration + ("version" to JsonPrimitive("other"))),
            JsonObject(configuration + ("services" to JsonArray(emptyList()))),
            JsonObject(configuration + ("services" to JsonArray(List(9) { service }))),
            JsonObject(configuration + ("services" to JsonArray(listOf(service, service)))),
            JsonObject(configuration + ("services" to JsonArray(listOf(JsonObject(service.jsonObject + ("generation" to JsonPrimitive("../key"))))))),
            JsonObject(configuration + ("services" to JsonArray(listOf(JsonObject(service.jsonObject + ("endpoint" to JsonPrimitive(f.endpoint))))))))) {
            rejects { McpTaskConfiguration.parse(invalid.toString()) }
        }
        rejects { McpTaskConfiguration.parse(configuration.toString() + " ".repeat(4096)) }
    }

    @Test fun 本机绑定要求原回执当前批准版本与本机代次一致() {
        val source = f.service()
        for (changed in listOf(JsonObject(source + ("revision" to JsonPrimitive(f.id(9)))),
            JsonObject(source + ("enabled" to JsonPrimitive(false)) + ("grants" to JsonArray(emptyList())))))
            rejects { McpTaskContext.capture(f.intent, f.receipt, listOf(changed to f.local)) }
        for (local in listOf(f.local.copy(configured = false), f.local.copy(generation = f.id(9)),
            f.local.copy(endpoint = "https://other.example.com/mcp")))
            rejects { McpTaskContext.capture(f.intent, f.receipt, listOf(source to local)) }
        for (receipt in listOf(f.receipt.copy(clientRequestId = f.id(9)), f.receipt.copy(status = "completed"),
            f.receipt.copy(confirmedControl = "cancel")))
            rejects { McpTaskContext.capture(f.intent, receipt, listOf(source to f.local)) }
        rejects { f.context(f.intent.copy(submissionAttempted = false)) }
        rejects { f.context(f.intent.copy(pendingControl = "cancel")) }
        rejects { McpTaskContext.capture(f.intent, f.receipt, emptyList()) }
        assertEquals(f.id(1), f.context().scope(f.id(1)).serviceId)
    }

    @Test fun 本机记忆绑定同时冻结插件字段但兼容旧任务哈希() {
        val original = f.intent.copy(mcpToolsJson = null)
        val grant = MemoryToolGrant(CloudChatConsent.context(original.identity), f.id(9))
        val binding = MemoryToolBinding.capture(original, grant)
        assertTrue(binding.matches(original)); assertFalse(binding.matches(f.intent))
        val withPlugin = MemoryToolBinding.capture(f.intent, grant)
        assertTrue(withPlugin.matches(f.intent)); assertFalse(withPlugin.matches(original))
        val service = f.configuration.getValue("services").jsonArray.single().jsonObject
        for (field in listOf("serviceId", "revision", "generation")) {
            val changed = JsonObject(f.configuration + ("services" to JsonArray(listOf(JsonObject(service + (field to JsonPrimitive(f.id(20))))))))
            assertFalse(withPlugin.matches(f.intent.copy(mcpToolsJson = changed.toString())))
        }
    }

    @Test fun 确认意图严格比对原任务配置目录模式摘要与时限() {
        val context = f.context(); val row = f.invocation(context)
        val parsed = McpTaskWire.confirmation(buildJsonObject { put("confirmation", row) }.toString(), context)!!
        assertEquals("notes.search", parsed.name); assertTrue(parsed.argumentsJson.contains("今晚"))
        assertFalse(parsed.toString().contains("今晚"))
        val patches = listOf("requestId" to JsonPrimitive(f.id(21)), "taskId" to JsonPrimitive(f.id(22)),
            "serviceId" to JsonPrimitive(f.id(23)), "revision" to JsonPrimitive(f.id(24)), "generation" to JsonPrimitive(f.id(25)),
            "endpoint" to JsonPrimitive("https://changed.example.com/mcp"), "name" to JsonPrimitive("notes.delete"),
            "modelName" to JsonPrimitive("forged"), "mode" to JsonPrimitive("read"), "argumentsDigest" to JsonPrimitive("0".repeat(64)),
            "intentDigest" to JsonPrimitive("invalid"), "expiresAtMillis" to JsonPrimitive(31001), "expiresAtMillis" to JsonPrimitive(1000),
            "expiresAtMillis" to JsonPrimitive("31000"), "secret" to JsonPrimitive("synthetic"))
        for ((key, value) in patches) rejects {
            McpTaskWire.confirmation(buildJsonObject { put("confirmation", JsonObject(row + (key to value))) }.toString(), context)
        }
        assertNull(McpTaskWire.confirmation("""{"confirmation":null}""", context))
        val read = f.context(mode = "read")
        rejects { McpTaskWire.confirmation(buildJsonObject { put("confirmation", f.invocation(read)) }.toString(), read) }
    }

    @Test fun 交付同时核对工具真实参数而不是只检查交换信封() {
        val context = f.context()
        val delivery = McpTaskWire.delivery(f.delivery(context), context)!!
        delivery.validate(context)
        val mismatched = f.packet(context, """{"query":"删除所有","pace":6.5}""")
        rejects { McpTaskWire.delivery(f.delivery(context, exchange = mismatched), context) }
        for (key in listOf("taskId", "requestId", "generation", "revision", "serviceId")) {
            val packet = JsonObject(f.packet(context) + (key to JsonPrimitive(f.id(26))))
            rejects { McpTaskWire.delivery(f.delivery(context, exchange = packet), context) }
        }
        assertNull(McpTaskWire.delivery("""{"delivery":null}""", context))
        rejects { McpTaskWire.delivery("""{"delivery":null,"approved":true}""", context) }
        for (method in listOf("initialize", "tools/list", "notifications/initialized", "notifications/cancelled"))
            McpTaskWire.delivery(f.delivery(context, exchange = f.packet(context, method = method)), context)!!.validate(context)
    }

    @Test fun 批准仅接受固定原意图且回执不能用布尔冒充() {
        val context = f.context(); val invocation = McpTaskInvocation.parse(f.invocation(context), context)
        val body = invocation.decision(context, "approve")
        assertEquals(setOf("version", "invocationId", "intentDigest", "generation", "decision"), body.keys)
        assertFalse(body.containsKey("argumentsJson")); assertFalse(body.containsKey("endpoint"))
        rejects { invocation.decision(context, "true") }
        val otherIntent = f.intent.copy(clientRequestId = f.id(27))
        val otherContext = McpTaskContext.capture(otherIntent, f.receipt.copy(clientRequestId = otherIntent.clientRequestId),
            listOf(f.service() to f.local))
        rejects { invocation.decision(otherContext, "approve") }
        val readContext = f.context(mode = "read")
        rejects { McpTaskInvocation.parse(f.invocation(readContext), readContext).decision(readContext, "approve") }
        for (invalid in listOf("""{"accepted":"true"}""", """{"accepted":false}""", """{"accepted":true,"secret":"x"}"""))
            rejects { McpTaskWire.accepted(invalid) }
    }
}
