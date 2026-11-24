package com.healthyagent.running.agent

import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.memory.MemoryToolConsent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemoryToolConsentTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private var stored: String? = null
    private var writes = 0
    private fun consent() = MemoryToolConsent({ stored }, { stored = it; writes++ })

    @Test fun 旧授权默认不含事实且范围变化使旧代际和页面失效() {
        val consent = consent()
        consent.set(identity, consent.context(identity), true)
        assertEquals(JsonPrimitive("auto_memory_v1"), Json.parseToJsonElement(stored!!).jsonObject["version"])
        val previous = consent.current(identity)!!
        assertFalse(previous.factsAllowed)
        val oldContext = consent.context(identity)
        consent.set(identity, oldContext, true, factsAllowed = true)
        val current = consent.current(identity)!!
        assertTrue(current.factsAllowed)
        assertNotEquals(previous.generation, current.generation)
        assertEquals(current, consent().current(identity))
        assertThrows(IllegalArgumentException::class.java) { consent.set(identity, oldContext, true, factsAllowed = true) }
        // 旧设置接口没有事实参数，不能续授新范围。
        consent.set(identity, consent.context(identity), true)
        assertFalse(consent.current(identity)!!.factsAllowed)
        assertNotEquals(current.generation, consent.current(identity)!!.generation)
        consent.set(identity, consent.context(identity), false)
        assertNull(consent.current(identity))
    }

    @Test fun 事实授权格式必须严格且关闭状态不得保留事实权限() {
        val consent = consent()
        consent.set(identity, consent.context(identity), true, factsAllowed = true)
        val valid = Json.parseToJsonElement(stored!!).jsonObject
        for (invalid in listOf(JsonObject(valid - "factsAllowed"),
            JsonObject(valid + ("factsAllowed" to JsonPrimitive("true"))),
            JsonObject(valid + ("enabled" to JsonPrimitive(false))),
            JsonObject(valid + ("version" to JsonPrimitive("auto_memory_v1"))))) {
            stored = invalid.toString()
            assertNull(consent.current(identity))
        }
        assertThrows(IllegalArgumentException::class.java) { consent.set(identity, consent.context(identity), false, factsAllowed = true) }
        val noWrite = MemoryToolConsent({ null }, {})
        assertThrows(IllegalStateException::class.java) { noWrite.set(identity, noWrite.context(identity), true, factsAllowed = true) }
    }

    @Test fun 默认关闭且读错损坏和错误类型均不授权() {
        val consent = consent()
        assertNull(consent.current(identity))
        assertNull(MemoryToolConsent({ error("合成读取失败") }, {}).current(identity))
        consent.set(identity, consent.context(identity), true)
        val valid = Json.parseToJsonElement(stored!!).jsonObject
        for (invalid in listOf("{", "{}", JsonObject(valid + ("enabled" to JsonPrimitive("true"))).toString(),
            JsonObject(valid + ("extra" to JsonPrimitive(true))).toString(),
            JsonObject(valid + ("version" to JsonPrimitive("future"))).toString())) {
            stored = invalid
            assertNull(consent.current(identity))
        }
    }

    @Test fun 关闭再开启更换代际并拒绝迟到设置页() {
        val consent = consent()
        val initial = consent.context(identity)
        consent.set(identity, initial, true)
        val first = consent.current(identity)!!
        assertEquals(first, consent().current(identity))
        val enabled = consent.context(identity)
        consent.set(identity, enabled, true)
        assertEquals(1, writes)
        consent.set(identity, enabled, false)
        assertNull(consent.current(identity))
        assertThrows(IllegalArgumentException::class.java) { consent.set(identity, enabled, true) }
        assertThrows(IllegalArgumentException::class.java) { consent.set(identity, initial, true) }
        consent.set(identity, consent.context(identity), true)
        assertNotEquals(first, consent.current(identity))
        assertEquals(3, writes)
    }

    @Test fun 身份各字段变化不继承授权或旧设置令牌() {
        val consent = consent()
        consent.set(identity, consent.context(identity), true)
        val oldToken = consent.context(identity)
        for (changed in listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"),
            identity.copy(deviceId = "other"), identity.copy(credentialRef = "other"))) {
            assertNull(consent.current(changed))
            assertNotEquals(oldToken, consent.context(changed))
            assertThrows(IllegalArgumentException::class.java) { consent.set(changed, oldToken, true) }
        }
        val changed = identity.copy(credentialRef = "new")
        consent.set(changed, consent.context(changed), true)
        assertNull(consent.current(identity))
        assertNotNull(consent.current(changed))
    }

    @Test fun 写入失败或未持久化不得报告成功() {
        val noWrite = MemoryToolConsent({ null }, {})
        assertThrows(IllegalStateException::class.java) { noWrite.set(identity, noWrite.context(identity), true) }
        val failed = MemoryToolConsent({ null }, { error("合成写入失败") })
        assertThrows(IllegalStateException::class.java) { failed.set(identity, failed.context(identity), true) }
        assertNull(noWrite.current(identity))
        assertNull(failed.current(identity))
    }
}
