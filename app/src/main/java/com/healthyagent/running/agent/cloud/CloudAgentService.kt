package com.healthyagent.running.agent.cloud

import androidx.room.withTransaction
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryConflict
import com.healthyagent.running.agent.memory.MemoryRecord
import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryContextState
import com.healthyagent.running.agent.memory.MemoryRef
import com.healthyagent.running.agent.memory.MemoryToolBinding
import com.healthyagent.running.agent.memory.MemoryToolCall
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.agent.memory.MemoryToolExecutor
import com.healthyagent.running.agent.memory.MemoryToolGrant
import com.healthyagent.running.agent.memory.MemoryToolPolicy
import com.healthyagent.running.agent.memory.MemoryToolResult
import com.healthyagent.running.agent.memory.MemoryToolTurn
import com.healthyagent.running.agent.memory.MemoryToolWire
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val chatConsent: CloudChatConsent = CloudChatConsent({ "{}" }, { error("持续授权存储不可用") }),
    private val memoryConsent: MemoryToolConsent = MemoryToolConsent({ null }, { error("记忆授权存储不可用") }),
    private val mcpRunner: com.healthyagent.running.agent.mcp.McpTaskRunner? = null,
    private val chatSpeech: com.healthyagent.running.speech.TtsEngine? = null,
    private val speechAllowed: () -> Boolean = { false },
) {
    private data class ConversationContext(val value: JsonObject, val dependencies: MemoryContextState)
    private data class Prepared(val intent: CloudTaskIntent, val memories: List<MemoryRecord>, val memoryGrant: MemoryToolGrant?,
        val dependencies: MemoryContextState, val mcp: com.healthyagent.running.agent.mcp.McpTaskSelection? = null)
    private val gate = Mutex()
    private val summaryProcessId = java.util.UUID.randomUUID().toString()
    private var speechJob: Job? = null
    private var speechRequestId: String? = null
    @Volatile private var speechState = "idle"
    @Volatile private var speechError: String? = null

    fun stopSpeech() { speechJob?.cancel() }
    private var prepared: Prepared? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val errors = ConcurrentHashMap<String, String>()
    private val requestedRecoveries = ConcurrentHashMap<String, CloudTaskIdentity>()
    private class StreamSlot(val identity: CloudTaskIdentity, val state: CloudTaskStream) { @Volatile var visible = true }
    private val streams = ConcurrentHashMap<String, StreamSlot>()
    private val streamJobs = ConcurrentHashMap<String, Job>()
    private val cleanupJobs = ConcurrentHashMap<CloudTaskIdentity, Job>()
    private val changeCounter = MutableStateFlow(0L)
    val changes = changeCounter.asStateFlow()
    private fun changed() { changeCounter.update { it + 1 } }
    private val configurationRequest = MutableStateFlow(false)
    val configuring = configurationRequest.asStateFlow()
    private val dao get() = database.cloudIntentDao()
    private val lifecycle get() = database.conversationLifecycleDao()

    suspend fun clearPreparation() = gate.withLock { prepared = null }
    fun closeConfiguration() { configurationRequest.value = false }
    @Volatile private var foreground = false
    fun setMcpForeground(value: Boolean) { foreground = value; mcpRunner?.setForeground(value); if (!value) { stopSpeech(); closeStreams() } }
    fun closeMcpWindows() { mcpRunner?.closeWindows() }
    fun closeStreams() { streamJobs.values.forEach { it.cancel() }; streams.clear(); changed() }
    private fun closeStream(id: String) { streamJobs[id]?.cancel(); streams.remove(id); changed() }

    suspend fun handle(name: String, body: JsonObject): CommandOutcome = withContext(Dispatchers.IO) {
        try {
            CloudAgentCommands.validate(name, body)
            val data = gate.withLock {
                when (name) {
                    "cloud.configure" -> { stopSpeech(); closeStreams(); prepared = null; configurationRequest.value = true; buildJsonObject { put("opened", true) } }
                    "cloud.list" -> listing()
                    "cloud.stopSpeech" -> { stopSpeech(); listing() }
                    "cloud.speak" -> {
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        val id = CloudAgentCommands.text(body, "requestId")
                        val intent = dao.find(identity, id) ?: throw CloudTaskFailure("not_found")
                        val receipt = dao.receipt(identity, id) ?: throw IllegalArgumentException()
                        require(foreground && speechAllowed() && chatSpeech != null)
                        require(intent.pendingControl == null && receipt.confirmedControl != "withdraw" && receipt.resultState != "withdrawn")
                        require(intent.conversationId == database.conversationDao().list(identity).singleOrNull { it.selected }?.id)
                        val text = receipt.resultJson?.let { Json.parseToJsonElement(it).jsonObject["answer"]?.jsonPrimitive?.content }
                        require(receipt.status == "completed" && !text.isNullOrBlank())
                        speechJob?.cancelAndJoin()
                        require(!chatSpeech.isSpeaking)
                        speechRequestId = id; speechState = "preparing"; speechError = null
                        speechJob = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val result = chatSpeech.speak(com.healthyagent.running.speech.SpeechRequest(
                                    com.healthyagent.running.core.model.RuleTrigger(
                                        "chat_answer", com.healthyagent.running.core.model.Severity.INFO, text,
                                        com.healthyagent.running.core.model.SpeechPriority.APP),
                                    noticeToken = com.healthyagent.running.speech.SpeechNoticeToken { foreground && speechAllowed() && configuration() == identity }))
                                if (result.isFailure || result.getOrNull()?.played != true) {
                                    speechError = "语音播放失败，请检查语音配置或网络后重试。"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { speechError = "语音播放失败，请稍后重试。" }
                            finally { speechState = "idle"; changed() }
                        }.also { it.start() }
                        listing()
                    }
                    "cloud.memoryAuthorization" -> memoryAuthorization()
                    "cloud.memoryConsent" -> {
                        stopSpeech()
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        memoryConsent.set(identity, CloudAgentCommands.text(body, "identityToken"), body.getValue("enabled").jsonPrimitive.boolean,
                            body["factsAllowed"]?.jsonPrimitive?.boolean ?: false)
                        closeStreams()
                        prepared = null
                        memoryAuthorization()
                    }
                    "cloud.chatConsent" -> {
                        stopSpeech()
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        chatConsent.set(identity, CloudAgentCommands.text(body, "identityToken"), body.getValue("enabled").jsonPrimitive.boolean)
                        closeStreams()
                        prepared = null
                        listing()
                    }
                    "cloud.newConversation", "cloud.selectConversation", "cloud.renameConversation" -> {
                        stopSpeech()
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        closeStreams()
                        prepared = null
                        val conversations = database.conversationDao()
                        when (name) {
                            "cloud.newConversation" -> conversations.create(identity, now())
                            "cloud.selectConversation" -> conversations.select(identity,
                                body["conversationId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content).also {
                                    if (body["conversationId"] == JsonNull) lifecycle.selectLegacy(identity, true)
                                }
                            else -> conversations.rename(identity, CloudAgentCommands.text(body, "conversationId"), CloudAgentCommands.text(body, "title"), now())
                        }
                        listing()
                    }
                    "cloud.archiveConversation", "cloud.restoreConversation", "cloud.deleteConversation" -> {
                        stopSpeech()
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        val id = body["conversationId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                        prepared = null
                        closeStreams()
                        if (name == "cloud.deleteConversation") {
                            val deleted = lifecycle.delete(identity, id, now())
                            // 先提交本机事务，再终止对应窗口；取消不等待持有 gate 的流式回调。
                            deleted.forEach { row ->
                                requestedRecoveries.remove(row.requestId)
                                jobs[row.requestId]?.cancel()
                                closeStream(row.requestId)
                                errors.remove(row.requestId)
                            }
                            // 只做非阻塞检查点；空闲页由 SQLite 复用，不关闭共享库或强制 VACUUM。
                            runCatching { database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() } }
                            startCleanup(identity)
                        } else lifecycle.archive(identity, id, name == "cloud.archiveConversation", now())
                        listing()
                    }
                    "cloud.retryConversationCleanup" -> {
                        startCleanup(configuration() ?: throw CloudTaskFailure("credential_unavailable"))
                        listing()
                    }
                    "cloud.discard" -> { if (prepared?.intent?.clientRequestId == CloudAgentCommands.text(body, "token")) prepared = null; buildJsonObject { put("discarded", true) } }
                    "cloud.prepare" -> prepare(body)
                    "cloud.confirm" -> confirm(body)
                    "cloud.send" -> confirm(body, sustained = true)
                    else -> {
                        val identity = configuration() ?: throw CloudTaskFailure("credential_unavailable")
                        val id = CloudAgentCommands.text(body, "requestId")
                        val intent = dao.find(identity, id) ?: throw CloudTaskFailure("not_found")
                        require(if (intent.conversationId == null) !lifecycle.legacyArchived(identity)
                            else database.conversationDao().find(identity, intent.conversationId)?.archivedAtMillis == null)
                        if (name != "cloud.recover") { stopSpeech(); closeStream(id); dao.requestControl(identity, id, name.substringAfter('.'), now()) }
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

    /** 删除只投递无正文原键；断网或旧后端不伪报清理成功，不在启动时自动发送。 */
    @Synchronized private fun startCleanup(identity: CloudTaskIdentity) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(120_000) {
                    for (row in lifecycle.pending(identity).take(50)) {
                        if (configuration() != identity) break
                        api(identity).purge(identity, row.requestId)
                        lifecycle.complete(identity, row.requestId)
                        changed()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 无正文待办保留，由用户在管理页明确重试。 */ }
        }
        if (cleanupJobs.putIfAbsent(identity, job) == null) {
            job.invokeOnCompletion { cleanupJobs.remove(identity, job); changed() }
            job.start()
        } else job.cancel()
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
        val history = conversationId?.let { database.withTransaction { conversationContext(identity, it) } }
        val withConversation = if (conversationId == null) withSummary else withSummary.copy(
            conversationId = conversationId,
            conversationJson = requireNotNull(history).value.toString(),
        )
        val plugins = body["mcpServices"]?.jsonArray?.map { it.jsonPrimitive.content }?.let {
            requireNotNull(mcpRunner).selection(identity, it)
        }
        require(configuration() == identity)
        val intent = withConversation.copy(mcpToolsJson = plugins?.configuration?.wire()?.toString())
        // 预览不保存任务；选择插件时只读云目录，不向模型或插件发送问题。
        intent.submission(if (memories.isEmpty()) null else context(memories))
        prepared = Prepared(intent, memories, if (conversationId == null) null else memoryConsent.current(identity),
            history?.dependencies ?: MemoryContextState(), plugins)
        return buildJsonObject {
            put("token", intent.clientRequestId); put("origin", identity.origin); put("prompt", prompt)
            intent.knowledge()?.let { put("knowledge", it) }
            summary?.let { put("runSummary", it) }
            intent.conversation()?.let { put("conversation", it) }
            plugins?.let { put("mcpServices", it.preview()) }
            put("memories", JsonArray(memories.map { row -> buildJsonObject { put("id", row.id); put("version", row.version); put("content", row.content) } }))
        }
    }

    private suspend fun confirm(body: JsonObject, sustained: Boolean = false): JsonObject {
        val selected = prepared ?: throw IllegalArgumentException("授权已失效")
        require(selected.intent.clientRequestId == CloudAgentCommands.text(body, "token"))
        prepared = null
        require(configuration() == selected.intent.identity && now() - selected.intent.consentedAtMillis in 0..120_000)
        if (selected.intent.conversationId != null) require(memoryConsent.current(selected.intent.identity) == selected.memoryGrant)
        if (sustained) {
            require(chatConsent.enabled(selected.intent.identity)) { "持续授权未开启" }
            require(selected.intent.consentVersion in setOf("prompt_only_v1", "selected_knowledge_v1") && selected.intent.memoryRefs().isEmpty() &&
                selected.intent.runSummary() == null &&
                selected.intent.mcpToolsJson == null && selected.intent.conversationId != null)
        }
        selected.mcp?.let { snapshot ->
            val ids = snapshot.configuration.services.map { it.getValue("serviceId").jsonPrimitive.content }
            require(snapshot.matches(requireNotNull(mcpRunner).selection(selected.intent.identity, ids))) { "插件权限已变化，请重新核对" }
        }
        require(configuration() == selected.intent.identity)
        selected.intent.runSummary()?.let {
            if (readRun(CloudAgentCommands.text(it, "runId")) != it) throw CloudRunConflict()
        }
        val confirmedAt = now()
        val intent = selected.intent.copy(consentedAtMillis = confirmedAt, updatedAtMillis = confirmedAt)
        database.withTransaction {
            intent.conversationId?.let { id ->
                val current = conversationContext(intent.identity, id)
                require(current.value == intent.conversation() && current.dependencies == selected.dependencies) { "会话已变化，请重新确认" }
            }
            if (selected.memories.isNotEmpty() && database.memoryDao().selected("local", intent.memoryRefs()).items != selected.memories) throw MemoryConflict()
            dao.save(intent)
            if (intent.conversationId != null) {
                val dependencies = selected.dependencies.merge(intent.memoryRefs())
                if (dependencies.exhausted) throw MemoryConflict()
                database.memoryContextDependenciesDao().save(dependencies.row(intent.clientRequestId, freezeBaseline = true))
            }
            selected.memoryGrant?.let { database.memoryToolBindingDao().insert(MemoryToolBinding.capture(intent, it)) }
            intent.conversationId?.let { id ->
                val conversation = requireNotNull(database.conversationDao().find(intent.identity, id))
                database.conversationDao().rename(intent.identity, id,
                    if (conversation.title == "新对话") intent.prompt.replace(Regex("\\s+"), " ").take(40) else conversation.title, confirmedAt)
            }
        }
        schedule(intent.identity, intent.clientRequestId, true)
        return listing()
    }

    private fun memoryAuthorization(): JsonObject {
        val identity = configuration()
        return buildJsonObject {
            put("configured", identity != null)
            put("enabled", identity?.let { memoryConsent.current(it) != null } ?: false)
            put("factsAllowed", identity?.let { memoryConsent.current(it)?.factsAllowed } ?: false)
            put("context", identity?.let { JsonPrimitive(memoryConsent.context(it)) } ?: JsonNull)
        }
    }

    /** 仅供原生云工具往返使用，不注册到 Web Bridge；来源从原生任务读取。 */
    suspend fun executeMemoryTool(identity: CloudTaskIdentity, requestId: String, callId: String,
        name: String, args: JsonObject): MemoryToolResult = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                MemoryCommands.id(requestId)
                database.withTransaction {
                    if (configuration() != identity) return@withTransaction MemoryToolResult.Rejected("scope_denied")
                    val intent = dao.find(identity, requestId) ?: return@withTransaction MemoryToolResult.Rejected("scope_denied")
                    val binding = database.memoryToolBindingDao().find(requestId)
                        ?: return@withTransaction MemoryToolResult.Rejected("consent_required")
                    if (!binding.matches(intent)) return@withTransaction MemoryToolResult.Rejected("scope_denied")
                    val receipt = dao.receipt(identity, requestId)
                    if (!intent.submissionAttempted || intent.pendingControl != null ||
                        now() - intent.consentedAtMillis !in 0..120_000 ||
                        receipt?.let { it.status !in setOf("queued", "running") || it.deadlineAtMillis <= now() } == true)
                        return@withTransaction MemoryToolResult.Rejected("task_inactive")
                    val turn = MemoryToolTurn("local", binding.scope, binding.grantGeneration,
                        intent.clientRequestId, intent.consentedAtMillis, intent.prompt, binding.factsAllowed)
                    MemoryToolExecutor(database.memoryDao(), now = now, readGrant = {
                        if (configuration() == identity) memoryConsent.current(identity) else null
                    }).execute(turn, callId, name, args).also { result ->
                        val refs = when (result) {
                            is MemoryToolResult.Found -> result.items.map { MemoryRef(it.id, it.version) }
                            is MemoryToolResult.Saved -> listOf(MemoryRef(result.memory.id, result.memory.version))
                            is MemoryToolResult.Rejected -> emptyList()
                        }
                        if (refs.isNotEmpty()) {
                            val dependencies = database.memoryContextDependenciesDao()
                            val current = dependencies.find(requestId)
                                ?: MemoryContextState(exhausted = intent.conversation()?.get("messages")?.jsonArray?.isNotEmpty() == true).row(requestId)
                            val target = if (result is MemoryToolResult.Saved)
                                (MemoryToolPolicy.decode(name, args) as? MemoryToolCall.Upsert)?.target else null
                            dependencies.save(current.mergeToolReferences(refs, target))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { MemoryToolResult.Rejected("invalid_tool") }
            catch (_: Exception) { MemoryToolResult.Rejected("storage_unavailable") }
        }
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
                    val client = api(identity)
                    val sync = CloudTaskSync(dao, client, now, memoryBinding = { intent ->
                        database.memoryToolBindingDao().find(intent.clientRequestId)?.takeIf { it.matches(intent) }
                    })
                    var receipt = if (submit) sync.submit(identity, id) else sync.recover(identity, id)
                    report("receipt_received")
                    stage = "poll"
                    // 只在首次明确发送时启动插件窗口；恢复查询不能重建或领取旧交换。
                    val submitted = if (submit && receipt?.status in setOf("queued", "running")) dao.find(identity, id) else null
                    val accepted = receipt
                    val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
                    val streamJob = if (client.supportsStreaming && foreground && receipt?.status in setOf("queued", "running")) launch {
                        var firstTextReported = false
                        val view = requireNotNull(accepted)
                        val slot = streams[id]?.takeIf { it.identity == identity && it.state.taskId == view.cloudTaskId && !it.state.ended }
                            ?: StreamSlot(identity, CloudTaskStream(view.cloudTaskId)).also {
                                // 仅保存少量当前进程的恢复快照，不建立第二套正式聊天数据库。
                                if (streams.size >= 4) streams.clear()
                                streams[id] = it
                            }
                        slot.visible = true
                        try {
                            client.events(identity, view.cloudTaskId, slot.state.cursor) { event ->
                                gate.withLock {
                                    currentCoroutineContext().ensureActive()
                                    val latest = dao.find(identity, id)
                                    if (!foreground || configuration() != identity || streams[id] !== slot ||
                                        latest?.pendingControl != null || latest == null || now() >= view.deadlineAtMillis)
                                        throw CancellationException("流式订阅已失效")
                                    slot.state.accept(event); changed()
                                    if (event.kind == "text_delta" && !firstTextReported) {
                                        firstTextReported = true
                                        report("first_stream_text")
                                    }
                                    if (event.kind == "round_end" && event.outcome == "tools") wake.trySend(Unit)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            // 旧后端404或断流只降级查询原任务，不重发POST、不自动重连。
                            slot.visible = false; changed()
                        } finally { wake.trySend(Unit) }
                    } else null
                    if (streamJob != null) streamJobs[id] = streamJob
                    val mcpJob = if (submitted?.mcpToolsJson != null && mcpRunner != null) launch {
                        try {
                            mcpRunner.run(submitted, requireNotNull(accepted), dispatch = { send ->
                                // 派发与删除共用锁，锁只覆盖最后本机查权和 enqueue，不等待网络。
                                gate.withLock {
                                    currentCoroutineContext().ensureActive()
                                    val latest = dao.find(identity, id)
                                    val view = dao.receipt(identity, id)
                                    if (configuration() != identity || !foreground || latest == null ||
                                        latest.pendingControl != null || view?.cloudTaskId != accepted.cloudTaskId ||
                                        view.status !in setOf("queued", "running") || view.confirmedControl != null || now() >= view.deadlineAtMillis)
                                        throw com.healthyagent.running.agent.mcp.McpTransportFailure("stale_configuration")
                                    send()
                                }
                            }) { refresh ->
                                if (refresh) sync.recover(identity, id)
                                val latest = dao.find(identity, id)
                                val view = dao.receipt(identity, id)
                                if (latest == null || view == null) null else latest to view
                            }
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            report("mcp_window_closed")
                        } catch (_: Exception) { report("mcp_window_closed") }
                    } else null
                    try {
                        // 正常执行期间有界查询回执；一次网络失败即停止，不自动重试。
                        repeat(15) {
                            if (receipt?.status !in setOf("queued", "running")) return@withTimeout
                            receipt?.let { processMemoryTool(identity, id, it, client) }
                            withTimeoutOrNull(2000) { wake.receive() }
                            receipt = sync.recover(identity, id)
                            changed()
                        }
                        if (receipt?.status in setOf("queued", "running")) {
                            errors[id] = CloudTaskDiagnostics.message("poll_exhausted")
                            report("poll_exhausted")
                        }
                    } finally {
                        mcpJob?.cancel(); streamJob?.cancelAndJoin()
                        if (streamJob != null) streamJobs.remove(id, streamJob)
                        wake.close()
                    }
                }
                report("finished")
            } catch (_: TimeoutCancellationException) { errors[id] = CloudTaskDiagnostics.message("deadline"); report("deadline") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CloudTaskFailure) { errors[id] = CloudTaskDiagnostics.message(failure.code); report(failure.code) }
            catch (_: Exception) { errors[id] = CloudTaskDiagnostics.message("unknown"); report("unknown") }
            finally {
                if (dao.find(identity, id) == null) errors.remove(id)
                // 已完成任务的最后草稿保留到正式回答可用，避免流终态与回执落盘之间闪空。
                streams[id]?.let { if (!(it.state.ended && it.state.draft() != null)) it.visible = false }
                changed()
            }
        }
        if (jobs.putIfAbsent(id, job) == null) {
            errors.remove(id)
            job.invokeOnCompletion {
                synchronized(this@CloudAgentService) {
                    jobs.remove(id, job)
                    changed()
                    requestedRecoveries.remove(id)?.let { schedule(it, id, false) }
                }
            }
            job.start()
        } else {
            if (!submit) requestedRecoveries[id] = identity
            job.cancel()
        }
    }

    /** 仅处理显式发送或恢复的原任务；不扫描任务，不因工具失败重发问题。 */
    private suspend fun processMemoryTool(identity: CloudTaskIdentity, requestId: String, receipt: CloudTaskReceipt, client: CloudTaskApi) {
        val intent = dao.find(identity, requestId) ?: throw CloudTaskFailure("not_found")
        val binding = database.memoryToolBindingDao().find(requestId) ?: return
        if (!binding.matches(intent) || intent.pendingControl != null) return
        try {
            val tool = client.memory(identity, receipt.cloudTaskId) ?: return
            if (tool.taskId != receipt.cloudTaskId || tool.requestId != requestId || tool.generation != binding.grantGeneration)
                throw CloudTaskFailure("invalid_response")
            val result = executeMemoryTool(identity, requestId, tool.callId, tool.name, tool.args)
            val observation = MemoryToolWire.encode(result)
            android.util.Log.i("LiuyingCloud", "stage=memory_tool_local code=${CloudTaskDiagnostics.memoryToolCode(observation)}")
            client.replyMemory(identity, tool, observation)
        } catch (failure: CloudTaskFailure) {
            // 正常完成或取消可与轮询交错；随后仍只查询原任务回执。
            if (failure.code != "inactive") throw failure
        }
    }

    private suspend fun listing(): JsonObject {
        val identity = configuration()
        val conversations = if (identity == null) emptyList() else database.conversationDao().list(identity)
        val active = conversations.singleOrNull { it.selected }?.id
        val legacySelected = identity?.let { lifecycle.legacySelected(it) } ?: false
        val legacyArchived = identity?.let { lifecycle.legacyArchived(it) } ?: false
        val retained = if (identity == null) emptyList() else lifecycle.retainedUsage(identity)
        return buildJsonObject {
            put("configured", identity != null); put("configuring", configurationRequest.value)
            put("speech", buildJsonObject {
                put("requestId", speechRequestId?.let(::JsonPrimitive) ?: JsonNull)
                put("state", if (speechState != "idle" && chatSpeech?.isSpeaking == true) "playing" else speechState)
                put("error", speechError?.let(::JsonPrimitive) ?: JsonNull)
                put("available", foreground && speechAllowed())
            })
            put("origin", identity?.origin?.let(::JsonPrimitive) ?: JsonNull)
            put("chatAuthorized", identity?.let(chatConsent::enabled) ?: false)
            put("chatConsentContext", identity?.let { JsonPrimitive(CloudChatConsent.context(it)) } ?: JsonNull)
            put("conversationId", active?.let(::JsonPrimitive) ?: JsonNull)
            put("conversationArchived", conversations.singleOrNull { it.selected }?.let { it.archivedAtMillis != null }
                ?: (legacySelected && legacyArchived))
            put("legacyAvailable", identity?.let { lifecycle.legacyAvailable(it) } ?: false)
            put("legacyArchived", legacyArchived)
            put("cleanupPending", identity?.let { lifecycle.pending(it).size } ?: 0)
            put("cleanupBusy", identity != null && cleanupJobs.containsKey(identity))
            put("retainedUsage", JsonArray(retained.map { row -> buildJsonObject {
                put("requestId", row.requestId); put("atMillis", row.atMillis)
                put("cost", row.costJson?.let(Json::parseToJsonElement)?.let(CloudCostEstimate::validate) ?: JsonNull)
            } }))
            put("conversations", JsonArray(conversations.map { row -> buildJsonObject {
                put("id", row.id); put("title", row.title); put("updatedAtMillis", row.updatedAtMillis)
                put("archived", row.archivedAtMillis != null)
            } }))
            // 按实际 JSON 字节限制总量，控制字符转义也计入；保留完整单条回答。
            put("items", JsonArray(if (identity == null || (active == null && !legacySelected)) emptyList() else dao.conversationPage(identity, active).map { intent ->
                val receipt = dao.receipt(identity, intent.clientRequestId)
                val result = receipt?.resultJson?.let { Json.parseToJsonElement(it).jsonObject }
                buildJsonObject {
                    put("requestId", intent.clientRequestId); put("prompt", intent.prompt)
                    put("busy", jobs.containsKey(intent.clientRequestId)); put("attempted", intent.submissionAttempted)
                    put("status", receipt?.status ?: "unconfirmed"); put("resultState", receipt?.resultState ?: "unavailable")
                    put("reason", receipt?.reason?.let(::JsonPrimitive) ?: JsonNull)
                    put("answer", if (intent.pendingControl == "withdraw") JsonNull else result?.get("answer") ?: JsonNull)
                    streamDraft(intent, receipt)?.let { put("draftAnswer", it) }
                    put("pendingControl", intent.pendingControl?.let(::JsonPrimitive) ?: JsonNull)
                    put("confirmedControl", receipt?.confirmedControl?.let(::JsonPrimitive) ?: JsonNull)
                    put("error", errors[intent.clientRequestId]?.let(::JsonPrimitive) ?: JsonNull)
                    put("memoryRefs", intent.memoryRefsJson?.let(Json::parseToJsonElement) ?: JsonArray(emptyList()))
                    // 只根据仍有效的原生保存回执提示，不采信模型的回答或动作声明。
                    if (intent.pendingControl != "withdraw" && receipt?.confirmedControl != "withdraw" &&
                        receipt?.resultState != "withdrawn" &&
                        database.memoryToolBindingDao().find(intent.clientRequestId)?.matches(intent) == true &&
                        database.memoryDao().hasCurrentToolSave("local", intent.clientRequestId)) {
                        put("memorySaved", true)
                    }
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

    /** 每次交给页面前重核本机权限及记忆来源；临时正文不参与上下文。 */
    private suspend fun streamDraft(intent: CloudTaskIntent, receipt: CloudTaskReceipt?): String? {
        val slot = streams[intent.clientRequestId] ?: return null
        if (!slot.visible || !foreground || configuration() != slot.identity || slot.identity != intent.identity || receipt == null ||
            receipt.status !in setOf("queued", "running") || intent.pendingControl != null || now() >= receipt.deadlineAtMillis) {
            if (slot.state.ended) streams.remove(intent.clientRequestId, slot)
            return null
        }
        val binding = database.memoryToolBindingDao().find(intent.clientRequestId)
        if (binding != null && (!binding.matches(intent) || memoryConsent.current(intent.identity)?.let {
                it.generation == binding.grantGeneration && it.factsAllowed == binding.factsAllowed
            } != true)) return null
        val tracked = database.memoryContextDependenciesDao().find(intent.clientRequestId)?.state()
        if (tracked?.exhausted == true) return null
        for (ref in (tracked?.refs ?: emptySet()) + intent.memoryRefs()) {
            try { database.memoryDao().selected("local", listOf(ref)) } catch (_: MemoryConflict) { return null }
        }
        return slot.state.draft()
    }

    /** 只从当前身份、当前会话的原生回执取完整往返，不接受 Web 自带历史文本。 */
    private suspend fun conversationContext(identity: CloudTaskIdentity, id: String): ConversationContext {
        val pairs = mutableListOf<List<JsonObject>>()
        var chars = 0
        var dependencies = MemoryContextState()
        validConversationTurns(identity, id) { intent, answer, refs ->
            if (pairs.size == 4 || chars + intent.prompt.length + answer.length > 12000) false else {
                val combined = dependencies.merge(refs)
                if (!combined.exhausted) {
                    chars += intent.prompt.length + answer.length
                    dependencies = combined
                    pairs += listOf(buildJsonObject { put("role", "user"); put("text", intent.prompt) },
                        buildJsonObject { put("role", "assistant"); put("text", answer) })
                }
                true
            }
        }
        return ConversationContext(buildJsonObject {
            put("version", "conversation_v1"); put("id", id); put("approved", true)
            put("messages", JsonArray(pairs.asReversed().flatten()))
        }, dependencies)
    }

    /** 仅构造本地候选，不挂接发送路径；每次调用重新核对数据库与授权。 */
    internal suspend fun summaryCandidate(id: String): ConversationSummaryPolicy.Plan? = gate.withLock { buildSummaryCandidate(id) }

    /** 仅内部合成接线；候选由宿主复核，不向 Web 或默认聊天开放。 */
    internal suspend fun prepareSummaryRequest(id: String, requestId: String): Boolean = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction false
            val current = buildSummaryCandidate(id)?.candidate ?: return@withTransaction false
            database.conversationSummaryRequestDao().prepare(identity, id, requestId, current, now())
        }
    }

    internal suspend fun nextSummaryRequest(id: String, requestId: String, revision: Long): ConversationSummaryProtocol.Step? = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction null
            val dao = database.conversationSummaryRequestDao()
            if (dao.find(requestId)?.conversationId != id) return@withTransaction null
            dao.next(identity, requestId, revision, buildSummaryCandidate(id)?.candidate)
        }
    }

    /** 领取与发送上下文在同一事务内绑定；网络调用必须在事务外执行。 */
    internal suspend fun summaryRecoveryRows(): List<ConversationSummaryRequest> = gate.withLock {
        val identity = configuration() ?: return@withLock emptyList()
        database.conversationSummaryRequestDao().recoverable(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef)
    }

    internal suspend fun claimSummaryRecovery(row: ConversationSummaryRequest): ConversationSummaryExecutor.Dispatch? = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction null
            if (identity != row.identity) return@withTransaction null
            val dao = database.conversationSummaryRequestDao()
            val saved = dao.find(row.requestId) ?: return@withTransaction null
            if (saved.conversationId != row.conversationId) return@withTransaction null
            val current = saved.conversationId?.let { buildSummaryCandidate(it)?.candidate }
            val step = dao.claimRecovery(identity, row.requestId, row.revision, current) ?: return@withTransaction null
            ConversationSummaryExecutor.Dispatch(identity, step, null)
        }
    }

    /** 领取与发送上下文在同一事务内绑定；网络调用必须在事务外执行。 */
    internal suspend fun claimSummaryDispatch(id: String, requestId: String, revision: Long): ConversationSummaryExecutor.Dispatch? = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction null
            val dao = database.conversationSummaryRequestDao()
            if (dao.find(requestId)?.conversationId != id) return@withTransaction null
            val current = buildSummaryCandidate(id)?.candidate
            val step = dao.next(identity, requestId, revision, current) ?: return@withTransaction null
            val action = step.action ?: return@withTransaction null
            ConversationSummaryExecutor.Dispatch(identity, step,
                current.takeIf { action == ConversationSummaryProtocol.Action.SUBMIT })
        }
    }

    internal suspend fun receiveSummaryRequest(id: String?, requestId: String, revision: Long, fingerprint: String,
        result: ConversationSummaryProtocol.Result): Boolean = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction false
            val dao = database.conversationSummaryRequestDao()
            val row = dao.find(requestId) ?: return@withTransaction false
            if (row.identity != identity || row.conversationId != id) return@withTransaction false
            dao.receive(identity, requestId, revision, fingerprint, id?.let { buildSummaryCandidate(it)?.candidate }, result)
        }
    }

    internal suspend fun receiveSummaryReceipt(id: String?, requestId: String, revision: Long, fingerprint: String,
        receipt: ConversationSummaryHttpClient.Receipt): Boolean = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction false
            val dao = database.conversationSummaryRequestDao()
            val row = dao.find(requestId) ?: return@withTransaction false
            if (row.identity != identity || row.conversationId != id) return@withTransaction false
            val current = id?.let { buildSummaryCandidate(it)?.candidate }
            dao.receive(identity, requestId, revision, fingerprint, current, receipt.result(requestId, fingerprint, current))
        }
    }

    internal suspend fun readRequestSummary(id: String): String? = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction null
            val chatGeneration = chatConsent.generation(identity)
            val memoryGeneration = memoryConsent.context(identity)
            val current = buildSummaryCandidate(id)?.candidate
            val dao = database.conversationSummaryRequestDao()
            val text = dao.readCompleted(identity, id, current)
            if (configuration() != identity || chatConsent.generation(identity) != chatGeneration ||
                memoryConsent.context(identity) != memoryGeneration) {
                dao.readCompleted(identity, id, null)
                return@withTransaction null
            }
            text
        }
    }

    internal suspend fun beginSummary(id: String, attemptId: String, expires: Long): Boolean = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction false
            val current = buildSummaryCandidate(id)?.candidate ?: return@withTransaction false
            database.conversationSummaryDao().begin(identity, id, current, attemptId, summaryProcessId, now(), expires)
        }
    }

    internal suspend fun acceptSummary(id: String, attemptId: String, text: String): Boolean = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction false
            database.conversationSummaryDao().accept(identity, id, buildSummaryCandidate(id)?.candidate,
                attemptId, summaryProcessId, text, now())
        }
    }

    internal suspend fun readSummary(id: String): String? = gate.withLock {
        database.withTransaction {
            val identity = configuration() ?: return@withTransaction null
            database.conversationSummaryDao().read(identity, id, buildSummaryCandidate(id)?.candidate, summaryProcessId)
        }
    }

    private suspend fun buildSummaryCandidate(id: String): ConversationSummaryPolicy.Plan? {
        val identity = configuration() ?: return null
        val generation = chatConsent.generation(identity) ?: return null
        val memoryGeneration = memoryConsent.context(identity)
        return database.withTransaction {
            val conversation = database.conversationDao().find(identity, id)
            if (conversation?.selected != true || conversation.archivedAtMillis != null) return@withTransaction null
            val turns = mutableListOf<ConversationSummaryPolicy.Turn>()
            validConversationTurns(identity, id) { intent, answer, refs ->
                val turn = runCatching { ConversationSummaryPolicy.Turn(intent.clientRequestId, intent.prompt, answer, MemoryContextState(refs)) }.getOrNull()
                if (turn != null) turns += turn
                true
            }
            if (configuration() != identity || chatConsent.generation(identity) != generation ||
                memoryConsent.context(identity) != memoryGeneration) return@withTransaction null
            ConversationSummaryPolicy.plan(ConversationSummaryPolicy.Scope(identity, id, generation, memoryGeneration),
                turns.asReversed(), turns.flatMap { it.dependencies.refs }.toSet())
        }
    }

    private suspend fun validConversationTurns(identity: CloudTaskIdentity, id: String,
        consume: (CloudTaskIntent, String, Set<MemoryRef>) -> Boolean) {
        require(database.conversationDao().find(identity, id)?.let { it.selected && it.archivedAtMillis == null } == true) { "会话已切换或归档" }
        var offset = 0
        // 失败或失效回合不占上下文名额；分页查找，不把第一页任务数当作问答上限。
        history@ while (true) {
            val rows = dao.conversationPage(identity, id, offset, 50)
            if (rows.isEmpty()) break
            offset += rows.size
            for (intent in rows) {
                val receipt = dao.receipt(identity, intent.clientRequestId)
                require(!jobs.containsKey(intent.clientRequestId)) { "请先等待上一条消息" }
                // 查询已结束的未知回合保留原任务，但不阻塞新问题或进入上下文。
                if (receipt == null || receipt.status in setOf("queued", "running")) continue
                if (intent.pendingControl != null || receipt.status != "completed" || receipt.resultJson == null) continue
                val trackedRow = database.memoryContextDependenciesDao().find(intent.clientRequestId)
                val tracked = if (trackedRow == null) null else try { trackedRow.state() } catch (_: IllegalArgumentException) { continue }
                // 旧回合缺少继承来源时不能证明间接引用仍有效；仅停止自动发送，不隐藏旧消息。
                if (tracked == null && intent.conversation()?.get("messages")?.jsonArray?.isNotEmpty() == true) continue
                if (tracked == null && database.memoryToolBindingDao().find(intent.clientRequestId) != null) continue
                if (tracked?.exhausted == true) continue
                val refs = (tracked?.refs ?: emptySet()) + intent.memoryRefs()
                // 包含工具搜索、工具保存和从上一轮继承的引用，而不只检查手工选中的条目。
                if (refs.any { ref ->
                    // 预期失效不能抛出嵌套事务异常，否则外层摘要清理也会回滚。
                    val memory = database.memoryDao().find("local", ref.id)
                    memory == null || memory.deletedAtMillis != null || memory.content == null ||
                        memory.version != ref.version || memory.source != "user_explicit" ||
                        runCatching { MemoryCommands.content(memory.content) == memory.content }.getOrDefault(false).not()
                }) continue
                val answer = Json.parseToJsonElement(receipt.resultJson).jsonObject["answer"]?.jsonPrimitive?.content ?: continue
                if (!consume(intent, answer, refs)) break@history
            }
            if (rows.size < 50) break
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
