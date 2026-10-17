package com.healthyagent.running.agent.cloud

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** 仅授权主动文字聊天；身份或凭据代际变化后关闭，不包含额外数据或工具权限。 */
class CloudChatConsent(private val read: () -> String?, private val write: (String) -> Unit) {
    @Synchronized fun enabled(identity: CloudTaskIdentity): Boolean = runCatching {
        val value = Json.parseToJsonElement(read() ?: return false).jsonObject
        value.keys == setOf("version", "identityToken", "enabled") &&
            value["version"] == JsonPrimitive(1) &&
            value["identityToken"] == JsonPrimitive(context(identity)) &&
            value["enabled"] == JsonPrimitive(true)
    }.getOrDefault(false)

    @Synchronized fun set(identity: CloudTaskIdentity, expectedContext: String, enabled: Boolean) {
        require(expectedContext == context(identity)) { "配置已变化，请重新读取授权" }
        write(buildJsonObject {
            put("version", 1); put("identityToken", expectedContext); put("enabled", enabled)
        }.toString())
        check(this.enabled(identity) == enabled)
    }

    companion object {
        fun context(identity: CloudTaskIdentity): String = MessageDigest.getInstance("SHA-256")
            .digest(JsonArray(listOf(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef).map(::JsonPrimitive))
                .toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun create(file: File): CloudChatConsent = CloudChatConsent(
            read = {
                if (!file.exists() && !File(file.path + ".bak").exists()) null
                else AtomicFile(file).openRead().use { it.readCloudBytes(4096) }.toString(Charsets.UTF_8)
            },
            write = { value ->
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(value.toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (error: Exception) { atomic.failWrite(stream); throw error }
            },
        )
    }
}
