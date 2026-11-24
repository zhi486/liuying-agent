package com.healthyagent.running.agent.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudChatConsentTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")

    @Test fun 默认直发但损坏或旧版本授权不扩义() {
        assertTrue(CloudChatConsent({ null }, {}).enabled(identity))
        for (stored in listOf("{}", "invalid", """{"version":1,"identityToken":"${CloudChatConsent.context(identity)}","enabled":"true"}""")) {
            assertFalse(CloudChatConsent({ stored }, {}).enabled(identity))
        }
        assertFalse(CloudChatConsent({ error("读取失败") }, {}).enabled(identity))
    }

    @Test fun 显式开启可持久恢复且关闭后不再授权() {
        var stored: String? = null
        val consent = CloudChatConsent({ stored }, { stored = it })
        val token = CloudChatConsent.context(identity)
        consent.set(identity, token, true)
        assertTrue(CloudChatConsent({ stored }, {}).enabled(identity))
        for (other in listOf(identity.copy(ownerId = "other"), identity.copy(deviceId = "other"), identity.copy(origin = "https://other.example.com"), identity.copy(credentialRef = "rotated"))) {
            assertFalse(consent.enabled(other))
            assertTrue(runCatching { consent.set(other, token, true) }.isFailure)
        }
        consent.set(identity, token, false)
        assertFalse(CloudChatConsent({ stored }, {}).enabled(identity))
    }

    @Test fun 写入失败不伪报开启成功() {
        val consent = CloudChatConsent({ "{}" }, {})
        assertTrue(runCatching { consent.set(identity, CloudChatConsent.context(identity), true) }.isFailure)
        assertFalse(consent.enabled(identity))
    }

    @Test fun 旧格式只读稳定且重新授权不恢复旧代次() {
        for (initial in listOf(null, """{"version":1,"identityToken":"${CloudChatConsent.context(identity)}","enabled":true}""")) {
            var stored = initial
            var writes = 0
            val consent = CloudChatConsent({ stored }, { stored = it; writes++ })
            val old = consent.generation(identity)!!
            assertEquals(old, CloudChatConsent({ stored }, {}).generation(identity))
            assertEquals(0, writes)
            consent.set(identity, CloudChatConsent.context(identity), false)
            assertNull(consent.generation(identity))
            consent.set(identity, CloudChatConsent.context(identity), true)
            val fresh = consent.generation(identity)!!
            assertNotEquals(old, fresh)
            assertEquals(fresh, CloudChatConsent({ stored }, {}).generation(identity))
            consent.set(identity, CloudChatConsent.context(identity), true)
            assertEquals(2, writes)
            assertEquals(fresh, consent.generation(identity))
        }
    }

    @Test fun 损坏二版代次或额外字段不能获得授权() {
        val prefix = "\"version\":2,\"identityToken\":\"${CloudChatConsent.context(identity)}\",\"enabled\":true"
        for (suffix in listOf("", ",\"generation\":null", ",\"generation\":123", ",\"generation\":\"bad\"",
            ",\"generation\":\"00000000-0000-4000-8000-000000000001\",\"extra\":true")) {
            val consent = CloudChatConsent({ "{$prefix$suffix}" }, { fail("只读校验不应写入") })
            assertFalse(consent.enabled(identity))
            assertNull(consent.generation(identity))
        }
        assertNull(CloudChatConsent({ error("读取失败") }, {}).generation(identity))
    }
}
