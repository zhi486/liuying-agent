package com.healthyagent.running.agent.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class CloudDeviceBearer internal constructor(internal val value: String) {
    init { require(Regex("lyd_[0-9a-f]{64}").matches(value)) { "设备凭据格式无效" } }
    override fun toString() = "CloudDeviceBearer(已脱敏)"
}

fun interface CloudCredentialSource { fun read(identity: CloudTaskIdentity): CloudDeviceBearer }

/** 必须在 IO 线程使用；密文在 noBackup 目录，密钥不离开 AndroidKeyStore。 */
class CloudDeviceCredentials internal constructor(private val directory: File, private val keyAlias: String) : CloudCredentialSource {
    override fun read(identity: CloudTaskIdentity): CloudDeviceBearer = synchronized(lock) {
        try {
            val file = file(identity)
            require(file.length() in 29..4096)
            val bytes = file.inputStream().use { it.readCloudBytes(4096) }
            require(bytes.size in 29..4096 && bytes[0] == 1.toByte())
            val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: error("凭据不可用")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad(identity))
            CloudDeviceBearer(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8))
        } catch (_: Exception) { throw CloudTaskFailure("credential_unavailable") }
    }

    /** 只接受新代次；同代次重复导入必须完全一致，绝不换绑旧意图。 */
    fun storeNew(identity: CloudTaskIdentity, bearer: CloudDeviceBearer) = synchronized(lock) {
        try {
            val target = file(identity)
            if (target.exists()) {
                require(MessageDigest.isEqual(read(identity).value.toByteArray(), bearer.value.toByteArray()))
                return@synchronized
            }
            require(directory.isDirectory || directory.mkdirs())
            val store = keyStore()
            val key = (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
                generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(identity))
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(bearer.value.toByteArray(Charsets.UTF_8))
            val temporary = File.createTempFile("credential-", ".pending", directory)
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) { throw CloudTaskFailure("credential_unavailable") }
    }

    private fun file(identity: CloudTaskIdentity) = File(directory, "${identity.credentialRef}.bin")
    private fun aad(identity: CloudTaskIdentity) = JsonArray(listOf("v1", identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef).map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    companion object {
        private val lock = Any()
        fun create(context: Context) = CloudDeviceCredentials(File(context.noBackupFilesDir, "cloud-device-credentials"), "liuying.agent.device.v1")
    }
}
