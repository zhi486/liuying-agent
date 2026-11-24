package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.MemoryContextState
import com.healthyagent.running.agent.memory.MemoryRef
import org.junit.Assert.*
import org.junit.Test

class MemoryContextStateTest {
    private val requestId = "11111111-1111-4111-8111-111111111111"
    private fun ref(value: Int, version: Int = 1) = MemoryRef("22222222-2222-4222-8222-${value.toString().padStart(12, '0')}", version)

    @Test fun 合并去重保留不同版本往返只含标识与版本() {
        val first = MemoryContextState().merge(listOf(ref(1), ref(1), ref(2)))
        val next = first.merge(listOf(ref(1, 2), ref(3)))
        assertEquals(setOf(ref(1), ref(1, 2), ref(2), ref(3)), next.refs)
        val row = next.row(requestId)
        assertEquals(next, row.state())
        assertEquals(row, MemoryContextState(next.refs.reversed().toSet()).row(requestId))
        assertFalse(row.referencesJson.contains("content"))
        assertFalse(row.toString().contains(requestId))
        assertEquals(first, MemoryContextState(first.refs).row(requestId).state())
    }

    @Test fun 超过上限整轮失效且后续合并不能重新变成有效() {
        val full = MemoryContextState().merge((1..256).map { ref(it, Int.MAX_VALUE) })
        assertEquals(full, full.row(requestId).state())
        assertFalse(full.exhausted)
        val overflow = full.merge(listOf(ref(257)))
        assertTrue(overflow.exhausted)
        assertTrue(overflow.refs.isEmpty())
        assertEquals(overflow, overflow.row(requestId).state())
        assertEquals(overflow, overflow.merge(listOf(ref(1))))
    }

    @Test fun 坏格式重复引用错误类型和非法标识拒绝而不降级空引用() {
        val valid = MemoryContextState(setOf(ref(1))).row(requestId).referencesJson
        listOf("{}", "null", "[null]", valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":0"), valid.replace(ref(1).id, "other"),
            valid.replace("\"version\":1", "\"version\":1,\"content\":\"伪造正文\""),
            "[${valid.drop(1).dropLast(1)},${valid.drop(1).dropLast(1)}]", " ".repeat(20001))
            .forEach { assertThrows(IllegalArgumentException::class.java) { MemoryContextState.decode(it, false) } }
        assertThrows(IllegalArgumentException::class.java) { MemoryContextState.decode(valid, true) }
        assertThrows(IllegalArgumentException::class.java) { MemoryContextState(setOf(ref(1, 0))) }
        assertThrows(IllegalArgumentException::class.java) { MemoryContextState().row("other") }
    }

    @Test fun 已知空基线允许本轮连续更正且重放不带回旧版本() {
        val initial = MemoryContextState().row(requestId, freezeBaseline = true)
        assertEquals("[]", initial.protectedReferencesJson)
        val searched = initial.mergeToolReferences(listOf(ref(1)))
        val second = searched.mergeToolReferences(listOf(ref(1, 2)), ref(1))
        assertEquals(setOf(ref(1, 2)), second.state().refs)
        assertEquals(second, second.mergeToolReferences(listOf(ref(1, 2)), ref(1)))
        val third = second.mergeToolReferences(listOf(ref(1, 3)), ref(1, 2))
        assertEquals(setOf(ref(1, 3)), third.state().refs)
        assertEquals(third, third.mergeToolReferences(listOf(ref(1, 3)), ref(1, 2)))
        assertEquals(initial.protectedReferencesJson, third.protectedReferencesJson)
    }

    @Test fun 保护基线和其他标识版本不被本轮定向更正整体替换() {
        val initial = MemoryContextState(setOf(ref(1), ref(2))).row(requestId, freezeBaseline = true)
        val searched = initial.mergeToolReferences(listOf(ref(1, 2), ref(3), ref(3, 4)))
        val protectedUpdate = searched.mergeToolReferences(listOf(ref(1, 2)), ref(1))
        assertEquals(searched.state().refs, protectedUpdate.state().refs)
        val next = protectedUpdate.mergeToolReferences(listOf(ref(1, 3)), ref(1, 2))
            .mergeToolReferences(listOf(ref(3, 2)), ref(3))
        assertEquals(setOf(ref(1), ref(1, 3), ref(2), ref(3, 2), ref(3, 4)), next.state().refs)
        assertEquals(initial.protectedReferencesJson, next.protectedReferencesJson)
    }

    @Test fun 旧行未知基线即使精确更正也只保守并集() {
        val legacy = MemoryContextState(setOf(ref(1))).row(requestId)
        assertNull(legacy.protectedReferencesJson)
        val next = legacy.mergeToolReferences(listOf(ref(1, 2)), ref(1))
        assertEquals(setOf(ref(1), ref(1, 2)), next.state().refs)
        assertNull(next.protectedReferencesJson)
        assertEquals(next, next.mergeToolReferences(listOf(ref(1, 2)), ref(1)))
    }

    @Test fun 标识不符版本跳跃多条结果及空结果都不能解除原引用() {
        val initial = MemoryContextState().row(requestId, freezeBaseline = true)
            .mergeToolReferences(listOf(ref(1)))
        listOf(listOf(ref(2, 2)), listOf(ref(1, 3)), listOf(ref(1, 2), ref(2)),
            listOf(ref(1, 2), ref(1, 2)), emptyList()).forEach { more ->
            assertEquals(initial.state().refs + more, initial.mergeToolReferences(more, ref(1)).state().refs)
        }
        val maximum = MemoryContextState().row(requestId, freezeBaseline = true)
            .mergeToolReferences(listOf(ref(1, Int.MAX_VALUE)))
        assertEquals(setOf(ref(1, Int.MAX_VALUE), ref(1)),
            maximum.mergeToolReferences(listOf(ref(1)), ref(1, Int.MAX_VALUE)).state().refs)
    }

    @Test fun 满额精确替换不误超限但已超限状态不能复活() {
        val full = MemoryContextState().row(requestId, freezeBaseline = true)
            .mergeToolReferences((1..256).map { ref(it) })
        val replaced = full.mergeToolReferences(listOf(ref(1, 2)), ref(1))
        assertFalse(replaced.state().exhausted)
        assertEquals((2..256).map { ref(it) }.toSet() + ref(1, 2), replaced.state().refs)
        val exhausted = full.mergeToolReferences(listOf(ref(257)))
        assertTrue(exhausted.state().exhausted)
        assertTrue(exhausted.state().refs.isEmpty())
        assertEquals(exhausted, exhausted.mergeToolReferences(listOf(ref(1, 2)), ref(1)))
        assertEquals(full.protectedReferencesJson, exhausted.protectedReferencesJson)
        assertThrows(IllegalArgumentException::class.java) { exhausted.state().row(requestId, freezeBaseline = true) }
    }

    @Test fun 损坏保护基线不能降级为已知空基线() {
        val initial = MemoryContextState(setOf(ref(1))).row(requestId, freezeBaseline = true)
        val valid = initial.protectedReferencesJson!!
        listOf("{}", "null", "[null]", valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":0"), valid.replace(ref(1).id, "other"),
            valid.replace("\"version\":1", "\"version\":1,\"content\":\"伪造正文\""),
            "[${valid.drop(1).dropLast(1)},${valid.drop(1).dropLast(1)}]", " ".repeat(20001)).forEach { broken ->
            val row = initial.copy(protectedReferencesJson = broken)
            assertThrows(IllegalArgumentException::class.java) { row.state() }
            assertThrows(IllegalArgumentException::class.java) { row.mergeToolReferences(listOf(ref(1, 2)), ref(1)) }
        }
    }

    @Test fun 非子集保护基线拒绝读取和定向更正() {
        val initial = MemoryContextState(setOf(ref(1))).row(requestId, freezeBaseline = true)
        val unrelated = MemoryContextState(setOf(ref(2))).row(requestId).referencesJson
        val row = initial.copy(protectedReferencesJson = unrelated)
        assertThrows(IllegalArgumentException::class.java) { row.state() }
        assertThrows(IllegalArgumentException::class.java) { row.mergeToolReferences(listOf(ref(1, 2)), ref(1)) }
    }

    @Test fun 后续搜索合并不重冻基线也不抹掉未知来源() {
        val initial = MemoryContextState(setOf(ref(2))).row(requestId, freezeBaseline = true)
        val searched = initial.mergeToolReferences(listOf(ref(1)))
            .mergeToolReferences(listOf(ref(3), ref(1)))
        assertEquals(initial.protectedReferencesJson, searched.protectedReferencesJson)
        val corrected = searched.mergeToolReferences(listOf(ref(1, 2)), ref(1))
        assertEquals(setOf(ref(1, 2), ref(2), ref(3)), corrected.state().refs)
        assertEquals(initial.protectedReferencesJson, corrected.protectedReferencesJson)
        assertEquals(corrected, corrected.mergeToolReferences(emptyList()))
        val legacy = MemoryContextState(setOf(ref(2))).row(requestId)
            .mergeToolReferences(listOf(ref(1))).mergeToolReferences(listOf(ref(3)))
        assertNull(legacy.protectedReferencesJson)
    }
}
