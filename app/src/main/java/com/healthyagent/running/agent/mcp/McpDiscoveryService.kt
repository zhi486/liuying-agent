package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

internal fun discoveryDiagnosticCode(code: String): String = when (code) {
    "dns_unavailable", "tls_failed", "network_timeout", "network_unavailable", "invalid_response",
    "invalid_request", "credential_unavailable", "stale_configuration", "transport_failed",
    "catalog_changed", "limit_exceeded", "authentication_required", "storage_unavailable",
    "inactive", "conflict", "not_found", "busy", "unavailable", "unauthenticated" -> code
    else -> "unclassified"
}

/** 显式前台配置操作；不扫描旧操作、不自动重试 POST，不注册模型工具。 */
class McpDiscoveryService internal constructor(
    private val identity: () -> CloudTaskIdentity?, private val configuration: McpPrivateConfiguration,
    private val apiFactory: (CloudTaskIdentity) -> McpDiscoveryApi,
    private val journal: McpDiscoveryJournal,
    private val exchangeFactory: (McpPhoneScope, suspend (McpPhoneRequest) -> Unit) -> McpPhoneExchange = { scope, authorize ->
        McpPhoneExchange(scope, configuration, authorize)
    },
) {
    private val lock = Any()
    private var running = false

    suspend fun discover(serviceId: String, revision: String, expectedIdentity: CloudTaskIdentity? = null): McpDiscoveryView {
        synchronized(lock) { if (running) throw CloudTaskFailure("busy"); running = true }
        var stage = "prepare"
        var started = System.nanoTime()
        fun enter(next: String) { stage = next; started = System.nanoTime() }
        fun record(code: String, phase: String = stage) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            runCatching { android.util.Log.i("LiuyingCloud", "stage=mcp_discovery_$phase code=$code elapsedMs=$elapsedMs") }
        }
        return try { withTimeout(MCP_DISCOVERY_TIMEOUT_MS + 5_000) { withContext(Dispatchers.IO) {
            val current = identity() ?: throw CloudTaskFailure("credential_unavailable")
            if (expectedIdentity != null && expectedIdentity != current) throw McpTransportFailure("stale_configuration")
            val slot = McpPrivateSlot(current, serviceId)
            val saved = configuration.status(slot)
            if (!saved.configured || saved.generation == null || saved.endpoint == null) throw McpTransportFailure("credential_unavailable")
            val requestId = UUID.randomUUID().toString()
            val scope = McpPhoneScope(current, requestId, requestId, serviceId, revision, saved.generation, McpEndpoint.parse(saved.endpoint))
            McpDiscoveryView.validateScope(scope)
            fun checkLocal() {
                if (identity() != current) throw McpTransportFailure("stale_configuration")
                val latest = configuration.status(slot)
                if (!latest.configured || latest.context != saved.context) throw McpTransportFailure("stale_configuration")
            }
            // 先持久保存唯一操作标识，再创建任何网络端口；崩溃后只能查询这个标识。
            checkLocal()
            journal.begin(scope)
            val api = apiFactory(current)
            val exchange = exchangeFactory(scope) { packet ->
                checkLocal()
                if (!packet.matches(scope)) throw McpTransportFailure("invalid_response")
                val allowed = packet.method == "DELETE" || packet.body?.let {
                    McpRpcResponse.boundedObject(it, 16384)["method"]?.let { name ->
                        name is kotlinx.serialization.json.JsonPrimitive && name.isString &&
                            name.content in setOf("initialize", "notifications/initialized", "notifications/cancelled", "tools/list")
                    }
                } == true
                if (!allowed) throw McpTransportFailure("invalid_response")
                // 每次真实外网发送前及结果返回前重新核对云授权；状态查询不会领取或重发。
                val observed = api.discovery(scope)
                journal.observe(scope, observed)
                if (observed.terminal) throw McpTransportFailure("stale_configuration")
                checkLocal()
            }
            checkLocal()
            enter("start")
            var view = api.startDiscovery(scope)
            journal.observe(scope, view)
            while (!view.terminal) {
                checkLocal()
                enter("take")
                val packet = api.takeDiscovery(scope)
                record(if (packet == null) "empty" else "packet_received")
                if (packet != null) {
                    record("ok", "decode")
                    enter("https")
                    val reply = exchange.execute(packet)
                    val error = (reply["error"] as? JsonPrimitive)?.contentOrNull
                    record(if (error == null) "ok" else discoveryDiagnosticCode(error))
                    enter("reply")
                    checkLocal()
                    api.replyDiscovery(scope, packet, reply)
                    record("accepted")
                } else delay(100)
                enter("observe")
                checkLocal()
                view = api.discovery(scope)
                journal.observe(scope, view)
            }
            checkLocal()
            view
        } } } catch (cancelled: CancellationException) {
            record(if (cancelled is kotlinx.coroutines.TimeoutCancellationException) "timeout" else "cancelled")
            throw cancelled
        } catch (failure: CloudTaskFailure) {
            record(discoveryDiagnosticCode(failure.code))
            throw failure
        } catch (failure: McpTransportFailure) {
            record(discoveryDiagnosticCode(failure.code))
            throw failure
        } catch (_: Exception) {
            record("transport_failed")
            throw McpTransportFailure("transport_failed")
        }
        finally { synchronized(lock) { running = false } }
    }

    suspend fun operations(): List<McpDiscoveryOperation> = withContext(Dispatchers.IO) {
        val current = identity() ?: throw CloudTaskFailure("credential_unavailable")
        journal.list(current).also { if (identity() != current) throw McpTransportFailure("stale_configuration") }
    }

    /** 只对原操作发一次 GET；不读取插件密钥、不创建交换器，也不恢复原 SDK 会话。 */
    suspend fun recover(requestId: String, expectedIdentity: CloudTaskIdentity? = null): McpDiscoveryView {
        synchronized(lock) { if (running) throw CloudTaskFailure("busy"); running = true }
        return try { withTimeout(10000) { withContext(Dispatchers.IO) {
            val current = identity() ?: throw CloudTaskFailure("credential_unavailable")
            if (expectedIdentity != null && expectedIdentity != current) throw McpTransportFailure("stale_configuration")
            val original = journal.get(current, requestId)
            if (identity() != current) throw McpTransportFailure("stale_configuration")
            val view = apiFactory(current).discovery(original.scope)
            if (identity() != current) throw McpTransportFailure("stale_configuration")
            journal.observe(original.scope, view)
            view
        } } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CloudTaskFailure) { throw failure }
        catch (failure: McpTransportFailure) { throw failure }
        catch (_: Exception) { throw McpTransportFailure("transport_failed") }
        finally { synchronized(lock) { running = false } }
    }
}
