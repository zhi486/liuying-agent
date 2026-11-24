package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.*
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpTaskRunnerTest {
    private val f = McpTaskFixtures
    private inner class Rig(val scheduler: TestCoroutineScheduler, val mode: String = "read") : McpTaskApi, McpManagementApi {
        var identity: CloudTaskIdentity? = f.identity
        private val files = mutableMapOf<String, ByteArray>()
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val configuration = McpPrivateConfiguration({ files[it]?.clone() }, { name, bytes -> files[name] = bytes.clone() }, { key })
        val slot = McpPrivateSlot(f.identity, f.id(1))
        val saved = configuration.save(slot, configuration.status(slot).context, McpEndpoint.parse(f.endpoint), McpBearer("synthetic-plugin-key"))
        var service = f.service(mode)
        var intent = f.intent.copy(mcpToolsJson = buildJsonObject { put("version", "native_mcp_v1")
            put("services", JsonArray(listOf(buildJsonObject { put("serviceId", f.id(1)); put("revision", f.id(2)); put("generation", saved.generation) }))) }.toString())
        var receipt = f.receipt
        val context get() = McpTaskContext.capture(intent, receipt, listOf(service to saved))
        var wireIntent = f.invocation(context)
        var asks = 0; var takes = 0; var replies = 0; var sends = 0; var decisions = 0; var closes = 0; var refreshes = 0
        var index = 0
        var pending = mode == "confirm"
        var duplicate = false
        var noDelivery = false
        var failAt: String? = null
        var onAsk: suspend () -> Boolean = { true }
        var onSend: suspend () -> Unit = {}
        var onTake: () -> Unit = {}
        var onDecision: () -> Unit = {}
        var onGet: () -> Unit = {}
        var responseSize = 0
        var clockOffset = 0L
        var cloudDelay = 0L
        var deleted = false
        var directoryReads = 0
        val methods = mutableListOf<String>()
        val wireReplies = mutableListOf<JsonObject>()
        val sequence = listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "DELETE")
        lateinit var runner: McpTaskRunner
        fun host(): McpTaskRunner = McpTaskRunner({ identity }, configuration, { this }, { this }, confirm = {
            asks++; assertTrue(it.argumentsJson.contains("今晚")); onAsk()
        }, now = { 1000 + scheduler.currentTime + clockOffset }, nanoTime = { scheduler.currentTime * 1_000_000 },
            exchangeFactory = { scope, authorize -> McpPhoneExchange(scope, configuration, authorize) { _, source, packet, dispatch ->
                dispatch {
                    assertEquals("synthetic-plugin-key", source.read()!!.value)
                    sends++; methods += packet.body?.let { Json.parseToJsonElement(it).jsonObject["method"]!!.jsonPrimitive.content } ?: "DELETE"
                }
                onSend()
                if (packet.method == "DELETE") McpHttpReply(204, "", null, false)
                else {
                    val message = Json.parseToJsonElement(packet.body!!).jsonObject
                    if ("id" !in message) McpHttpReply(202, "", null, false)
                    else McpHttpReply(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", message.getValue("id"));
                        put("result", buildJsonObject { put("content", if (responseSize == 0) "合成观察" else "x".repeat(responseSize)) }) }.toString(), null, false)
                }
            } }).also { runner = it; it.setForeground(true) }
        suspend fun run(host: McpTaskRunner = host(), dispatch: suspend (() -> Unit) -> Unit = { it() }) = host.run(intent, receipt, dispatch) { refresh ->
            if (refresh) { refreshes++; delay(cloudDelay) }
            if (deleted) null else intent to receipt
        }
        override suspend fun taskConfirmation(context: McpTaskContext): McpTaskInvocation? {
            delay(cloudDelay)
            if (failAt == "get") throw CloudTaskFailure("network_timeout")
            return if (pending) McpTaskInvocation.parse(wireIntent, context) else null
        }
        override suspend fun takeTaskExchange(context: McpTaskContext): McpTaskDelivery? {
            delay(cloudDelay)
            takes++; onTake()
            if (failAt == "take") throw CloudTaskFailure("network_timeout")
            if (noDelivery || pending) return null
            val method = sequence[index]
            val packet = if (method == "DELETE") JsonObject(f.packet(context) + mapOf("method" to JsonPrimitive("DELETE"), "body" to JsonNull))
                else f.packet(context, method = method)
            val exchange = JsonObject(packet + ("exchangeId" to JsonPrimitive(f.id(if (duplicate) 20 else index + 20))))
            return McpTaskWire.delivery(f.delivery(context, value = wireIntent, exchange = exchange), context)
        }
        override suspend fun replyTaskExchange(context: McpTaskContext, delivery: McpTaskDelivery, reply: JsonObject) {
            delay(cloudDelay)
            replies++; wireReplies += reply
            if (failAt == "reply") throw CloudTaskFailure("network_timeout")
            index++
            if (index == sequence.size) receipt = receipt.copy(status = "completed")
        }
        override suspend fun decideTaskInvocation(context: McpTaskContext, invocation: McpTaskInvocation, decision: String) {
            decisions++; onDecision()
            if (failAt == "decision") throw CloudTaskFailure("network_timeout")
            assertTrue(decision in setOf("approve", "deny"))
            pending = false
            if (decision == "deny") receipt = receipt.copy(status = "completed")
        }
        override suspend fun closeTaskMcp(context: McpTaskContext) { closes++; if (failAt == "close") throw CloudTaskFailure("network_timeout") }
        override suspend fun getService(identity: CloudTaskIdentity, id: String, summaryOnly: Boolean): JsonObject {
            check(!summaryOnly)
            directoryReads++; delay(cloudDelay); onGet(); return service
        }
        override suspend fun listServices(identity: CloudTaskIdentity, after: String?): JsonObject = error("不得列出其他服务")
        override suspend fun changeService(identity: CloudTaskIdentity, id: String?, action: String, body: JsonObject): JsonObject = error("不得更改授权")
    }
    private suspend fun rejected(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        error("应关闭不再有效的插件窗口")
    }

    @Test fun 查权已完成但等派发锁时删除或撤回任务不能开始插件交换() = runTest {
        for (deleted in listOf(false, true)) {
            val rig = Rig(testScheduler)
            val gate = Mutex(locked = true)
            val entered = CompletableDeferred<Unit>()
            val work = async {
                rejected { rig.run(dispatch = { send ->
                    entered.complete(Unit)
                    gate.withLock {
                        if (rig.deleted || rig.intent.pendingControl != null) throw McpTransportFailure("stale_configuration")
                        send()
                    }
                }) }
            }
            entered.await()
            assertTrue(rig.refreshes >= 2)
            if (deleted) rig.deleted = true else rig.intent = rig.intent.copy(pendingControl = "withdraw")
            gate.unlock(); work.await()
            assertEquals(0, rig.sends); assertEquals(0, rig.replies); assertEquals(1, rig.takes)
        }
    }

    @Test fun 选择预览只读目录并在身份或前台窗口变化时拒绝() = runTest {
        val rig = Rig(testScheduler)
        val host = rig.host()
        val snapshot = host.selection(f.identity, listOf(f.id(1)))
        assertEquals(rig.intent.mcpTools()!!.wire(), snapshot.configuration.wire())
        assertEquals(0, rig.sends); assertEquals(0, rig.takes); assertEquals(0, rig.asks)
        host.setForeground(false)
        rejected { host.selection(f.identity, listOf(f.id(1))) }
        host.setForeground(true)
        rig.onGet = { host.setForeground(false); host.setForeground(true) }
        rejected { host.selection(f.identity, listOf(f.id(1))) }
        rig.onGet = { rig.identity = f.identity.copy(deviceId = "changed") }
        rejected { host.selection(f.identity, listOf(f.id(1))) }
        assertEquals(0, rig.sends)
    }

    @Test fun 只读许可快速顺序处理五次交换且不读取其他服务或上传密钥() = runTest {
        val rig = Rig(testScheduler)
        rig.run()
        assertEquals(rig.sequence, rig.methods); assertEquals(5, rig.replies); assertEquals(0, rig.asks)
        assertEquals(1, rig.closes); assertFalse(rig.wireReplies.toString().contains("synthetic-plugin-key"))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun 手机时钟落后不误关刚接收的窗口且本机等待不超过三十秒() = runTest {
        val running = Rig(testScheduler).apply { clockOffset = -2000 }
        running.run()
        assertEquals(running.sequence, running.methods)
        val waiting = Rig(testScheduler).apply { clockOffset = -2000; noDelivery = true }
        val started = testScheduler.currentTime
        rejected { waiting.run() }
        assertEquals(30000L, testScheduler.currentTime - started)
        assertEquals(0, waiting.sends); assertEquals(1, waiting.closes)
    }

    @Test fun 每轮独立云读取并行且每次插件发送前后仍重新查权() = runTest {
        val rig = Rig(testScheduler).apply { cloudDelay = 150 }
        // 终态可由本机 watcher 先观察到并关闭；两条路径都必须完成五个唯一回执。
        val failure = runCatching { rig.run() }.exceptionOrNull()
        assertTrue(failure == null || failure is McpTransportFailure)
        assertEquals(rig.sequence, rig.methods)
        assertEquals(5, rig.replies); assertEquals("completed", rig.receipt.status)
        // 五次交换各在循环、发送前、发送后查权；终态轮若先被 watcher 关闭可不发额外 GET。
        assertTrue(rig.refreshes in 15..16); assertEquals(rig.refreshes + 1, rig.directoryReads)
        assertTrue(testScheduler.currentTime in 3900L..4050L)
    }

    @Test fun 并行读取期间云撤权仍阻止第一条插件请求() = runTest {
        val rig = Rig(testScheduler).apply { cloudDelay = 150 }
        rig.onGet = { if (rig.directoryReads >= 2) rig.service = JsonObject(rig.service + ("revision" to JsonPrimitive(f.id(42)))) }
        rejected { rig.run() }
        assertEquals(0, rig.sends); assertEquals(0, rig.takes); assertEquals(1, rig.closes)
    }

    @Test fun 并行查询确认失联不会因另一路查权成功而领取或派发() = runTest {
        val rig = Rig(testScheduler, "confirm").apply { cloudDelay = 150; failAt = "get" }
        rejected { rig.run() }
        assertEquals(0, rig.takes); assertEquals(0, rig.sends); assertEquals(0, rig.decisions)
        assertEquals(0, rig.replies); assertEquals(1, rig.closes)
    }

    @Test fun 写操作只在展示原意图并收到批准回执后才握手() = runTest {
        val rig = Rig(testScheduler, "confirm")
        rig.onSend = { assertEquals(1, rig.decisions); assertEquals(1, rig.asks) }
        rig.run()
        assertEquals(5, rig.sends); assertEquals(1, rig.asks); assertEquals(1, rig.decisions); assertEquals(1, rig.closes)
    }

    @Test fun 拒绝不启动握手且不能以工具信封冒充已批准() = runTest {
        val denied = Rig(testScheduler, "confirm")
        denied.onAsk = { false }
        // 模拟云端拒绝后终态；无插件握手。
        denied.onDecision = { denied.receipt = denied.receipt.copy(status = "completed") }
        denied.run()
        assertEquals(0, denied.sends); assertEquals(1, denied.decisions)
        val forged = Rig(testScheduler, "confirm").apply { pending = false }
        rejected { forged.run() }
        assertEquals(0, forged.sends); assertEquals(0, forged.asks); assertEquals(1, forged.closes)
    }

    @Test fun 看过确认后参数发生变化不批准也不发送() = runTest {
        val rig = Rig(testScheduler, "confirm")
        rig.onAsk = { rig.wireIntent = f.invocation(rig.context, """{"query":"删除所有"}"""); true }
        rejected { rig.run() }
        assertEquals(0, rig.decisions); assertEquals(0, rig.sends); assertEquals(1, rig.closes)
    }

    @Test fun 领取或回执或批准失联均关闭一次不重试() = runTest {
        for (stage in listOf("take", "reply", "decision")) {
            val rig = Rig(testScheduler, if (stage == "decision") "confirm" else "read").apply { failAt = stage }
            rejected { rig.run() }
            assertEquals(1, rig.closes)
            assertEquals(if (stage == "decision") 0 else 1, rig.takes)
            assertEquals(if (stage == "reply") 1 else 0, rig.sends)
            assertTrue(rig.decisions <= 1); assertTrue(rig.replies <= 1)
        }
    }

    @Test fun 重复交付不能靠新循环重新发送同一交换() = runTest {
        val rig = Rig(testScheduler).apply { duplicate = true }
        rejected { rig.run() }
        assertEquals(1, rig.sends); assertEquals(1, rig.replies); assertEquals(2, rig.takes); assertEquals(1, rig.closes)
    }

    @Test fun 取件后撤销本机密钥或换身份均不能出网() = runTest {
        for (identityChange in listOf(false, true)) {
            val rig = Rig(testScheduler)
            rig.onTake = { if (identityChange) rig.identity = f.identity.copy(credentialRef = "changed")
                else rig.configuration.revoke(rig.slot, rig.saved.context) }
            rejected { rig.run() }
            assertEquals(0, rig.sends); assertEquals(0, rig.replies)
            assertEquals(if (identityChange) 0 else 1, rig.closes)
        }
    }

    @Test fun 插件返回时撤权或原任务取消均不回传观察() = runTest {
        for (cancel in listOf(false, true)) {
            val rig = Rig(testScheduler)
            rig.onSend = { if (cancel) rig.intent = rig.intent.copy(pendingControl = "cancel")
                else rig.configuration.revoke(rig.slot, rig.saved.context) }
            rejected { rig.run() }
            assertEquals(1, rig.sends); assertEquals(0, rig.replies); assertEquals(if (cancel) 0 else 1, rig.closes)
        }
    }

    @Test fun 等待确认时取消或撤回不先关闭通道放行模型续轮() = runTest {
        for (action in listOf("cancel", "withdraw")) {
            val rig = Rig(testScheduler, "confirm")
            rig.onAsk = { rig.intent = rig.intent.copy(pendingControl = action); awaitCancellation() }
            rejected { rig.run() }
            assertEquals(1, rig.asks); assertEquals(0, rig.decisions)
            assertEquals(0, rig.sends); assertEquals(0, rig.takes); assertEquals(0, rig.closes)
        }
    }

    @Test fun 云目录换代后不进入下一次交换() = runTest {
        val rig = Rig(testScheduler)
        rig.onSend = { rig.service = JsonObject(rig.service + ("revision" to JsonPrimitive(f.id(42)))) }
        rejected { rig.run() }
        assertEquals(1, rig.sends); assertEquals(0, rig.replies); assertEquals(1, rig.closes)
    }

    @Test fun 后台和文档重载中止正在等待的网络或人工确认() = runTest {
        for (background in listOf(false, true)) {
            val rig = Rig(testScheduler, if (background) "read" else "confirm")
            val entered = CompletableDeferred<Unit>()
            if (background) rig.onSend = { entered.complete(Unit); awaitCancellation() }
            else rig.onAsk = { entered.complete(Unit); awaitCancellation() }
            val host = rig.host()
            val work = async { rejected { rig.run(host) } }
            entered.await()
            if (background) { host.setForeground(false); host.setForeground(true) } else host.closeWindows()
            advanceTimeBy(101); runCurrent(); work.await()
            assertEquals(0, rig.replies); assertEquals(0, rig.decisions); assertEquals(1, rig.closes)
        }
    }

    @Test fun 原任务截止不因空轮询或新SDK窗口续租() = runTest {
        val waiting = Rig(testScheduler).apply { noDelivery = true }
        rejected { waiting.run() }
        assertEquals(30000L, testScheduler.currentTime); assertEquals(0, waiting.sends); assertEquals(1, waiting.closes)
        val slow = Rig(testScheduler)
        slow.onSend = { delay(4000) }
        val started = testScheduler.currentTime
        rejected { slow.run() }
        // 当前虚拟时钟已过该任务截止，不能以新窗口开始旧任务。
        assertEquals(started, testScheduler.currentTime); assertEquals(0, slow.sends)
    }

    @Test fun 云端每次往返六百毫秒仍可完成一次已批准调用且不跳过查权() = runTest {
        val rig = Rig(testScheduler, "confirm").apply { cloudDelay = 600 }
        val failure = runCatching { rig.run() }.exceptionOrNull()
        assertTrue(failure == null || failure is McpTransportFailure)
        assertEquals("completed", rig.receipt.status)
        assertEquals(rig.sequence, rig.methods)
        assertEquals(1, rig.asks); assertEquals(1, rig.decisions)
        assertEquals(5, rig.sends); assertEquals(5, rig.replies)
        assertTrue(rig.directoryReads >= 15)
        assertTrue(testScheduler.currentTime < 30000L)
    }

    @Test fun 单次SDK多轮共享二十五秒不会逐次续租() = runTest {
        val rig = Rig(testScheduler)
        rig.onSend = { delay(6000) }
        rejected { rig.run() }
        assertEquals(25000L, testScheduler.currentTime); assertEquals(5, rig.sends); assertEquals(4, rig.replies)
        assertEquals(1, rig.closes)
    }

    @Test fun 原任务较早截止仍中止二十五秒会话且不重发() = runTest {
        val rig = Rig(testScheduler).apply { clockOffset = 22000 }
        rig.onSend = { delay(5000) }
        rejected { rig.run() }
        assertEquals(8000L, testScheduler.currentTime)
        assertEquals(2, rig.sends); assertEquals(1, rig.replies); assertEquals(1, rig.closes)
    }

    @Test fun 慢网已确认会话在目录后撤权仍不能调用工具() = runTest {
        val rig = Rig(testScheduler, "confirm").apply { cloudDelay = 600 }
        rig.onSend = {
            if (rig.methods.last() == "tools/list")
                rig.service = JsonObject(rig.service + ("revision" to JsonPrimitive(f.id(42))))
        }
        rejected { rig.run() }
        assertEquals(1, rig.asks); assertEquals(1, rig.decisions)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), rig.methods)
        assertEquals(2, rig.replies); assertEquals(1, rig.closes)
    }

    @Test fun 调用方取消传播到插件且关闭失败不替代原错误() = runTest {
        val rig = Rig(testScheduler)
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        rig.onSend = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val work = launch { rig.run() }
        entered.await(); work.cancelAndJoin(); cancelled.await()
        assertEquals(0, rig.replies); assertEquals(1, rig.closes)
        val closing = Rig(testScheduler).apply { failAt = "close" }
        closing.onTake = { throw CloudTaskFailure("network_timeout") }
        assertEquals("network_timeout", (rejected { closing.run() } as CloudTaskFailure).code)
        assertEquals(1, closing.closes)
    }

    @Test fun 每个SDK会话累计回执超过256KiB即停止不把超额观察上传() = runTest {
        val rig = Rig(testScheduler).apply { responseSize = 90000 }
        rejected { rig.run() }
        // 初始化和目录已回传；第三个非空响应将越过累计上限。
        assertEquals(4, rig.sends); assertEquals(3, rig.replies); assertEquals(1, rig.closes)
    }

    @Test fun 前台未开启或原回执已过期均不创建任何网络端口() = runTest {
        val rig = Rig(testScheduler)
        val host = rig.host().also { it.setForeground(false) }
        rejected { rig.run(host) }
        assertEquals(0, rig.takes); assertEquals(0, rig.sends); assertEquals(0, rig.closes)
        rig.receipt = rig.receipt.copy(deadlineAtMillis = 1000)
        rejected { rig.run(rig.host()) }
        assertEquals(0, rig.takes); assertEquals(0, rig.closes)
    }

    @Test fun 已批准意图不能为同ID的新参数或其他云任务放行() = runTest {
        val changed = Rig(testScheduler, "confirm")
        changed.onTake = { changed.wireIntent = f.invocation(changed.context, """{"query":"换一个操作"}""") }
        rejected { changed.run() }
        assertEquals(1, changed.decisions); assertEquals(0, changed.sends); assertEquals(1, changed.closes)
        val task = Rig(testScheduler)
        task.onTake = { task.receipt = task.receipt.copy(cloudTaskId = f.id(43)) }
        rejected { task.run() }
        assertEquals(0, task.sends); assertEquals(0, task.replies); assertEquals(1, task.closes)
    }
}
