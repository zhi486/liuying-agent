package com.healthyagent.running.agent.cloud

import androidx.room.withTransaction
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryConflict
import com.healthyagent.running.agent.memory.MemoryRecord
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** 只启动本次明确授权的工作；进程重启不扫描发送，恢复入口永不补发 POST。 */
class CloudAgentService(
    private val database: AgentDatabase,
    private val configuration: () -> CloudTaskIdentity?,
    private val api: (CloudTaskIdentity) -> CloudTaskApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val loadRunSummary: suspend (String) -> JsonObject? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val chatConsent: CloudChatConsent = CloudChatConsent({ null }, { error("持续授权存储不可用") }),
) {
    private data class Prepared(val intent: CloudTaskIntent, val memories: List<MemoryRecord>)
    private val gate = Mutex()
    private var prepared: Prepared? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val errors = ConcurrentHashMap<String, String>()
    private val requestedRecoveries = ConcurrentHashMap<String, CloudTaskIdentity>()
    private val configurationRequest = MutableStateFlow(false)
    val configuring = configurationRequest.asStateFlow()
    private val dao get() = database.cloudIntentDao()

    suspend fun clearPreparation() = gate.withLock { prepared = null }
    fun closeConfiguration() { configurationRequest.value = false }

    suspend fun handle(name: String, body: JsonObject): CommandOutcome = withContext(Dispatchers.IO) {
        try {
            CloudAgentCommands.validate(name, body)
            val data = gate.withLock {
                when (name) {
                    "cloud.configure" -> { prepared = null; configurationRequest.value = true; buildJsonObject { put("opened", true) } }
                    "cloud.list" -> listing()
                    "cloud.chatConsent" -> {
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        chatConsent.set(identity, CloudAgentCommands.text(body, "identityToken"), body.getValue("enabled").jsonPrimitive.boolean)
                        prepared = null
                        listing()
                    }
                    "cloud.newConversation", "cloud.selectConversation", "cloud.renameConversation" -> {
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        prepared = null
                        val conversations = database.conversationDao()
                        when (name) {
                            "cloud.newConversation" -> conversations.create(identity, now())
                            "cloud.selectConversation" -> conversations.select(identity,
                                body["conversationId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                            else -> conversations.rename(identity, CloudAgentCommands.text(body, "conversationId"), CloudAgentCommands.text(body, "title"), now())
                        }
                        listing()
                    }
                    "cloud.discard" -> { if (prepared?.intent?.clientRequestId == CloudAgentCommands.text(body, "token")) prepared = null; buildJsonObject { put("discarded", true) } }
                    "cloud.prepare" -> prepare(body)
                    "cloud.confirm" -> confirm(body)
                    "cloud.send" -> confirm(body, sustained = true)
                    else -> {
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        val id = CloudAgentCommands.text(body, "requestId")
                        dao.find(identity, id) ?: throw CloudTaskFailure("not_found")
                        if (name != "cloud.recover") dao.requestControl(identity, id, name.substringAfter('.'), now())
                        schedule(identity, id, false)
                        listing()
                    }
                }
            }
            CommandOutcome.AcceptedWithData(data)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MemoryConflict) { CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "记忆已修改或删除，请重新选择并确认；没有发送") }
        catch (_: CloudRunConflict) { CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "跑步记录已变化、删除或不可用，请重新选择；没有发送") }
        catch (_: IllegalArgumentException) { CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "本次输入或授权已失效，请重新核对") }
        catch (_: Exception) { CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "在线操作未确认完成，请刷新本机状态；不会自动重发") }
    }

    private suspend fun prepare(body: JsonObject): JsonObject {
        prepared = null
        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
        val refs = CloudAgentCommands.refs(body)
        val memories = if (refs.isEmpty()) emptyList() else database.memoryDao().selected("local", refs).items
        val prompt = CloudAgentCommands.text(body, "prompt")
        val base = if (refs.isEmpty()) CloudTaskIntent.confirm(identity, prompt, true, now()) else CloudTaskIntent.confirmSelected(identity, prompt, refs, now())
        val knowledge = body["knowledge"]?.let(CloudKnowledge::selection)
        val withKnowledge = if (knowledge == null) base else base.copy(consentVersion = "selected_knowledge_v1", knowledgeJson = knowledge.toString())
        val summary = if (body.containsKey("runId")) readRun(CloudAgentCommands.text(body, "runId")) else null
        val withSummary = if (summary == null) withKnowledge else withKnowledge.copy(consentVersion = "selected_run_v1", runSummaryJson = summary.toString())
        val conversationId = body["conversationId"]?.jsonPrimitive?.content
        val intent = if (conversationId == null) withSummary else withSummary.copy(
            conversationId = conversationId,
            conversationJson = conversationContext(identity, conversationId).toString(),
        )
        // 在展示授权前验证服务端支持的文本；预览不保存任务，也不访问网络。
        intent.submission(if (memories.isEmpty()) null else context(memories))
        prepared = Prepared(intent, memories)
        return buildJsonObject {
            put("token", intent.clientRequestId); put("origin", identity.origin); put("prompt", prompt)
            intent.knowledge()?.let { put("knowledge", it) }
            summary?.let { put("runSummary", it) }
            intent.conversation()?.let { put("conversation", it) }
            put("memories", JsonArray(memories.map { row -> buildJsonObject { put("id", row.id); put("version", row.version); put("content", row.content) } }))
        }
    }

    private suspend fun confirm(body: JsonObject, sustained: Boolean = false): JsonObject {
        val selected = prepared ?: throw IllegalArgumentException("授权已失效")
        require(selected.intent.clientRequestId == CloudAgentCommands.text(body, "token"))
        prepared = null
        require(configuration() == selected.intent.identity && now() - selected.intent.consentedAtMillis in 0..120_000)
        if (sustained) {
            require(chatConsent.enabled(selected.intent.identity)) { "持续授权未开启" }
            require(selected.intent.consentVersion == "prompt_only_v1" && selected.intent.memoryRefs().isEmpty() &&
                selected.intent.knowledge() == null && selected.intent.runSummary() == null && selected.intent.conversationId != null)
        }
        selected.intent.runSummary()?.let {
            if (readRun(CloudAgentCommands.text(it, "runId")) != it) throw CloudRunConflict()
        }
        val confirmedAt = now()
        val intent = selected.intent.copy(consentedAtMillis = confirmedAt, updatedAtMillis = confirmedAt)
        database.withTransaction {
            intent.conversationId?.let { id ->
                require(conversationContext(intent.identity, id) == intent.conversation()) { "会话已变化，请重新确认" }
            }
            if (selected.memories.isNotEmpty() && database.memoryDao().selected("local", intent.memoryRefs()).items != selected.memories) throw MemoryConflict()
            dao.save(intent)
            intent.conversationId?.let { id ->
                val conversation = requireNotNull(database.conversationDao().find(intent.identity, id))
                database.conversationDao().rename(intent.identity, id,
                    if (conversation.title == "新对话") intent.prompt.replace(Regex("\\s+"), " ").take(40) else conversation.title, confirmedAt)
            }
        }
        schedule(intent.identity, intent.clientRequestId, true)
        return listing()
    }

    @Synchronized private fun schedule(identity: CloudTaskIdentity, id: String, submit: Boolean) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val started = System.nanoTime()
            var stage = if (submit) "submit" else "recover"
            fun report(code: String) {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                // 不记录任务标识、请求地址、异常正文或密钥。
                android.util.Log.i("LiuyingCloud", "stage=$stage code=$code elapsedMs=$elapsedMs")
            }
            try {
                withTimeout(120_000) {
                    val sync = CloudTaskSync(dao, api(identity), now)
                    var receipt = if (submit) sync.submit(identity, id) else sync.recover(identity, id)
                    report("receipt_received")
                    stage = "poll"
                    // 正常执行期间有界查询回执；一次网络失败即停止，不自动重试。
                    repeat(15) {
                        if (receipt?.status !in setOf("queued", "running")) return@withTimeout
                        delay(2000)
                        receipt = sync.recover(identity, id)
                    }
                    if (receipt?.status in setOf("queued", "running")) {
                        errors[id] = CloudTaskDiagnostics.message("poll_exhausted")
                        report("poll_exhausted")
                    }
                }
                report("finished")
            } catch (_: TimeoutCancellationException) { errors[id] = CloudTaskDiagnostics.message("deadline"); report("deadline") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CloudTaskFailure) { errors[id] = CloudTaskDiagnostics.message(failure.code); report(failure.code) }
            catch (_: Exception) { errors[id] = CloudTaskDiagnostics.message("unknown"); report("unknown") }
        }
        if (jobs.putIfAbsent(id, job) == null) {
            errors.remove(id)
            job.invokeOnCompletion {
                synchronized(this@CloudAgentService) {
                    jobs.remove(id, job)
                    requestedRecoveries.remove(id)?.let { schedule(it, id, false) }
                }
            }
            job.start()
        } else {
            if (!submit) requestedRecoveries[id] = identity
            job.cancel()
        }
    }

    private suspend fun listing(): JsonObject {
        val identity = configuration()
        val conversations = if (identity == null) emptyList() else database.conversationDao().list(identity)
        val active = conversations.singleOrNull { it.selected }?.id
        return buildJsonObject {
            put("configured", identity != null); put("configuring", configurationRequest.value)
            put("origin", identity?.origin?.let(::JsonPrimitive) ?: JsonNull)
            put("chatAuthorized", identity?.let(chatConsent::enabled) ?: false)
            put("chatConsentContext", identity?.let { JsonPrimitive(CloudChatConsent.context(it)) } ?: JsonNull)
            put("conversationId", active?.let(::JsonPrimitive) ?: JsonNull)
            put("conversations", JsonArray(conversations.map { row -> buildJsonObject {
                put("id", row.id); put("title", row.title); put("updatedAtMillis", row.updatedAtMillis)
            } }))
            // 按实际 JSON 字节限制总量，控制字符转义也计入；保留完整单条回答。
            put("items", JsonArray(if (identity == null) emptyList() else dao.conversationPage(identity, active).map { intent ->
                val receipt = dao.receipt(identity, intent.clientRequestId)
                val result = receipt?.resultJson?.let { Json.parseToJsonElement(it).jsonObject }
                buildJsonObject {
                    put("requestId", intent.clientRequestId); put("prompt", intent.prompt)
                    put("busy", jobs.containsKey(intent.clientRequestId)); put("attempted", intent.submissionAttempted)
                    put("status", receipt?.status ?: "unconfirmed"); put("resultState", receipt?.resultState ?: "unavailable")
                    put("reason", receipt?.reason?.let(::JsonPrimitive) ?: JsonNull)
                    put("answer", if (intent.pendingControl == "withdraw") JsonNull else result?.get("answer") ?: JsonNull)
                    put("pendingControl", intent.pendingControl?.let(::JsonPrimitive) ?: JsonNull)
                    put("confirmedControl", receipt?.confirmedControl?.let(::JsonPrimitive) ?: JsonNull)
                    put("error", errors[intent.clientRequestId]?.let(::JsonPrimitive) ?: JsonNull)
                    put("memoryRefs", intent.memoryRefsJson?.let(Json::parseToJsonElement) ?: JsonArray(emptyList()))
                    intent.knowledge()?.let { put("knowledge", it) }
                    if (intent.pendingControl != "withdraw") intent.runSummary()?.let { put("runSummary", it) }
                    if (intent.pendingControl != "withdraw") result?.get("sources")?.let { put("sources", CloudKnowledge.sources(it)) }
                    if (intent.pendingControl != "withdraw") result?.get("cost")?.let { put("cost", CloudCostEstimate.validate(it)) }
                }
            }.let { rows ->
                var bytes = 0
                rows.takeWhile { row -> bytes += row.toString().toByteArray(Charsets.UTF_8).size; bytes <= 220_000 }
            }))
        }
    }

    /** 只从当前身份、当前会话的原生回执取完整往返，不接受 Web 自带历史文本。 */
    private suspend fun conversationContext(identity: CloudTaskIdentity, id: String): JsonObject {
        require(database.conversationDao().find(identity, id)?.selected == true) { "会话已切换" }
        val rows = dao.conversationPage(identity, id)
        val pairs = mutableListOf<List<JsonObject>>()
        var chars = 0
        for (intent in rows) {
            val receipt = dao.receipt(identity, intent.clientRequestId)
            require(receipt != null && receipt.status !in setOf("queued", "running") && !jobs.containsKey(intent.clientRequestId)) { "请先等待或恢复上一条消息" }
            if (intent.pendingControl != null || receipt.status != "completed" || receipt.resultJson == null) continue
            // 已删／已改的选中记忆不通过历史回答重新带入。
            if (intent.memoryRefs().any { ref ->
                try { database.memoryDao().selected("local", listOf(ref)); false } catch (_: MemoryConflict) { true }
            }) continue
            val answer = Json.parseToJsonElement(receipt.resultJson).jsonObject["answer"]?.jsonPrimitive?.content ?: continue
            if (pairs.size == 4 || chars + intent.prompt.length + answer.length > 12000) break
            chars += intent.prompt.length + answer.length
            pairs += listOf(buildJsonObject { put("role", "user"); put("text", intent.prompt) },
                buildJsonObject { put("role", "assistant"); put("text", answer) })
        }
        return buildJsonObject {
            put("version", "conversation_v1"); put("id", id); put("approved", true)
            put("messages", JsonArray(pairs.asReversed().flatten()))
        }
    }

    private suspend fun readRun(id: String): JsonObject {
        val summary = loadRunSummary(id) ?: throw CloudRunConflict()
        CloudRunSummary.validate(summary)
        if (CloudAgentCommands.text(summary, "runId") != id) throw CloudRunConflict()
        return summary
    }

    private fun context(memories: List<MemoryRecord>) = buildJsonObject {
        put("version", "selected_memories_v1"); put("approved", true)
        put("items", JsonArray(memories.map { row -> buildJsonObject { put("id", row.id); put("version", row.version); put("content", row.content) } }))
    }
}
