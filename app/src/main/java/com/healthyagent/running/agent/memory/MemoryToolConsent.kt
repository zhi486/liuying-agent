package com.healthyagent.running.agent.memory

import android.util.AtomicFile
import com.healthyagent.running.agent.cloud.CloudChatConsent
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.readCloudBytes
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*

/** 自动记忆独立授权；与聊天授权分开，关闭再开启不恢复旧请求的权限。 */
class MemoryToolConsent(private val read: () -> String?, private val write: (String) -> Unit) {
    private data class State(val scope: String, val generation: String, val enabled: Boolean, val factsAllowed: Boolean)

    private fun state(): State? = runCatching {
        val value = Json.parseToJsonElement(read() ?: return null).jsonObject
        val version = value["version"]
        require(version == JsonPrimitive("auto_memory_v1") || version == JsonPrimitive("auto_memory_v2"))
        require(value.keys == setOf("version", "scope", "generation", "enabled") +
            if (version == JsonPrimitive("auto_memory_v2")) setOf("factsAllowed") else emptySet())
        val scope = value.getValue("scope").jsonPrimitive
        val generation = value.getValue("generation").jsonPrimitive
        val enabled = value.getValue("enabled").jsonPrimitive
        require(scope.isString && scope.content.matches(Regex("[0-9a-f]{64}")))
        require(generation.isString)
        MemoryCommands.id(generation.content)
        require(!enabled.isString)
        val facts = if (version == JsonPrimitive("auto_memory_v1")) false else {
            val flag = value.getValue("factsAllowed").jsonPrimitive
            require(!flag.isString)
            requireNotNull(flag.booleanOrNull)
        }
        val active = requireNotNull(enabled.booleanOrNull)
        require(active || !facts)
        State(scope.content, generation.content, active, facts)
    }.getOrNull()

    @Synchronized fun current(identity: CloudTaskIdentity): MemoryToolGrant? = state()?.let {
        if (it.enabled && it.scope == CloudChatConsent.context(identity)) MemoryToolGrant(it.scope, it.generation, it.factsAllowed) else null
    }

    /** 设置页同时绑定身份及当前授权代际，迟到页面不能重新开启已关闭的权限。 */
    @Synchronized fun context(identity: CloudTaskIdentity): String = context(identity, state())

    private fun context(identity: CloudTaskIdentity, state: State?): String = MemoryToolPolicy.hash(JsonArray(listOf(
        JsonPrimitive(CloudChatConsent.context(identity)),
        state?.let { JsonPrimitive(it.generation) } ?: JsonNull,
        state?.let { JsonPrimitive(it.scope) } ?: JsonNull,
        state?.let { JsonPrimitive(it.enabled) } ?: JsonNull,
    ) + if (state?.factsAllowed == true) listOf(JsonPrimitive("facts_v1")) else emptyList()).toString())

    @Synchronized fun set(identity: CloudTaskIdentity, expectedContext: String, enabled: Boolean, factsAllowed: Boolean = false) {
        require(enabled || !factsAllowed)
        val previous = state()
        require(expectedContext == context(identity, previous)) { "记忆授权已变化，请重新读取" }
        val scope = CloudChatConsent.context(identity)
        if (previous?.scope == scope && previous.enabled == enabled && previous.factsAllowed == factsAllowed) return
        val generation = UUID.randomUUID().toString()
        write(buildJsonObject {
            put("version", if (factsAllowed) "auto_memory_v2" else "auto_memory_v1"); put("scope", scope)
            put("generation", generation); put("enabled", enabled)
            if (factsAllowed) put("factsAllowed", true)
        }.toString())
        check(state() == State(scope, generation, enabled, factsAllowed)) { "记忆授权未完整保存" }
    }

    companion object {
        fun create(file: File) = MemoryToolConsent(
            read = {
                if (!file.exists() && !File(file.path + ".bak").exists()) null
                else AtomicFile(file).openRead().use { it.readCloudBytes(4096) }.toString(Charsets.UTF_8)
            },
            write = { value ->
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
                catch (error: Exception) { atomic.failWrite(stream); throw error }
            },
        )
    }
}
