package com.healthyagent.running.agent.cloud

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** 原生私密配置：身份元数据排除备份；Bearer 单独交由 Keystore 加密存储。 */
class CloudAgentConfiguration(private val file: File, private val credentials: CloudDeviceCredentials) {
    @Synchronized fun read(): CloudTaskIdentity? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        try {
            val data = AtomicFile(file).openRead().use { it.readCloudBytes(4096) }.toString(Charsets.UTF_8)
            val value = Json.parseToJsonElement(data).jsonObject
            require(value.keys == setOf("origin", "ownerId", "deviceId", "credentialRef"))
            return CloudTaskIdentity(value.getValue("origin").jsonPrimitive.content, value.getValue("ownerId").jsonPrimitive.content,
                value.getValue("deviceId").jsonPrimitive.content, value.getValue("credentialRef").jsonPrimitive.content)
        } catch (_: Exception) { throw CloudTaskFailure("credential_unavailable") }
    }

    @Synchronized fun save(origin: String, ownerId: String, deviceId: String, secret: String) {
        val previous = runCatching { read() }.getOrNull()
        if (previous != null && previous.origin == origin.trim() && previous.ownerId == ownerId.trim() && previous.deviceId == deviceId.trim() &&
            runCatching { MessageDigest.isEqual(credentials.read(previous).value.toByteArray(), secret.trim().toByteArray()) }.getOrDefault(false)) return
        val identity = CloudTaskIdentity(origin.trim(), ownerId.trim(), deviceId.trim(), UUID.randomUUID().toString())
        credentials.storeNew(identity, CloudDeviceBearer(secret.trim()))
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(buildJsonObject { put("origin", identity.origin); put("ownerId", identity.ownerId); put("deviceId", identity.deviceId); put("credentialRef", identity.credentialRef) }.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
        check(read() == identity)
    }
}
