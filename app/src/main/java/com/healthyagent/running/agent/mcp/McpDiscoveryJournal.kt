package com.healthyagent.running.agent.mcp

import android.util.AtomicFile
import com.healthyagent.running.agent.cloud.CloudChatConsent
import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.readCloudBytes
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** 本机状态仅表示上次确认；没有云回执不等于操作未执行，也不授予任何发送权。 */
class McpDiscoveryOperation internal constructor(val scope: McpPhoneScope, val view: McpDiscoveryView?) {
    override fun toString() = "McpDiscoveryOperation(仅本机操作元数据)"
}

/** 每个云身份最多 64 个操作。仅存绑定／固定视图，无请求正文、插件结果、会话 ID 或凭据。 */
class McpDiscoveryJournal internal constructor(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    fun list(identity: CloudTaskIdentity): List<McpDiscoveryOperation> = synchronized(lock) { load(identity).asReversed() }

    fun get(identity: CloudTaskIdentity, requestId: String): McpDiscoveryOperation = synchronized(lock) {
        load(identity).find { it.scope.requestId == requestId } ?: throw CloudTaskFailure("not_found")
    }

    fun begin(scope: McpPhoneScope) = synchronized(lock) {
        McpDiscoveryView.validateScope(scope)
        val previous = load(scope.identity).toMutableList()
        if (previous.any { it.scope.requestId == scope.requestId }) throw CloudTaskFailure("conflict")
        // 容量满时仅移除最早收到云终态的内部缓存；尚无回执或云端仍进行中的标识不能丢弃。
        if (previous.size == 64) {
            val removable = previous.indexOfFirst { it.view?.terminal == true }
            if (removable < 0) throw CloudTaskFailure("busy")
            previous.removeAt(removable)
        }
        persist(scope.identity, previous + McpDiscoveryOperation(scope, null))
    }

    fun observe(scope: McpPhoneScope, view: McpDiscoveryView) = synchronized(lock) {
        McpDiscoveryView.parse(view.envelope(scope).toString(), scope)
        val previous = load(scope.identity).toMutableList()
        val index = previous.indexOfFirst { it.scope.requestId == scope.requestId }
        if (index < 0) throw CloudTaskFailure("not_found")
        val saved = previous[index]
        if (binding(saved.scope) != binding(scope)) throw CloudTaskFailure("conflict")
        val old = saved.view
        if (old != null && (old.startedAtMillis != view.startedAtMillis || old.deadlineAtMillis != view.deadlineAtMillis ||
                (old.terminal && old != view))) throw CloudTaskFailure("invalid_response")
        if (old == view) return@synchronized
        previous[index] = McpDiscoveryOperation(scope, view)
        persist(scope.identity, previous)
    }

    private fun binding(scope: McpPhoneScope) = buildJsonObject {
        put("requestId", scope.requestId); put("serviceId", scope.serviceId); put("revision", scope.revision)
        put("generation", scope.generation); put("endpoint", scope.endpoint.toString())
    }
    private fun encoded(identity: CloudTaskIdentity, operations: List<McpDiscoveryOperation>) = buildJsonObject {
        put("version", 1); put("identityContext", CloudChatConsent.context(identity))
        put("operations", JsonArray(operations.map { operation -> buildJsonObject {
            put("binding", binding(operation.scope))
            put("discovery", operation.view?.envelope(operation.scope)?.getValue("discovery") ?: JsonNull)
        } }))
    }

    private fun load(identity: CloudTaskIdentity): List<McpDiscoveryOperation> = storage {
        val raw = read(CloudChatConsent.context(identity) + ".json") ?: return@storage emptyList()
        val row = McpRpcResponse.boundedObject(raw, 524288)
        require(row.keys == setOf("version", "identityContext", "operations") && row["version"] == JsonPrimitive(1) &&
            row["identityContext"] == JsonPrimitive(CloudChatConsent.context(identity)))
        val operations = row.getValue("operations").jsonArray
        require(operations.size <= 64)
        operations.map { item ->
            val operation = item.jsonObject
            require(operation.keys == setOf("binding", "discovery"))
            val fields = operation.getValue("binding").jsonObject
            require(fields.keys == setOf("requestId", "serviceId", "revision", "generation", "endpoint"))
            fun string(key: String) = fields.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val scope = McpPhoneScope(identity, string("requestId"), string("requestId"), string("serviceId"), string("revision"),
                string("generation"), McpEndpoint.parse(string("endpoint")))
            McpDiscoveryView.validateScope(scope)
            val view = operation.getValue("discovery").let { value -> if (value == JsonNull) null else
                McpDiscoveryView.parse(buildJsonObject { put("discovery", value) }.toString(), scope) }
            McpDiscoveryOperation(scope, view)
        }.also { require(it.map { record -> record.scope.requestId }.distinct().size == it.size) }
    }

    private fun persist(identity: CloudTaskIdentity, operations: List<McpDiscoveryOperation>) = storage {
        val value = encoded(identity, operations)
        require(value.toString().toByteArray(Charsets.UTF_8).size <= 524288)
        write(CloudChatConsent.context(identity) + ".json", value.toString())
        check(encoded(identity, load(identity)) == value)
    }
    private fun <T> storage(block: () -> T): T = try { block() }
        catch (_: Exception) { throw CloudTaskFailure("storage_unavailable") }

    companion object {
        private val lock = Any()
        fun create(directory: File) = McpDiscoveryJournal(
            read = { name ->
                val file = File(directory, name)
                if (!file.exists() && !File(file.path + ".bak").exists()) null else AtomicFile(file).openRead().use {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(it.readCloudBytes(524288))).toString()
                }
            },
            write = { name, text ->
                check(directory.isDirectory || directory.mkdirs())
                val atomic = AtomicFile(File(directory, name)); val stream = atomic.startWrite()
                try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
                catch (error: Exception) { atomic.failWrite(stream); throw error }
            },
        )
    }
}
