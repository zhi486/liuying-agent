package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.MemoryCommand
import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryRef
import com.healthyagent.running.bridge.BridgeCodec
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MemoryCommandsTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun decode(name: String, body: String) = MemoryCommands.decode(name, BridgeCodec.json.parseToJsonElement(body).jsonObject)

    @Test fun 显式创建和编辑采用严格字段及版本() {
        assertEquals(MemoryCommand.Create(id, "喜欢晚跑"), decode("memory.create", """{"memoryId":"$id","content":" 喜欢晚跑 "}"""))
        assertEquals(MemoryCommand.Update(id, 2, "鼓励简短"), decode("memory.update", """{"memoryId":"$id","version":2,"content":"鼓励简短"}"""))
        assertEquals(MemoryCommand.Delete(id, 2), decode("memory.delete", """{"memoryId":"$id","version":2,"confirmed":true}"""))
        assertEquals(MemoryCommand.ListMemories(0, 20), decode("memory.list", """{"offset":0,"limit":20}"""))
    }

    @Test fun 历史与恢复必须使用严格版本和显式确认() {
        assertEquals(MemoryCommand.History(id, 0, 10), decode("memory.history", """{"memoryId":"$id","offset":0,"limit":10}"""))
        assertEquals(MemoryCommand.Restore(id, 3, 1), decode("memory.restore", """{"memoryId":"$id","version":3,"targetVersion":1,"confirmed":true}"""))
        listOf(
            "memory.history" to """{"memoryId":"$id","offset":-1,"limit":10}""",
            "memory.history" to """{"memoryId":"$id","offset":0,"limit":51}""",
            "memory.history" to """{"memoryId":"$id","offset":0,"limit":10,"ownerId":"other"}""",
            "memory.restore" to """{"memoryId":"$id","version":3,"targetVersion":3,"confirmed":true}""",
            "memory.restore" to """{"memoryId":"$id","version":3,"targetVersion":0,"confirmed":true}""",
            "memory.restore" to """{"memoryId":"$id","version":"3","targetVersion":1,"confirmed":true}""",
            "memory.restore" to """{"memoryId":"$id","version":3,"targetVersion":1,"confirmed":false}""",
            "memory.restore" to """{"memoryId":"$id","version":3,"targetVersion":1,"confirmed":"true"}""",
            "memory.restore" to """{"memoryId":"$id","version":2147483647,"targetVersion":1,"confirmed":true}""",
            "memory.restore" to """{"memoryId":"$id","version":3,"targetVersion":1,"confirmed":true,"content":"伪造正文"}""",
        ).forEach { (name, body) -> assertThrows(IllegalArgumentException::class.java) { decode(name, body) } }
    }

    @Test fun 拒绝空白超长错误类型和越权字段() {
        listOf(
            "memory.create" to """{"memoryId":"$id","content":" "}""",
            "memory.create" to """{"memoryId":"$id","content":"${"字".repeat(501)}"}""",
            "memory.create" to """{"memoryId":"bad","content":"偏好"}""",
            "memory.create" to """{"memoryId":"$id","content":"偏好","ownerId":"other"}""",
            "memory.create" to """{"memoryId":"$id","content":"偏好","cloudProcessing":true}""",
            "memory.update" to """{"memoryId":"$id","version":"1","content":"偏好"}""",
            "memory.delete" to """{"memoryId":"$id","version":0}""",
            "memory.delete" to """{"memoryId":"$id","version":1,"confirmed":false}""",
            "memory.list" to """{"offset":-1,"limit":20}""",
            "memory.list" to """{"offset":0,"limit":51}""",
        ).forEach { (name, body) -> assertThrows(IllegalArgumentException::class.java) { decode(name, body) } }
    }

    @Test fun 中文与表情可保存而孤立代理字符拒绝() {
        assertEquals("喜欢轻松跑😊", MemoryCommands.content("喜欢轻松跑😊"))
        assertEquals(500, MemoryCommands.content("字".repeat(500)).length)
        assertThrows(IllegalArgumentException::class.java) { MemoryCommands.content("偏好\uD800") }
        assertThrows(IllegalArgumentException::class.java) { MemoryCommands.content("\uDC00偏好") }
    }

    @Test fun 空白规范与网页一致而不采用Java额外空白() {
        assertEquals("\u001c夜跑\u001c", MemoryCommands.content("\u001c夜跑\u001c"))
        assertEquals("夜跑", MemoryCommands.content("\ufeff\u00a0夜跑\u3000"))
        assertThrows(IllegalArgumentException::class.java) { MemoryCommands.content("\ufeff\u2007\u2028") }
    }

    @Test fun 预览只允许唯一记忆标识和版本而非网页正文或上传授权() {
        assertEquals(MemoryCommand.Preview(listOf(MemoryRef(id, 2))), decode("memory.preview", """{"memoryRefs":[{"id":"$id","version":2}]}"""))
        val ref = """{"id":"$id","version":2}"""
        listOf(
            """{"memoryRefs":[]}""",
            """{"memoryRefs":[$ref,$ref]}""",
            """{"memoryRefs":[{"id":"$id","version":"2"}]}""",
            """{"memoryRefs":[{"id":"$id","version":0}]}""",
            """{"memoryRefs":[{"id":"$id","version":2,"content":"伪造正文"}]}""",
            """{"memoryRefs":[$ref],"approved":true}""",
            """{"memoryRefs":[$ref],"ownerId":"other"}""",
            """{"memoryRefs":[${List(6) { """{"id":"11111111-1111-4111-8111-11111111111$it","version":1}""" }.joinToString(",") }]}""",
        ).forEach { assertThrows(IllegalArgumentException::class.java) { decode("memory.preview", it) } }
    }
}
