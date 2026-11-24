package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudChatConsent
import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.util.UUID

/** 原生私密窗口请求只含绑定元数据；密钥不经 Bridge 传入或返回。 */
class McpConfigurationRequest internal constructor(
    val token: String, internal val identity: CloudTaskIdentity, val id: String, val revision: String,
    val endpoint: String, internal val context: String,
) { override fun toString() = "McpConfigurationRequest(绑定元数据)" }

/** 设置入口；所有写入均按当前身份与审核版本执行，失败不自动补发。 */
class McpManagementService(
    private val identity: () -> CloudTaskIdentity?, private val privateConfiguration: McpPrivateConfiguration,
    private val api: (CloudTaskIdentity) -> McpManagementApi, private val discovery: McpDiscoveryService,
) {
    private val gate = Mutex()
    private val request = MutableStateFlow<McpConfigurationRequest?>(null)
    val configuring = request.asStateFlow()
    fun closeConfiguration(token: String? = null) {
        if (token == null || request.value?.token == token) request.value = null
    }

    suspend fun handle(name: String, body: JsonObject): CommandOutcome = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) return@withContext CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "插件操作进行中，请稍后读取状态")
        try {
            McpManagementCommands.validate(name, body)
            // 目录发现保留原有内部期限，外层还需容纳服务读取和回执整理。
            val timeoutMs = if (name == "mcp.discover") MCP_DISCOVERY_TIMEOUT_MS + 13_000 else 8_000L
            val result = withTimeout(timeoutMs) {
                if (name == "mcp.status") return@withTimeout status()
                val current = identity() ?: throw CloudTaskFailure("credential_unavailable")
                checkIdentity(current, McpManagementWire.text(body, "identityToken"))
                val id = body["id"]?.jsonPrimitive?.content
                val port = api(current)
                val data = when (name) {
                    "mcp.list" -> {
                        val listed = port.listServices(current, body["after"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                        buildJsonObject {
                            put("services", JsonArray(listed.getValue("services").jsonArray.map { item ->
                                val row = item.jsonObject
                                JsonObject(row + ("private" to privateStatus(current, row)))
                            }))
                            put("nextCursor", listed.getValue("nextCursor"))
                            put("phoneDiscoveryAvailable", listed["phoneDiscoveryAvailable"] == JsonPrimitive(true))
                        }
                    }
                    "mcp.recover" -> {
                        discovery.recover(McpManagementWire.text(body, "requestId"), current)
                        status()
                    }
                    "mcp.revokePrivate" -> {
                        privateConfiguration.revoke(McpPrivateSlot(current, requireNotNull(id)), McpManagementWire.text(body, "context"))
                        buildJsonObject { put("revoked", true) }
                    }
                    "mcp.register" -> detail(current, port.changeService(current, null, "register", buildJsonObject { put("endpoint", body.getValue("endpoint")) }))
                    else -> {
                        // 移除不依赖目录解析；仍先核对权威版本，再撤销本机代次，云端删除还会执行 CAS。
                        val service = port.getService(current, requireNotNull(id), summaryOnly = name == "mcp.remove")
                        checkIdentity(current)
                        if (name != "mcp.detail" && service["revision"] != body["revision"]) throw CloudTaskFailure("conflict")
                        when (name) {
                            "mcp.detail" -> detail(current, service)
                            "mcp.configure" -> {
                                val saved = privateConfiguration.status(McpPrivateSlot(current, id))
                                request.value = McpConfigurationRequest(UUID.randomUUID().toString(), current, id,
                                    McpManagementWire.text(service, "revision"), McpManagementWire.text(service, "endpoint"), saved.context)
                                buildJsonObject { put("opened", true) }
                            }
                            "mcp.discover" -> {
                                val saved = privateStatus(current, service)
                                if (saved["configured"] != JsonPrimitive(true) || saved["endpointMatches"] != JsonPrimitive(true))
                                    throw CloudTaskFailure("credential_unavailable")
                                discovery.discover(id, McpManagementWire.text(service, "revision"), current)
                                status()
                            }
                            "mcp.endpoint", "mcp.remove" -> {
                                if (name == "mcp.endpoint" && service["endpoint"] == body["endpoint"]) throw CloudTaskFailure("conflict")
                                // 先撤销旧代次；即使云结果丢失或以后改回旧地址，也不能复用旧密钥。
                                privateConfiguration.revoke(McpPrivateSlot(current, id), McpManagementWire.text(body, "context"))
                                request.value?.takeIf { it.identity == current && it.id == id }?.let { closeConfiguration(it.token) }
                                checkIdentity(current)
                                val changed = port.changeService(current, id, name.substringAfter('.'), buildJsonObject {
                                    put("revision", body.getValue("revision"))
                                    if (name == "mcp.endpoint") put("endpoint", body.getValue("endpoint"))
                                })
                                if (name == "mcp.remove") changed else detail(current, changed)
                            }
                            else -> {
                                val payload = buildJsonObject {
                                    put("revision", body.getValue("revision"))
                                    if (name == "mcp.approve") { put("confirmed", true); put("selection", body.getValue("selection")) }
                                }
                                detail(current, port.changeService(current, id, name.substringAfter('.'), payload))
                            }
                        }
                    }
                }
                checkIdentity(current)
                JsonObject(data + ("identityToken" to JsonPrimitive(CloudChatConsent.context(current))))
            }
            CommandOutcome.AcceptedWithData(result)
        } catch (_: TimeoutCancellationException) { unknown() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unknown() }
        finally { gate.unlock() }
    }

    /** 保存前重新读取云端版本及本机代次，旧窗口不能给新账号或变更后的端点保存密钥。 */
    suspend fun saveConfiguration(opened: McpConfigurationRequest, bearer: String, anonymous: Boolean) = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) throw CloudTaskFailure("busy")
        try { withTimeout(8000) {
            check(opened === request.value)
            checkIdentity(opened.identity)
            val service = api(opened.identity).getService(opened.identity, opened.id)
            check(service["revision"] == JsonPrimitive(opened.revision) && service["endpoint"] == JsonPrimitive(opened.endpoint))
            checkIdentity(opened.identity)
            check(opened === request.value && (if (anonymous) bearer.isEmpty() else bearer.isNotBlank()))
            privateConfiguration.save(McpPrivateSlot(opened.identity, opened.id), opened.context,
                McpEndpoint.parse(opened.endpoint), if (anonymous) null else McpBearer(bearer))
            checkIdentity(opened.identity)
        } } finally { gate.unlock() }
    }
    private fun checkIdentity(current: CloudTaskIdentity, token: String = CloudChatConsent.context(current)) {
        if (identity() != current || token != CloudChatConsent.context(current)) throw CloudTaskFailure("conflict")
    }
    private fun privateStatus(current: CloudTaskIdentity, service: JsonObject): JsonObject {
        val saved = privateConfiguration.status(McpPrivateSlot(current, McpManagementWire.text(service, "id")))
        return buildJsonObject {
            put("configured", saved.configured); put("hasCredential", saved.hasCredential); put("context", saved.context)
            put("endpointMatches", saved.endpoint == McpManagementWire.text(service, "endpoint"))
        }
    }
    private fun detail(current: CloudTaskIdentity, service: JsonObject) = buildJsonObject {
        put("service", service); put("private", privateStatus(current, service))
    }
    private suspend fun status(): JsonObject {
        val current = identity()
        val operations = if (current == null) emptyList() else discovery.operations()
        if (current != null) checkIdentity(current)
        return buildJsonObject {
            put("configured", current != null)
            put("identityToken", current?.let { JsonPrimitive(CloudChatConsent.context(it)) } ?: JsonNull)
            put("operations", JsonArray(operations.map { operation -> buildJsonObject {
                put("requestId", operation.scope.requestId); put("serviceId", operation.scope.serviceId)
                put("endpoint", operation.scope.endpoint.toString()); put("status", operation.view?.status ?: "unknown")
                put("reason", operation.view?.reason?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
    }
    private fun unknown() = CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE,
        "插件操作未确认完成，请重新读取配置或查询原发现记录；地址修改或移除若已开始，手机私密配置可能已撤销，不会自动重试")
}
