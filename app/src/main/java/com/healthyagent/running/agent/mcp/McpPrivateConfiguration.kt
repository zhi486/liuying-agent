package com.healthyagent.running.agent.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.healthyagent.running.agent.cloud.CloudChatConsent
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.readCloudBytes
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

/** 服务 ID 来自已认证的权威配置，不接受模型自报身份或任意文件名。 */
class McpPrivateSlot(val identity: CloudTaskIdentity, val serviceId: String) {
    init { if (!serviceId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) throw McpTransportFailure("invalid_request") }
    internal val aad = JsonArray(listOf("liuying_mcp_private_v1", CloudChatConsent.context(identity), serviceId).map(::JsonPrimitive))
        .toString().toByteArray(Charsets.UTF_8)
    internal val fileName = MessageDigest.getInstance("SHA-256").digest(aad).joinToString("") { "%02x".format(it) } + ".bin"
}

/** 仅含设置页需要的元数据，没有 Bearer、密文或可导出的密钥。 */
data class McpPrivateStatus(
    val configured: Boolean, val hasCredential: Boolean, val endpoint: String?, val generation: String?, val context: String,
)

/** 插件凭据独立于云设备凭据；方法只供可信原生宿主在 IO 线程调用，保存本身不授予工具权限。 */
class McpPrivateConfiguration internal constructor(
    private val read: (String) -> ByteArray?, private val write: (String, ByteArray) -> Unit,
    private val key: (create: Boolean) -> SecretKey?,
) {
    private data class Entry(val endpoint: McpEndpoint, val generation: String, val configured: Boolean, val bearer: McpBearer?) {
        override fun toString() = "McpPrivateEntry(已脱敏)"
    }

    fun status(slot: McpPrivateSlot): McpPrivateStatus = synchronized(lock) { guard { status(slot, load(slot)) } }

    fun save(slot: McpPrivateSlot, expectedContext: String, endpoint: McpEndpoint, bearer: McpBearer?): McpPrivateStatus = synchronized(lock) { guard {
        val previous = load(slot)
        if (expectedContext != status(slot, previous).context) throw McpTransportFailure("stale_configuration")
        if (previous?.configured == true && previous.endpoint.url == endpoint.url && previous.bearer?.value == bearer?.value)
            return@guard status(slot, previous)
        persist(slot, Entry(endpoint, UUID.randomUUID().toString(), true, bearer))
    } }

    /** 逻辑撤销清除当前记录内的秘密并换代；不承诺撤回已经发出的外部请求或磁盘安全擦除。 */
    fun revoke(slot: McpPrivateSlot, expectedContext: String): McpPrivateStatus = synchronized(lock) { guard {
        val previous = load(slot)
        if (expectedContext != status(slot, previous).context) throw McpTransportFailure("stale_configuration")
        if (previous == null || !previous.configured) return@guard status(slot, previous)
        persist(slot, Entry(previous.endpoint, UUID.randomUUID().toString(), false, null))
    } }

    /** 每次真实发送前重新读取；缺失／损坏／换端点／换代均拒绝，只有明确保存的匿名配置才返回 null。 */
    fun source(slot: McpPrivateSlot, endpoint: McpEndpoint, generation: String) = McpCredentialSource {
        synchronized(lock) { guard {
            val entry = load(slot) ?: throw McpTransportFailure("credential_unavailable")
            if (!entry.configured || entry.generation != generation || entry.endpoint.url != endpoint.url)
                throw McpTransportFailure("stale_configuration")
            entry.bearer
        } }
    }

    private fun status(slot: McpPrivateSlot, entry: Entry?): McpPrivateStatus {
        val context = JsonArray(listOf(JsonPrimitive(slot.fileName), entry?.let { JsonPrimitive(it.generation) } ?: JsonNull))
        val hash = MessageDigest.getInstance("SHA-256").digest(context.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return McpPrivateStatus(entry?.configured == true, entry?.bearer != null, entry?.endpoint?.toString(), entry?.generation, hash)
    }

    private fun load(slot: McpPrivateSlot): Entry? {
        val bytes = read(slot.fileName) ?: return null
        require(bytes.size in 29..8192 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireNotNull(key(false)), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(slot.aad)
        val value = Json.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)).jsonObject
        require(value.keys == setOf("endpoint", "generation", "configured", "bearer"))
        val endpoint = value.getValue("endpoint").jsonPrimitive.also { require(it.isString) }.content
        val generation = value.getValue("generation").jsonPrimitive.also { require(it.isString) }.content
        require(generation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        val configured = value.getValue("configured").jsonPrimitive.also { require(!it.isString) }.boolean
        val bearer = value.getValue("bearer").let { item -> if (item == JsonNull) null else McpBearer(item.jsonPrimitive.also { require(it.isString) }.content) }
        require(configured || bearer == null)
        return Entry(McpEndpoint.parse(endpoint), generation, configured, bearer)
    }

    private fun persist(slot: McpPrivateSlot, entry: Entry): McpPrivateStatus {
        val data = buildJsonObject {
            put("endpoint", entry.endpoint.toString()); put("generation", entry.generation); put("configured", entry.configured)
            put("bearer", entry.bearer?.let { JsonPrimitive(it.value) } ?: JsonNull)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key(true)))
        require(cipher.iv.size == 12)
        cipher.updateAAD(slot.aad)
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(data)
        require(bytes.size <= 8192)
        write(slot.fileName, bytes)
        val saved = requireNotNull(load(slot))
        check(saved.generation == entry.generation && saved.configured == entry.configured &&
            saved.endpoint.url == entry.endpoint.url && saved.bearer?.value == entry.bearer?.value)
        return status(slot, saved)
    }

    private fun <T> guard(run: () -> T): T = try { run() }
        catch (error: Exception) { throw if (error is McpTransportFailure) error else McpTransportFailure("credential_unavailable") }

    companion object {
        private val lock = Any()
        fun create(context: Context): McpPrivateConfiguration {
            val directory = File(context.noBackupFilesDir, "mcp-private-config")
            val alias = "liuying.agent.mcp.private.v1"
            return McpPrivateConfiguration(
                read = { name ->
                    val file = File(directory, name)
                    if (!file.exists() && !File(file.path + ".bak").exists()) null
                    else AtomicFile(file).openRead().use { it.readCloudBytes(8192) }
                },
                write = { name, bytes ->
                    check(directory.isDirectory || directory.mkdirs())
                    val atomic = AtomicFile(File(directory, name)); val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream) }
                    catch (error: Exception) { atomic.failWrite(stream); throw error }
                },
                key = { create ->
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    (store.getKey(alias, null) as? SecretKey) ?: if (!create) null else KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
                        generateKey()
                    }
                },
            )
        }
    }
}
