package com.healthyagent.running.agent.mcp

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 只使用独立 noBackup 子目录、虚构身份和插件秘密，不读取现有用户配置。 */
@RunWith(AndroidJUnit4::class)
class McpPrivateConfigurationDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.noBackupFilesDir, "synthetic-mcp-private-${UUID.randomUUID()}")
    private val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
    private val slot = McpPrivateSlot(CloudTaskIdentity("https://agent.example.com", "synthetic", "device", "credential"), UUID.randomUUID().toString())
    private val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
    private val secret = McpBearer("synthetic-device-plugin-secret")

    @Test fun 真实AndroidKeystore及AtomicFile重开后可读且没有明文秘密() {
        val store = McpPrivateConfiguration.create(isolated)
        val status = store.save(slot, store.status(slot).context, endpoint, secret)
        val file = File(File(directory, "mcp-private-config"), slot.fileName)
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(secret.value))
        val reopened = McpPrivateConfiguration.create(isolated)
        assertEquals(status, reopened.status(slot))
        assertEquals(secret.value, reopened.source(slot, endpoint, status.generation!!).read()!!.value)
    }

    @Test fun 复制密文到另一个服务不能通过真实GCM身份校验() {
        val store = McpPrivateConfiguration.create(isolated)
        store.save(slot, store.status(slot).context, endpoint, secret)
        val other = McpPrivateSlot(slot.identity, UUID.randomUUID().toString())
        val root = File(directory, "mcp-private-config")
        File(root, other.fileName).writeBytes(File(root, slot.fileName).readBytes())
        val error = assertThrows(McpTransportFailure::class.java) { McpPrivateConfiguration.create(isolated).status(other) }
        assertEquals("credential_unavailable", error.code); assertNull(error.cause)
        assertFalse(error.toString().contains(secret.value))
    }

    @Test fun 真实文件撤销后重建对象旧代际仍失效且不回退匿名() {
        val store = McpPrivateConfiguration.create(isolated)
        val before = store.save(slot, store.status(slot).context, endpoint, secret)
        val after = store.revoke(slot, before.context)
        val reopened = McpPrivateConfiguration.create(isolated)
        assertFalse(reopened.status(slot).configured)
        assertEquals(after, reopened.status(slot))
        assertThrows(McpTransportFailure::class.java) { reopened.source(slot, endpoint, before.generation!!).read() }
        val anonymous = reopened.save(slot, after.context, endpoint, null)
        assertNotEquals(before.generation, anonymous.generation)
        assertNull(reopened.source(slot, endpoint, anonymous.generation!!).read())
    }
}
