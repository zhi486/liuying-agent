package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class McpPrivateConfigurationTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val slot = McpPrivateSlot(identity, "11111111-1111-4111-8111-111111111111")
    private val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
    private val bearer = McpBearer("synthetic-plugin-secret")
    private val files = mutableMapOf<String, ByteArray>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var writes = 0
    private fun store() = McpPrivateConfiguration({ files[it]?.clone() }, { name, value -> files[name] = value.clone(); writes++ }, { key })
    private fun save(store: McpPrivateConfiguration = store()) = store.save(slot, store.status(slot).context, endpoint, bearer)
    private fun rejected(block: () -> Unit) = assertThrows(McpTransportFailure::class.java, block).also {
        assertNull(it.cause); assertFalse(it.toString().contains(bearer.value))
    }

    @Test fun 新配置默认不可用而匿名连接也必须明确保存() {
        val store = store()
        assertFalse(store.status(slot).configured)
        rejected { store.source(slot, endpoint, "missing").read() }
        val status = store.save(slot, store.status(slot).context, endpoint, null)
        assertTrue(status.configured); assertFalse(status.hasCredential)
        assertNull(store.source(slot, endpoint, status.generation!!).read())
        assertEquals(1, writes)
    }

    @Test fun 真实AES密文不含密钥且重建对象后可解密同一配置() {
        val status = save()
        assertTrue(status.hasCredential)
        assertFalse(files.values.single().toString(Charsets.UTF_8).contains(bearer.value))
        assertFalse(status.toString().contains(bearer.value))
        assertEquals(bearer.value, store().source(slot, endpoint, status.generation!!).read()!!.value)
        assertEquals(status, store().status(slot))
    }

    @Test fun 相同保存幂等而密钥轮换使旧句柄和迟到设置页失效() {
        val store = store(); val status = save(store); val original = files.values.single().clone()
        assertEquals(status, store.save(slot, status.context, endpoint, bearer))
        assertEquals(1, writes); assertArrayEquals(original, files.values.single())
        val source = store.source(slot, endpoint, status.generation!!)
        val rotated = store.save(slot, status.context, endpoint, McpBearer("synthetic-rotated-secret"))
        assertNotEquals(status.generation, rotated.generation)
        rejected { source.read() }
        rejected { store.save(slot, status.context, endpoint, bearer) }
        assertEquals("synthetic-rotated-secret", store.source(slot, endpoint, rotated.generation!!).read()!!.value)
    }

    @Test fun 撤销写入无秘密墓碑且再次开启不恢复旧句柄() {
        val store = store(); val status = save(store)
        val source = store.source(slot, endpoint, status.generation!!)
        val disabled = store.revoke(slot, status.context)
        assertFalse(disabled.configured); assertFalse(disabled.hasCredential)
        rejected { source.read() }
        assertEquals(disabled, store.revoke(slot, disabled.context)); assertEquals(2, writes)
        val enabled = store.save(slot, disabled.context, endpoint, bearer)
        assertNotEquals(status.generation, enabled.generation)
        rejected { source.read() }
    }

    @Test fun 更换端点后旧端点不能借用原密钥或重新激活旧配置() {
        val store = store(); val status = save(store)
        val replacement = McpEndpoint.parse("https://other.example.com/mcp")
        rejected { store.source(slot, replacement, status.generation!!).read() }
        val updated = store.save(slot, status.context, replacement, bearer)
        rejected { store.source(slot, endpoint, status.generation!!).read() }
        rejected { store.source(slot, endpoint, updated.generation!!).read() }
        assertEquals(bearer.value, store.source(slot, replacement, updated.generation!!).read()!!.value)
        assertEquals(1, files.size)
    }

    @Test fun 换账号设备凭据或服务均不继承配置且复制密文不能换绑() {
        val store = store(); save(store); val original = files.values.single().clone()
        val others = listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"),
            identity.copy(deviceId = "other"), identity.copy(credentialRef = "other")).map { McpPrivateSlot(it, slot.serviceId) } +
            McpPrivateSlot(identity, "22222222-2222-4222-8222-222222222222")
        for (other in others) {
            assertFalse(store.status(other).configured)
            store.save(other, store.status(other).context, endpoint, null)
            val name = files.keys.last(); files[name] = original.clone()
            rejected { store.status(other) }
        }
    }

    @Test fun 密文篡改截断错误版本及过长均拒绝且不回退匿名() {
        val store = store(); val status = save(store); val name = files.keys.single(); val original = files[name]!!.clone()
        val variants = listOf(ByteArray(0), original.copyOf(10), original.clone().also { it[0] = 2 },
            original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, ByteArray(8193))
        for (invalid in variants) {
            files[name] = invalid
            rejected { store.status(slot) }
            rejected { store.source(slot, endpoint, status.generation!!).read() }
            rejected { store.save(slot, status.context, endpoint, null) }
        }
        files[name] = original
        assertEquals(status, store.status(slot))
    }

    @Test fun 密钥丢失和读写失败只返回固定错误且不得报告保存成功() {
        save()
        rejected { McpPrivateConfiguration({ files[it] }, { _, _ -> }, { null }).status(slot) }
        rejected { McpPrivateConfiguration({ error(bearer.value) }, { _, _ -> }, { key }).status(slot) }
        val missing = McpPrivateConfiguration({ null }, { _, _ -> }, { key })
        rejected { missing.save(slot, missing.status(slot).context, endpoint, bearer) }
        val broken = McpPrivateConfiguration({ null }, { _, _ -> error(bearer.value) }, { key })
        rejected { broken.save(slot, broken.status(slot).context, endpoint, bearer) }
    }

    @Test fun 同一上下文并发修改只有一个成功而不静默覆盖() {
        val store = store(); val context = store.status(slot).context
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { index -> workers.submit<Boolean> {
                try { store.save(slot, context, endpoint, McpBearer("synthetic-key-$index")); true }
                catch (_: McpTransportFailure) { false }
            } }.map { it.get() }
            assertEquals(1, results.count { it }); assertEquals(1, writes)
        } finally { workers.shutdownNow() }
    }

    @Test fun 非服务ID与不安全Bearer在存储或网络之前拒绝() {
        for (id in listOf("../secret", "not-a-uuid", "11111111-1111-1111-1111-111111111111"))
            rejected { McpPrivateSlot(identity, id) }
        for (secret in listOf("", "contains space", "header\r\ninjection", "中", "a".repeat(4097)))
            rejected { McpBearer(secret) }
        assertTrue(files.isEmpty())
    }
}
