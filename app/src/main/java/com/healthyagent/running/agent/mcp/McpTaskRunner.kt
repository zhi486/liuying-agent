package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskIdentity
import com.healthyagent.running.agent.cloud.CloudTaskIntent
import com.healthyagent.running.agent.cloud.CloudTaskReceipt
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** 只供首次显式发送使用；恢复查询不启动此窗口，也不重新创建旧 SDK 交换。 */
class McpTaskRunner internal constructor(
    private val identity: () -> CloudTaskIdentity?, private val configuration: McpPrivateConfiguration,
    private val api: (CloudTaskIdentity) -> McpTaskApi,
    private val management: (CloudTaskIdentity) -> McpManagementApi,
    private val confirm: suspend (McpTaskInvocation) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val exchangeFactory: (McpPhoneScope, suspend (McpPhoneRequest) -> Unit) -> McpPhoneExchange = { scope, authorize ->
        McpPhoneExchange(scope, configuration, authorize)
    },
) {
    @Volatile private var foreground = false
    private val epoch = AtomicLong()

    fun setForeground(value: Boolean) { foreground = value; if (!value) closeWindows() }
    fun closeWindows() { epoch.incrementAndGet() }

    /** 只读已配置云端的选中目录；不发现、不访问插件、不上传问题或密钥。 */
    internal suspend fun selection(current: CloudTaskIdentity, ids: List<String>): McpTaskSelection {
        val window = epoch.get()
        fun check() { require(foreground && epoch.get() == window && identity() == current) { "插件选择已失效" } }
        check()
        require(ids.size in 1..8 && ids.distinct().size == ids.size)
        ids.forEach(McpManagementWire::id)
        return withTimeout(8000) {
            val port = management(current)
            val selected = ids.map { id ->
                check()
                val service = port.getService(current, id)
                check()
                service to configuration.status(McpPrivateSlot(current, id))
            }
            check()
            McpTaskSelection.capture(ids, selected)
        }
    }

    /** read(true) 只刷新原云任务回执，read(false) 只读本机；不允许提交或重放插件。 */
    internal suspend fun run(original: CloudTaskIntent, receipt: CloudTaskReceipt,
        dispatch: suspend (() -> Unit) -> Unit = { it() },
        read: suspend (Boolean) -> Pair<CloudTaskIntent, CloudTaskReceipt>?,
    ) {
        require(original.mcpTools() != null)
        val window = epoch.get()
        val current = original.identity
        fun checkWindow() {
            if (!foreground || epoch.get() != window || identity() != current || now() >= receipt.deadlineAtMillis)
                throw McpTransportFailure("stale_configuration")
        }
        checkWindow()
        // 手机时钟可能稍落后于云端；本机最多等三十秒，云端仍逐次执行原截止校验。
        val remaining = (receipt.deadlineAtMillis - now()).coerceAtMost(30000)
        if (remaining <= 0) throw McpTransportFailure("stale_configuration")
        val client = api(current)
        var opened: McpTaskContext? = null
        try { withTimeout(remaining) { coroutineScope {
            val port = management(current)
            suspend fun selected(): List<Pair<JsonObject, McpPrivateStatus>> = original.mcpTools()!!.services.map { reference ->
                checkWindow()
                val id = McpManagementWire.text(reference, "serviceId")
                val service = port.getService(current, id)
                checkWindow()
                service to configuration.status(McpPrivateSlot(current, id))
            }
            val context = McpTaskContext.capture(original, receipt, selected())
            opened = context
            fun checkPrivate() {
                checkWindow()
                original.mcpTools()!!.services.forEach { reference ->
                    val scope = context.scope(McpManagementWire.text(reference, "serviceId"))
                    val saved = configuration.status(McpPrivateSlot(current, scope.serviceId))
                    if (!saved.configured || saved.generation != scope.generation || saved.endpoint != scope.endpoint.toString())
                        throw McpTransportFailure("stale_configuration")
                }
            }
            fun checkTask(local: Pair<CloudTaskIntent, CloudTaskReceipt>) {
                if (local.first.copy(updatedAtMillis = original.updatedAtMillis) != original ||
                    local.second.cloudTaskId != receipt.cloudTaskId || local.second.acceptedAtMillis != receipt.acceptedAtMillis ||
                    local.second.deadlineAtMillis != receipt.deadlineAtMillis || local.second.confirmedControl != null)
                    throw McpTransportFailure("stale_configuration")
            }
            suspend fun checkLocal(): Boolean {
                checkPrivate()
                val latest = read(false) ?: throw McpTransportFailure("stale_configuration")
                checkTask(latest)
                return latest.second.status in setOf("queued", "running")
            }
            suspend fun checkAuthority(): Boolean {
                checkPrivate()
                // 两个独立只读请求并行；两者完成后仍核对原任务、目录及本机配置，不缓存授权。
                val (latest, services) = coroutineScope {
                    val task = async { read(true) ?: throw McpTransportFailure("stale_configuration") }
                    val directory = async { selected() }
                    task.await() to directory.await()
                }
                checkTask(latest)
                if (latest.second.status !in setOf("queued", "running")) return false
                val observed = McpTaskContext.capture(latest.first, latest.second, services)
                if (!context.matches(observed)) throw McpTransportFailure("stale_configuration")
                checkPrivate()
                return true
            }
            suspend fun checkPending(): Pair<Boolean, McpTaskInvocation?> {
                checkPrivate()
                // 确认意图和任务／目录都是只读查询；并行等待，全部复核后才允许批准或取件。
                return coroutineScope {
                    val authority = async { checkAuthority() }
                    val pending = async { client.taskConfirmation(context) }
                    authority.await() to pending.await()
                }
            }
            // 人工确认和网络等待期间也复核本机；回到前台不能复活此前窗口。
            val watcher = launch {
                while (true) {
                    delay(100)
                    checkPrivate()
                    val latest = read(false) ?: throw McpTransportFailure("stale_configuration")
                    checkTask(latest)
                    if (latest.second.status !in setOf("queued", "running")) throw McpTransportFailure("stale_configuration")
                }
            }
            data class Decision(val invocation: McpTaskInvocation, val approved: Boolean)
            data class Channel(val invocation: McpTaskInvocation, val exchange: McpPhoneExchange, val started: Long, var responseBytes: Int = 0)
            val decisions = mutableMapOf<String, Decision>()
            val channels = mutableMapOf<String, Channel>()
            try {
                while (checkLocal()) {
                    val (active, pending) = checkPending()
                    if (!active) break
                    checkPrivate()
                    if (pending != null) {
                        pending.validate(context)
                        if (pending.mode != "confirm") throw McpTransportFailure("invalid_response")
                        val previous = decisions[pending.invocationId]
                        if (previous != null) {
                            if (!previous.invocation.matches(pending)) throw McpTransportFailure("invalid_response")
                        } else {
                            if (decisions.size >= 6) throw McpTransportFailure("limit_exceeded")
                            val approved = confirm(pending)
                            val (stillActive, latest) = checkPending()
                            if (!stillActive) break
                            // 用户看见的意图必须仍是当前对象，确认返回不能跨窗口或换参数。
                            if (latest == null || !pending.matches(latest)) throw McpTransportFailure("stale_configuration")
                            checkPrivate()
                            client.decideTaskInvocation(context, pending, if (approved) "approve" else "deny")
                            decisions[pending.invocationId] = Decision(pending, approved)
                        }
                        if (!checkAuthority()) break
                    }
                    // 无人工等待时沿用本轮刚查的云状态；take 本身仍在云端重核全部权限。
                    if (!checkLocal()) break
                    val delivery = client.takeTaskExchange(context)
                    checkPrivate()
                    if (delivery == null) { delay(100); continue }
                    delivery.validate(context)
                    val invocation = delivery.invocation
                    fun checkApproval() {
                        if (invocation.mode == "confirm") {
                            val decision = decisions[invocation.invocationId]
                            if (decision == null || !decision.approved || !decision.invocation.matches(invocation))
                                throw McpTransportFailure("invalid_response")
                        }
                    }
                    checkApproval()
                    val channel = channels[invocation.invocationId] ?: run {
                        if (channels.size >= 6) throw McpTransportFailure("limit_exceeded")
                        val exchange = exchangeFactory(invocation.scope) { packet ->
                            invocation.authorize(packet); checkApproval()
                            if (!checkAuthority()) throw McpTransportFailure("stale_configuration")
                        }
                        Channel(invocation, exchange, nanoTime()).also { channels[invocation.invocationId] = it }
                    }
                    if (!channel.invocation.matches(invocation)) throw McpTransportFailure("invalid_response")
                    val callRemaining = 25000 - (nanoTime() - channel.started) / 1_000_000
                    if (callRemaining !in 1..25000) throw McpTransportFailure("limit_exceeded")
                    val reply = withTimeout(callRemaining) { channel.exchange.execute(delivery.packet) { send ->
                        dispatch { checkPrivate(); checkApproval(); send() }
                    } }
                    McpPhoneRequest.validateReply(invocation.scope, delivery.packet, reply)
                    channel.responseBytes += (reply["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.toByteArray(Charsets.UTF_8)?.size ?: 0
                    if (channel.responseBytes > 262144) throw McpTransportFailure("limit_exceeded")
                    // 错误可能来自 exchange 的末尾查权失败，不能把失败回执当作已通过查权。
                    if ("error" in reply && !checkAuthority()) break
                    // exchange 已在实际 I/O 后重新查云权限；回执前复核本机，云 reply 再次鉴权。
                    if (!checkLocal()) break
                    client.replyTaskExchange(context, delivery, reply)
                }
            } finally { watcher.cancelAndJoin() }
        } } } finally {
            // 待取消／撤回由原任务控制收敛；先 close 会放行“未知结果”模型续轮，抢在取消之前完成。
            // 其他退出只关闭云端在途窗口，不访问插件；关闭回执未知时不重试，也不掩盖原错误。
            if (identity() == current && opened != null) withContext(NonCancellable) {
                try { withTimeout(500) {
                    val latest = read(false)
                    if (latest != null && latest.first.pendingControl == null && latest.second.confirmedControl == null)
                        client.closeTaskMcp(requireNotNull(opened))
                } } catch (_: Exception) { }
            }
        }
    }
}
