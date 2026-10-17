package com.healthyagent.running.agent.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudChatConsentTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")

    @Test fun 默认关闭且损坏或旧版本授权不扩义() {
        for (stored in listOf(null, "{}", "invalid", """{"version":1,"identityToken":"${CloudChatConsent.context(identity)}","enabled":"true"}""")) {
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
        val consent = CloudChatConsent({ null }, {})
        assertTrue(runCatching { consent.set(identity, CloudChatConsent.context(identity), true) }.isFailure)
        assertFalse(consent.enabled(identity))
    }
}
