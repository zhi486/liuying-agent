package com.healthyagent.running.agent.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudDeviceCredentialsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val bearer = CloudDeviceBearer("lyd_" + "b".repeat(64))
    private fun directory() = File(context.noBackupFilesDir, "credential-test-${UUID.randomUUID()}")

    @Test fun 真实Keystore加密存储重建后可读且文件不含明文() {
        val dir = directory(); val alias = "liuying.test.${UUID.randomUUID()}"
        val store = CloudDeviceCredentials(dir, alias)
        store.storeNew(identity, bearer)
        val file = File(dir, "generation.bin")
        val encrypted = file.readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains(bearer.value))
        assertEquals(bearer.value, CloudDeviceCredentials(dir, alias).read(identity).value)
        store.storeNew(identity, bearer)
        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test fun 同代次不同密钥或身份拒绝且不覆盖旧密文() {
        val dir = directory(); val store = CloudDeviceCredentials(dir, "liuying.test.${UUID.randomUUID()}")
        store.storeNew(identity, bearer)
        val original = File(dir, "generation.bin").readBytes()
        assertThrows(CloudTaskFailure::class.java) { store.storeNew(identity, CloudDeviceBearer("lyd_" + "c".repeat(64))) }
        listOf(identity.copy(origin = "https://other.example.com"), identity.copy(ownerId = "other"), identity.copy(deviceId = "other")).forEach {
            assertThrows(CloudTaskFailure::class.java) { store.read(it) }
            assertThrows(CloudTaskFailure::class.java) { store.storeNew(it, bearer) }
        }
        assertArrayEquals(original, File(dir, "generation.bin").readBytes())
        assertEquals(bearer.value, store.read(identity).value)
    }

    @Test fun 缺失密钥或篡改密文安全拒绝且不带原始异常() {
        val dir = directory(); val alias = "liuying.test.${UUID.randomUUID()}"
        val store = CloudDeviceCredentials(dir, alias)
        assertThrows(CloudTaskFailure::class.java) { store.read(identity) }
        store.storeNew(identity, bearer)
        assertThrows(CloudTaskFailure::class.java) { CloudDeviceCredentials(dir, "$alias.missing").read(identity) }
        RandomAccessFile(File(dir, "generation.bin"), "rw").use { file ->
            val position = file.length() - 1; file.seek(position); val value = file.read(); file.seek(position); file.write(value xor 1)
        }
        val error = assertThrows(CloudTaskFailure::class.java) { store.read(identity) }
        assertEquals("credential_unavailable", error.code)
        assertNull(error.cause)
        assertFalse(error.toString().contains(bearer.value))
    }
}
