package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.MemoryDocument
import com.healthyagent.running.agent.memory.MemoryDocumentEntry
import com.healthyagent.running.agent.memory.MemoryRecord
import java.io.IOException
import java.io.Writer
import org.junit.Assert.*
import org.junit.Test

class MemoryDocumentTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val turn = "22222222-2222-4222-8222-222222222222"
    private fun entry(content: String = "我喜欢周三测试", source: String? = "agent", category: String? = "preference") =
        MemoryDocumentEntry(MemoryRecord(id, content, "user_explicit", 1000, 2000, 2),
            source, if (source == "agent") turn else null, category)
    private fun markdown(document: MemoryDocument) = StringBuilder().also { document.writeMarkdown(it) }.toString()

    @Test fun 事实原句及分类在只读文档中保留() {
        val content = "我的主要编程语言是Kotlin"
        val result = markdown(MemoryDocument(3000, listOf(entry(content, category = "fact"))))
        assertTrue(result.contains("分类：事实"))
        assertTrue(result.contains(content))
        assertTrue(result.contains("来源消息：`$turn`"))
    }

    @Test fun 文档保留正文当前版本来源分类时间并注明只读副本() {
        val result = markdown(MemoryDocument(3000, listOf(entry())))
        listOf("共 1 条当前有效记忆", "不包含历史版本或已删除内容", "不会随应用内编辑或删除自动更新",
            "不会自动导入应用", "1970-01-01T00:00:03Z", "记忆标识：`$id`", "分类：偏好",
            "当前版本：2", "生成时有效", "Agent 经授权写入", "来源消息：`$turn`",
            "创建时间：1970-01-01T00:00:01Z", "更新时间：1970-01-01T00:00:02Z", "我喜欢周三测试")
            .forEach { assertTrue("缺少预期字段：$it", result.contains(it)) }
    }

    @Test fun 不推测手动或旧版本来源与分类且目标有明确标签() {
        val result = markdown(MemoryDocument(3000, listOf(entry(source = "manual", category = null),
            entry(source = "restored", category = null), entry(source = null, category = null), entry(category = "goal"))))
        assertTrue(result.contains("手动保存或编辑"))
        assertTrue(result.contains("用户恢复历史版本"))
        assertTrue(result.contains("旧记录（来源未记录）"))
        assertTrue(result.contains("来源消息：未记录或不适用"))
        assertEquals(3, Regex("分类：未分类").findAll(result).count())
        assertTrue(result.contains("分类：目标"))
    }

    @Test fun 空文档明确无记录且不会生成虚构条目() {
        val result = markdown(MemoryDocument(0, emptyList()))
        assertTrue(result.contains("共 0 条"))
        assertTrue(result.endsWith("暂无有效记忆。\n"))
        assertFalse(result.contains("## 1"))
    }

    @Test fun 多行正文和嵌套围栏作为原文保留而不成为文档结构() {
        val content = "首行\n```\n## 伪造标题\n<script>原文</script>\n``````text\n末行🙂"
        val result = markdown(MemoryDocument(3000, listOf(entry(content))))
        assertTrue(result.contains("```````text\n$content\n```````\n"))
        assertEquals(content, result.substringAfter("```````text\n").substringBefore("\n```````\n"))
    }

    @Test fun 文档完整覆盖超过管理页上限的全部条目且不泄露正文到日志() {
        val entries = List(121) { entry("合成偏好 $it") }
        val document = MemoryDocument(3000, entries)
        val result = markdown(document)
        assertTrue(result.contains("共 121 条"))
        assertTrue(result.contains("## 121\n"))
        assertTrue(result.contains("合成偏好 120"))
        assertFalse(document.toString().contains("合成偏好"))
        assertFalse(entries.first().toString().contains("合成偏好"))
    }

    @Test fun 目标写入失败向上传递而不被当作导出成功() {
        val target = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) { throw IOException("合成目标不可写") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { MemoryDocument(3000, listOf(entry())).writeMarkdown(target) }
    }
}
