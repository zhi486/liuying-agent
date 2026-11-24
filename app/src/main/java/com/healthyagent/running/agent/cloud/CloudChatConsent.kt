package com.healthyagent.running.agent.cloud

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** 默认允许主动文字聊天；显式选择绑定身份，损坏或身份变化时关闭，不包含私密数据与工具权限。 */
class CloudChatConsent(private val read: () -> String?, private val write: (String) -> Unit) {
    private data class State(val enabled: Boolean, val generation: String)

    private fun state(identity: CloudTaskIdentity): State? = runCatching {
        val stored = read() ?: return State(true, "legacy-default:${context(identity)}")
        val value = Json.parseToJsonElement(stored).jsonObject
        val version = value["version"]
        require(version == JsonPrimitive(1) || version == JsonPrimitive(2))
        require(value.keys == setOf("version", "identityToken", "enabled") +
            if (version == JsonPrimitive(2)) setOf("generation") else emptySet())
        require(value["identityToken"] == JsonPrimitive(context(identity)))
        val flag = value.getValue("enabled").jsonPrimitive
        require(!flag.isString)
        val enabled = requireNotNull(flag.booleanOrNull)
        val generation = if (version == JsonPrimitive(1)) "legacy-v1:${context(identity)}:$enabled" else {
            val token = value.getValue("generation").jsonPrimitive
            require(token.isString)
            com.healthyagent.running.agent.memory.MemoryCommands.id(token.content)
        }
        State(enabled, generation)
    }.getOrNull()

    @Synchronized fun enabled(identity: CloudTaskIdentity): Boolean = state(identity)?.enabled == true

    /** 仅供本机失效判定；读取旧格式不写盘，不代表新增权限。 */
    @Synchronized fun generation(identity: CloudTaskIdentity): String? = state(identity)?.takeIf { it.enabled }?.generation

    @Synchronized fun set(identity: CloudTaskIdentity, expectedContext: String, enabled: Boolean) {
        require(expectedContext == context(identity)) { "配置已变化，请重新读取授权" }
        val previous = state(identity)
        if (previous?.enabled == enabled && !previous.generation.startsWith("legacy-default:")) return
        val generation = UUID.randomUUID().toString()
        write(buildJsonObject {
            put("version", 2); put("identityToken", expectedContext); put("enabled", enabled)
            put("generation", generation)
        }.toString())
        check(state(identity) == State(enabled, generation))
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
