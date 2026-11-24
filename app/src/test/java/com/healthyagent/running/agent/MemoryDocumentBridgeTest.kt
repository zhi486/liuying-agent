package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.*
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemoryDocumentBridgeTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val entry = MemoryDocumentEntry(MemoryRecord(id, "合成偏好", "user_explicit", 1000, 2000, 2), "agent", id, "preference")
    private fun body(raw: String) = BridgeCodec.json.parseToJsonElement(raw).jsonObject

    @Test fun 文档命令只接受严格只读分页而不接受正文路径或归属() {
        assertEquals(MemoryCommand.Document(20, 20), MemoryCommands.decode("memory.document", body("""{"offset":20,"limit":20}""")))
        listOf("""{"offset":0,"limit":21}""", """{"offset":-1,"limit":20}""",
            """{"offset":"0","limit":20}""", """{"offset":0,"limit":0}""",
            """{"offset":0,"limit":20,"ownerId":"other"}""",
            """{"offset":0,"limit":20,"path":"/target"}""",
            """{"offset":0,"limit":20,"content":"网页正文"}""")
            .forEach { assertThrows(IllegalArgumentException::class.java) { MemoryCommands.decode("memory.document", body(it)) } }
    }

    @Test fun 文档分页不截断后续记录且日志不含正文() {
        val document = MemoryDocument(3000, List(121) { entry.copy(memory = entry.memory.copy(content = "合成偏好 $it")) })
        val pages = (0..120 step 20).map { document.page(it, 20) }
        assertEquals(121, pages.flatMap { it.items }.size)
        assertEquals("合成偏好 120", pages.last().items.single().memory.content)
        assertTrue(pages.all { it.totalItems == 121 && it.generatedAtMillis == 3000L })
        assertEquals(emptyList<MemoryDocumentEntry>(), document.page(Int.MAX_VALUE, 20).items)
        assertFalse(pages.first().toString().contains("合成偏好"))
    }

    @Test fun 文档管理服务从原生当前归属读取并返回真实元数据而非网页正文() = runTest {
        var calls = 0
        val dao = Proxy.newProxyInstance(MemoryDao::class.java.classLoader, arrayOf(MemoryDao::class.java)) { proxy, method, args ->
            when (method.name) {
                "document" -> {
                    calls++
                    assertEquals("local", args!![0])
                    assertEquals(3000L, args[1])
                    MemoryDocument(3000, listOf(entry))
                }
                "toString" -> "合成记忆库"
                "hashCode" -> System.identityHashCode(proxy)
                else -> throw AssertionError("不应执行 ${method.name}")
            }
        } as MemoryDao
        val result = MemoryService(dao, now = { 3000 }).handle("memory.document", body("""{"offset":0,"limit":20}""")) as CommandOutcome.AcceptedWithData
        assertEquals(1, calls)
        assertEquals(1, result.data.getValue("totalItems").jsonPrimitive.int)
        val row = result.data.getValue("items").jsonArray.single().jsonObject
        assertEquals("合成偏好", row.getValue("memory").jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("agent", row.getValue("changeSource").jsonPrimitive.content)
        assertEquals("preference", row.getValue("category").jsonPrimitive.content)
        assertFalse(result.data.containsKey("ownerId"))
        assertEquals(listOf(entry), MemoryService(dao, now = { 3000 }).document().entries)
        assertEquals(2, calls)
    }

    @Test fun 读取异常只返回固定错误且不会当成空记忆() = runTest {
        val dao = Proxy.newProxyInstance(MemoryDao::class.java.classLoader, arrayOf(MemoryDao::class.java)) { _, _, _ -> error("私密异常正文") } as MemoryDao
        val result = MemoryService(dao).handle("memory.document", body("""{"offset":0,"limit":20}""")) as CommandOutcome.Rejected
        assertEquals(BridgeErrorCode.INTERNAL_ERROR, result.code)
        assertFalse(result.message.contains("私密异常正文"))
    }
}
