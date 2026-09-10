package com.healthyagent.running.coordinator

import com.healthyagent.running.ai.AnalysisManager
import com.healthyagent.running.ai.EmergencyPhraseCache
import com.healthyagent.running.ai.LlmConfig
import com.healthyagent.running.ai.PromptBuilder
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.data.local.SettingsPatch
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.data.local.SettingsSnapshot
import com.healthyagent.running.data.local.toRunConfig
import com.healthyagent.running.data.local.validate
import com.healthyagent.running.data.health.HealthConnectSync
import com.healthyagent.running.data.health.HealthConnectSyncResult
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.data.sensor.RunLifecycleAwareSensor
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.speech.SpeechBus
import com.healthyagent.running.speech.SpeechPolicy
import com.healthyagent.running.speech.SpeechRequest
import com.healthyagent.running.speech.SessionSpeechEvent
import com.healthyagent.running.speech.SessionSpeechTriggerFactory
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 不依赖界面生命周期的跑步业务唯一入口。 */
class AppCoordinator(
    private val sensorDataSource: SensorDataSource,
    private val pipeline: RunningDataPipeline,
    private val runConfig: RunConfig,
    private val ruleEngine: RuleEngine,
    private val speechBus: SpeechBus,
    private val sessionManager: RunSessionManager,
    private val repository: RunRepository,
    private val settingsRepository: SettingsRepository,
    private val healthConnectSync: HealthConnectSync,
    private val analysisManager: AnalysisManager,
    private val emergencyPhraseCache: EmergencyPhraseCache,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val realtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pausedHeartRateMonitor: PausedHeartRateMonitor = PausedHeartRateMonitor(),
    private val onVoiceSettingsChanged: (SettingsSnapshot) -> Unit = {},
) {
    private val _snapshot = MutableStateFlow(
        AppSnapshot.initial(runConfig, nowMillis()).copy(
            connection = ConnectionSnapshot(
                state = sensorDataSource.connectionState.value.toContractValue(),
                deviceName = sensorDataSource.primaryDeviceInfo.name.takeIf(String::isNotBlank),
                batteryPercent = sensorDataSource.primaryDeviceInfo.batteryPercent,
            ),
            wearable = wearableSnapshot(sensorDataSource.wearableConnectionState?.value),
        ),
    )
    val snapshot: StateFlow<AppSnapshot> = _snapshot.asStateFlow()

    private val _events = Channel<CoordinatorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pipelineJob: Job? = null
    private var pausedHeartRateJob: Job? = null
    private var connectJob: Job? = null
    private var connectIncludesPhone = false
    private var disconnectJob: Job? = null
    private val connectionMutex = Mutex()
    private var connectionGeneration = 0L
    private var pipelineGeneration = 0L
    private var runGeneration = 0L
    private var ruleContext = RuleContext()
    private val recentTriggerTexts = mutableListOf<String>()
    private val persistenceMutex = Mutex()
    private val restoreJob: Deferred<Boolean>
    private var segmentBaseElapsedSeconds = 0L
    private var segmentBaseElapsedMillis = 0L
    private var segmentStartedAtRealtimeMillis: Long? = null
    private var segmentBaseDistanceMeters = 0.0
    private var sourceDistanceBaseline: Double? = null
    private var lastCommittedElapsedSeconds = 0L
    private var lastCommittedDistanceMeters = 0.0
    private var sourceWasFresh = false
    private var lastCheckpointElapsed = -1L
    private var wearableWasConnected =
        sensorDataSource.wearableConnectionState?.value == ConnectionState.CONNECTED
    private var wearableDisconnectNotified = false
    @Volatile
    private var activeCompletionId: String? = null
    @Volatile
    private var pendingRunConfig = runConfig
    @Volatile
    private var activeRunConfig = runConfig
    @Volatile
    private var pendingLlmApiKey = ""
    private var voiceSettings: SettingsSnapshot? = null

    val isRestoring: Boolean get() = !restoreJob.isCompleted

    init {
        observeConnection()
        observeWearableConnection()
        observeRoutePoints()
        observeCoachMessages()
        observeSettings()
        restoreJob = scope.async { restoreActiveSession() }
    }

    suspend fun awaitRestoration(): Boolean = restoreJob.await()

    /** 前台服务已取得 location 运行资格后，才让恢复中的 RUNNING 会话继续计时和采样。 */
    @Synchronized
    fun attachRecoveredRun(): CommandOutcome {
        if (sessionManager.currentState != RunState.RUNNING) return CommandOutcome.Accepted
        val session = sessionManager.currentSession
            ?: return CommandOutcome.Rejected(BridgeErrorCode.INTERNAL_ERROR, "恢复会话数据不存在")
        if (segmentStartedAtRealtimeMillis == null) {
            prepareFrameRebase(
                elapsedSeconds = session.totalDurationSec,
                distanceMeters = session.totalDistanceMeters,
                running = true,
            )
            ruleContext = RuleContext(elapsedSeconds = session.totalDurationSec)
            (sensorDataSource as? RunLifecycleAwareSensor)?.onRunResumed(session.totalDurationSec)
            (sensorDataSource as? RunLifecycleAwareSensor)?.onRunMetricsUpdated(session.totalDistanceMeters, null)
            connectSensor()
        }
        return CommandOutcome.Accepted
    }

    suspend fun queryHistory(page: Int, pageSize: Int): CommandOutcome {
        if (page < 1 || pageSize !in 1..100) {
            return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "历史分页参数无效")
        }
        val offset = (page - 1L) * pageSize
        if (offset > Int.MAX_VALUE) {
            return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "历史分页范围过大")
        }

        val history = repository.getHistoryPage(offset = offset.toInt(), limit = pageSize)
        val data = buildJsonObject {
            put("page", page)
            put("pageSize", pageSize)
            put("totalItems", history.totalItems)
            put("hasNextPage", offset + history.items.size < history.totalItems.toLong())
            put(
                "items",
                buildJsonArray {
                    history.items.forEach { session ->
                        add(
                            buildJsonObject {
                                put("id", session.id)
                                put("startedAt", Instant.ofEpochMilli(session.startTimeMillis).toString())
                                put(
                                    "endedAt",
                                    session.endTimeMillis?.let {
                                        JsonPrimitive(Instant.ofEpochMilli(it).toString())
                                    } ?: JsonNull,
                                )
                                put("distanceMeters", session.totalDistanceMeters)
                                put("durationSeconds", session.totalDurationSec)
                                put(
                                    "averagePaceSecPerKm",
                                    session.avgPaceSecPerKm?.let { JsonPrimitive(it) } ?: JsonNull,
                                )
                                put(
                                    "averageHeartRate",
                                    session.avgHeartRate?.let { JsonPrimitive(it) } ?: JsonNull,
                                )
                                put(
                                    "averageCadence",
                                    session.avgCadence?.let { JsonPrimitive(it) } ?: JsonNull,
                                )
                            },
                        )
                    }
                },
            )
        }
        return CommandOutcome.AcceptedWithData(data)
    }

    suspend fun querySettings(): CommandOutcome = runCatching {
        settingsRepository.current()
    }.fold(
        onSuccess = { settings ->
            acceptPersistedSettings(settings)
            CommandOutcome.AcceptedWithData(settingsResult(settings))
        },
        onFailure = {
            CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "读取本地设置失败")
        },
    )

    suspend fun updateSettings(patch: SettingsPatch): CommandOutcome {
        if (patch.validate().isNotEmpty()) {
            return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "设置值超出有效范围")
        }

        return runCatching {
            settingsRepository.update(patch)
            settingsRepository.current()
        }.fold(
            onSuccess = { settings ->
                acceptPersistedSettings(settings)
                CommandOutcome.AcceptedWithData(settingsResult(settings))
            },
            onFailure = {
                CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "保存本地设置失败")
            },
        )
    }

    fun updatePermissions(permissions: PermissionsSnapshot) {
        updateSnapshot { it.copy(permissions = permissions) }
    }

    fun requestRunningPermissions(): CommandOutcome {
        scope.launch { _events.send(CoordinatorEvent.RequestRunningPermissions) }
        return CommandOutcome.Accepted
    }

    @Synchronized
    fun connectSensor(): CommandOutcome {
        val includePhone = sessionManager.currentState in setOf(RunState.RUNNING, RunState.PAUSED)
        if (!includePhone && isRestoring) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "正在恢复上次跑步，请稍候")
        }
        if (!includePhone && sensorDataSource.wearableConnectionState == null) {
            return CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "此设备未启用手环接入")
        }
        if (connectJob?.isActive == true && (!includePhone || connectIncludesPhone)) return CommandOutcome.Accepted
        val pendingConnect = connectJob
        val generation = ++connectionGeneration
        val pendingDisconnect = disconnectJob
        lateinit var launchedJob: Job
        launchedJob = scope.launch {
            try {
                pendingConnect?.join()
                pendingDisconnect?.join()
                if (!isCurrentConnectionGeneration(generation)) return@launch
                connectionMutex.withLock {
                    if (includePhone) sensorDataSource.connect() else sensorDataSource.prepareConnection()
                }
                if (includePhone && isCurrentConnectionGeneration(generation)) ensurePipelineStarted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCurrentConnectionGeneration(generation)) return@launch
                updateSnapshot {
                    if (includePhone) it.copy(
                        connection = it.connection.copy(state = "error"),
                        safetyState = SafetySnapshot("warning", PRIMARY_SOURCE_ERROR_MESSAGE),
                    ) else it.copy(wearable = it.wearable.copy(state = "error"))
                }
                _events.send(CoordinatorEvent.ShowError(error.message ?: "连接设备失败"))
            }
        }
        connectIncludesPhone = includePhone
        connectJob = launchedJob
        launchedJob.invokeOnCompletion {
            synchronized(this) {
                if (connectJob === launchedJob) connectJob = null
            }
        }
        return CommandOutcome.Accepted
    }

    fun disconnectSensor(): CommandOutcome {
        if (sessionManager.currentState in setOf(RunState.RUNNING, RunState.PAUSED)) {
            return CommandOutcome.Rejected(
                BridgeErrorCode.COMMAND_NOT_ALLOWED,
                "跑步进行中不能断开全部传感器",
            )
        }
        scheduleDisconnect()
        return CommandOutcome.Accepted
    }

    @Synchronized
    fun startRun(): CommandOutcome {
        if (isRestoring) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "正在恢复上次跑步，请稍候")
        }
        if (_snapshot.value.persistenceState == "saving") {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "正在保存上次跑步，请稍候")
        }
        if (sessionManager.currentState in setOf(RunState.RUNNING, RunState.PAUSED)) {
            return CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "已有跑步正在进行")
        }

        val config = pendingRunConfig
        runGeneration++
        cancelPipeline()
        activeCompletionId = null
        applyActiveRunConfig(config)
        val session = sessionManager.startSession(config)
        ruleEngine.reset()
        ruleContext = RuleContext()
        recentTriggerTexts.clear()
        analysisManager.cancel()
        speechBus.resetSession()
        enqueueSessionStatus(SessionSpeechEvent.STARTED)
        prepareFrameRebase(
            elapsedSeconds = session.totalDurationSec,
            distanceMeters = session.totalDistanceMeters,
            running = true,
        )

        updateSnapshot {
            it.copy(
                session = session.toSnapshot(config),
                metrics = MetricsSnapshot(),
                completedRun = null,
                persistenceState = "idle",
                healthSync = HealthSyncSnapshot(),
                latestCoachMessage = null,
                safetyState = SafetySnapshot(),
            )
        }
        checkpointActiveSession()
        (sensorDataSource as? RunLifecycleAwareSensor)?.onRunStarted(session.totalDurationSec)
        connectSensor()
        return CommandOutcome.Accepted
    }

    @Synchronized
    fun pauseRun(): CommandOutcome {
        if (sessionManager.currentState != RunState.RUNNING) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "当前不能暂停")
        }
        val runningPipelineGeneration = pipelineGeneration
        val pausedMaxHeartRate = activeRunConfig.maxHeartRate
        val pausedSeed = pausedHeartRateMonitor.freezeForPause(
            runningPipelineGeneration,
            pausedMaxHeartRate,
        )
        val frozenElapsedMillis = currentActiveElapsedMillis()
        val frozenElapsed = maxOf(lastCommittedElapsedSeconds, frozenElapsedMillis / 1_000L)
        runGeneration++
        sessionManager.advanceElapsedTo(frozenElapsed)
        lastCommittedElapsedSeconds = frozenElapsed
        segmentBaseElapsedSeconds = frozenElapsed
        segmentBaseElapsedMillis = frozenElapsedMillis
        segmentStartedAtRealtimeMillis = null
        cancelPipeline()
        analysisManager.cancel()
        sessionManager.pause()
        enqueueSessionStatus(SessionSpeechEvent.PAUSED)
        (sensorDataSource as? RunLifecycleAwareSensor)?.onRunPaused(frozenElapsed)
        updateSnapshot { current ->
            current.copy(
                session = current.session.copy(state = RunState.PAUSED.toContractValue()),
                metrics = current.metrics.copy(
                    elapsedSeconds = frozenElapsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                ),
                latestCoachMessage = null,
            )
        }
        val pausedGeneration = pipelineGeneration
        pausedHeartRateJob = scope.launch {
            pausedHeartRateMonitor.observe(
                samples = sensorDataSource.samples,
                maxHeartRate = pausedMaxHeartRate,
                seed = pausedSeed,
            ).collect { state -> processPausedHeartRate(state, pausedGeneration) }
        }
        checkpointActiveSession()
        return CommandOutcome.Accepted
    }

    @Synchronized
    fun resumeRun(): CommandOutcome {
        if (sessionManager.currentState != RunState.PAUSED) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "当前不能继续")
        }
        cancelPipeline()
        runGeneration++
        sessionManager.resume()
        val session = sessionManager.currentSession
        prepareFrameRebase(
            elapsedSeconds = session?.totalDurationSec ?: _snapshot.value.metrics.elapsedSeconds.toLong(),
            distanceMeters = session?.totalDistanceMeters ?: _snapshot.value.metrics.distanceMeters,
            running = true,
            elapsedMillis = segmentBaseElapsedMillis,
        )
        ruleContext = RuleContext(elapsedSeconds = segmentBaseElapsedSeconds)
        updateSnapshot { current ->
            current.copy(
                session = current.session.copy(state = RunState.RUNNING.toContractValue()),
                metrics = current.metrics.copy(
                    heartRate = null,
                    paceSecPerKm = null,
                    cadence = null,
                    strideLengthCm = null,
                    distanceMeters = segmentBaseDistanceMeters,
                    elapsedSeconds = segmentBaseElapsedSeconds
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                    hrZone = null,
                    isDataFresh = false,
                ),
            )
        }
        checkpointActiveSession()
        (sensorDataSource as? RunLifecycleAwareSensor)?.onRunResumed(segmentBaseElapsedSeconds)
        (sensorDataSource as? RunLifecycleAwareSensor)?.onRunMetricsUpdated(segmentBaseDistanceMeters, null)
        enqueueSessionStatus(SessionSpeechEvent.RESUMED)
        connectSensor()
        return CommandOutcome.Accepted
    }

    @Synchronized
    fun stopRun(): CommandOutcome {
        if (sessionManager.currentState !in setOf(RunState.RUNNING, RunState.PAUSED)) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "当前没有可结束的跑步")
        }

        if (sessionManager.currentState == RunState.RUNNING) {
            val frozenElapsedMillis = currentActiveElapsedMillis()
            val frozenElapsed = maxOf(lastCommittedElapsedSeconds, frozenElapsedMillis / 1_000L)
            segmentBaseElapsedMillis = frozenElapsedMillis
            sessionManager.advanceElapsedTo(frozenElapsed)
            lastCommittedElapsedSeconds = frozenElapsed
        }
        runGeneration++
        cancelPipeline()
        analysisManager.cancel()
        (sensorDataSource as? RunLifecycleAwareSensor)?.onRunStopped()
        enqueueSessionStatus(SessionSpeechEvent.FINISHED)
        val pendingDisconnect = scheduleDisconnect()
        val session = sessionManager.finishSession()
            ?: return CommandOutcome.Rejected(BridgeErrorCode.INTERNAL_ERROR, "会话数据不存在")
        activeCompletionId = session.id
        updateSnapshot {
            it.copy(
                session = session.toSnapshot(activeRunConfig),
                completedRun = session.toCompletedRunSnapshot(),
                persistenceState = "saving",
                healthSync = HealthSyncSnapshot(),
                latestCoachMessage = null,
            )
        }

        scope.launch {
            pendingDisconnect.join()
            val saveResult = runCatching {
                persistenceMutex.withLock { repository.saveSession(session) }
            }
            if (saveResult.isFailure) {
                updateCompletionSnapshot(session.id) {
                    it.copy(persistenceState = "failed", healthSync = HealthSyncSnapshot())
                }
                _events.send(
                    CoordinatorEvent.ShowError(
                        saveResult.exceptionOrNull()?.message ?: "保存跑步记录失败",
                    ),
                )
                return@launch
            }

            updateCompletionSnapshot(session.id) { it.copy(persistenceState = "saved") }
            updateCompletionSnapshot(session.id) {
                it.copy(healthSync = HealthSyncSnapshot(state = "syncing"))
            }
            val healthSnapshot = runCatching { healthConnectSync.syncRun(session) }
                .fold(
                    onSuccess = HealthConnectSyncResult::toSnapshot,
                    onFailure = { HealthSyncSnapshot(state = "failed") },
                )
            updateCompletionSnapshot(session.id) { it.copy(healthSync = healthSnapshot) }
        }
        emergencyPhraseCache.clear()
        return CommandOutcome.Accepted
    }

    @Synchronized
    fun close() {
        cancelPipeline()
        speechBus.shutdown()
        scope.cancel()
    }

    private fun observeConnection() {
        scope.launch {
            sensorDataSource.connectionState.collect { state ->
                val info = sensorDataSource.primaryDeviceInfo
                updateSnapshot {
                    it.copy(
                        connection = ConnectionSnapshot(
                            state = state.toContractValue(),
                            deviceName = info.name.takeIf { name -> name.isNotBlank() },
                            batteryPercent = info.batteryPercent,
                        ),
                        safetyState = when (state) {
                            ConnectionState.ERROR -> SafetySnapshot("warning", PRIMARY_SOURCE_ERROR_MESSAGE)
                            else -> if (it.safetyState.message == PRIMARY_SOURCE_ERROR_MESSAGE) {
                                SafetySnapshot()
                            } else {
                                it.safetyState
                            }
                        },
                    )
                }
            }
        }
    }

    private fun observeWearableConnection() {
        val wearableState = sensorDataSource.wearableConnectionState ?: return
        scope.launch {
            wearableState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        wearableWasConnected = true
                        wearableDisconnectNotified = false
                        updateSnapshot {
                            it.copy(
                                wearable = wearableSnapshot(state),
                                safetyState = if (it.safetyState.message == WEARABLE_DISCONNECTED_MESSAGE) {
                                    SafetySnapshot()
                                } else {
                                    it.safetyState
                                },
                            )
                        }
                    }
                    ConnectionState.CONNECTING,
                    ConnectionState.DISCONNECTED,
                    ConnectionState.ERROR,
                    -> {
                        val running = sessionManager.currentState in ACTIVE_RUN_STATES
                        updateSnapshot { it.copy(wearable = wearableSnapshot(state)) }
                        if (running && wearableWasConnected && !wearableDisconnectNotified) {
                            wearableDisconnectNotified = true
                            updateSnapshot {
                                it.copy(
                                    safetyState = SafetySnapshot(
                                        "warning",
                                        WEARABLE_DISCONNECTED_MESSAGE,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 路线只在 RUNNING 状态进入会话；暂停期间的定位回调由状态机自动忽略。 */
    private fun observeRoutePoints() {
        scope.launch {
            sensorDataSource.routePoints.collect(::acceptRoutePoint)
        }
    }

    @Synchronized
    private fun acceptRoutePoint(point: RoutePoint) {
        sessionManager.onRoutePoint(point)
    }

    private fun observeCoachMessages() {
        scope.launch {
            speechBus.latestMessage.collect { message ->
                updateSnapshot { it.copy(latestCoachMessage = message?.toSnapshot()) }
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                acceptPersistedSettings(settings)
            }
        }
    }

    @Synchronized
    private fun acceptPersistedSettings(settings: SettingsSnapshot) {
        val nextRunConfig = settings.toRunConfig()
        pendingRunConfig = nextRunConfig
        pendingLlmApiKey = settings.llmApiKey
        val previous = voiceSettings
        voiceSettings = settings
        onVoiceSettingsChanged(settings)
        LlmConfig.apiKey = settings.llmApiKey
        if (previous != null && (
                previous.ttsEnabled != settings.ttsEnabled ||
                previous.ttsMinIntervalSec != settings.ttsMinIntervalSec ||
                previous.llmEnabled != settings.llmEnabled ||
                previous.llmApiKey != settings.llmApiKey ||
                previous.fishApiKey != settings.fishApiKey ||
                previous.fishReferenceId != settings.fishReferenceId
            )) speechBus.stop()
        speechBus.configure(SpeechPolicy(settings.ttsEnabled, settings.ttsMinIntervalSec))
        updateSnapshot { current ->
            val session = if (sessionManager.currentState in CONFIG_FROZEN_RUN_STATES) {
                current.session
            } else {
                current.session.copy(
                    targetPaceSecPerKm = nextRunConfig.targetPaceSecPerKm,
                    targetDistanceMeters = nextRunConfig.targetDistanceMeters,
                )
            }
            current.copy(
                session = session,
                settings = SettingsContractSnapshot.from(settings),
                theme = settings.themePreference.toTheme(),
                motionPolicy = settings.motionPreference.toPolicy(),
            )
        }
    }

    private fun applyActiveRunConfig(config: RunConfig) {
        activeRunConfig = config
        pipeline.updateConfig(config)
        ruleEngine.updateConfig(config)
        speechBus.configure(
            SpeechPolicy(
                enabled = voiceSettings?.ttsEnabled ?: config.ttsEnabled,
                minIntervalSec = voiceSettings?.ttsMinIntervalSec ?: config.ttsMinIntervalSec,
            ),
        )
        LlmConfig.apiKey = pendingLlmApiKey
    }

    private fun enqueueSessionStatus(event: SessionSpeechEvent) {
        speechBus.enqueueSessionStatus(SpeechRequest(
            SessionSpeechTriggerFactory.create(event),
            companionEnabled = voiceSettings?.llmEnabled ?: activeRunConfig.llmEnabled,
        ))
    }

    private fun settingsResult(settings: SettingsSnapshot): JsonObject = buildJsonObject {
        put(
            "settings",
            BridgeCodec.json.encodeToJsonElement(
                SettingsContractSnapshot.serializer(),
                SettingsContractSnapshot.from(settings),
            ).jsonObject,
        )
    }

    @Synchronized
    private fun ensurePipelineStarted() {
        if (sessionManager.currentState != RunState.RUNNING || pipelineJob?.isActive == true) return
        val generation = ++pipelineGeneration
        pausedHeartRateMonitor.beginRunning(generation)
        pipelineJob = scope.launch {
            pipeline.process(
                samples = sensorDataSource.samples,
                onOrderedHeartRateReceived = { sample, receivedAtMillis ->
                    pausedHeartRateMonitor.recordRunningSample(
                        generation = generation,
                        sample = sample,
                        receivedAtMillis = receivedAtMillis,
                    )
                },
            )
                .catch { error ->
                    if (!isCurrentPipelineGeneration(generation)) return@catch
                    updateSnapshot {
                        it.copy(safetyState = SafetySnapshot("warning", "跑步数据暂时中断"))
                    }
                    _events.send(CoordinatorEvent.ShowError(error.message ?: "数据管道错误"))
                }
                .collect { frame -> processFrame(frame, generation) }
        }
    }

    private fun cancelPipeline() {
        pipelineGeneration++
        pipelineJob?.cancel()
        pipelineJob = null
        pausedHeartRateJob?.cancel()
        pausedHeartRateJob = null
        pausedHeartRateMonitor.invalidateRunning()
    }

    @Synchronized
    internal fun processPausedHeartRate(state: PausedHeartRateState, generation: Long) {
        if (pipelineGeneration != generation || sessionManager.currentState != RunState.PAUSED) return
        updateSnapshot { current ->
            current.copy(
                metrics = current.metrics.copy(
                    heartRate = state.heartRate,
                    hrZone = state.hrZone?.toContractValue(),
                ),
            )
        }
    }

    @Synchronized
    internal fun pipelineGenerationToken(): Long = pipelineGeneration

    @Synchronized
    private fun processFrame(sourceFrame: MetricsFrame, generation: Long) {
        if (!isCurrentPipelineGeneration(generation) || sessionManager.currentState != RunState.RUNNING) return

        val frame = rebaseFrame(sourceFrame)
        (sensorDataSource as? RunLifecycleAwareSensor)?.onRunMetricsUpdated(
            frame.distanceMeters,
            frame.currentPaceSecPerKm.takeIf { frame.isDataFresh },
        )
        updateSnapshot {
            val primaryInfo = sensorDataSource.primaryDeviceInfo
            it.copy(
                metrics = frame.toSnapshot(),
                connection = it.connection.copy(
                    deviceName = primaryInfo.name.takeIf(String::isNotBlank),
                    batteryPercent = primaryInfo.batteryPercent,
                ),
                wearable = wearableSnapshot(sensorDataSource.wearableConnectionState?.value),
            )
        }

        val split = sessionManager.onFrame(frame)
        if (frame.elapsedSeconds - lastCheckpointElapsed >= CHECKPOINT_INTERVAL_SECONDS) {
            lastCheckpointElapsed = frame.elapsedSeconds
            checkpointActiveSession()
        }
        if (split != null) {
            ruleEngine.onSplit(split.index)
        }

        val triggers = ruleEngine.evaluate(
            frame,
            ruleContext.copy(completedSplit = split),
        )
        ruleContext = ruleContext.copy(
            recentFrames = (ruleContext.recentFrames + frame).takeLast(60),
            elapsedSeconds = frame.elapsedSeconds,
            completedSplit = null,
        )

        triggers.forEach { trigger ->
            val request = SpeechRequest(
                trigger = trigger,
                companionEnabled = voiceSettings?.llmEnabled ?: activeRunConfig.llmEnabled,
                analysis = if (trigger.ruleId == "km_split" && split != null) {
                    sessionManager.currentSession?.let { session ->
                        PromptBuilder.build(session, split, activeRunConfig, recentTriggerTexts.toList())
                    }
                } else null,
            )
            recentTriggerTexts.add(trigger.speakText)
            if (recentTriggerTexts.size > 10) recentTriggerTexts.removeAt(0)
            val generation = runGeneration
            val speechSessionGeneration = speechBus.sessionGenerationToken()
            scope.launch {
                if (!isCurrentRunGeneration(generation)) return@launch
                speechBus.enqueue(
                    request,
                    speechSessionGeneration,
                )
            }
        }
    }

    private fun prepareFrameRebase(
        elapsedSeconds: Long,
        distanceMeters: Double,
        running: Boolean,
        elapsedMillis: Long = elapsedSeconds * 1_000L,
    ) {
        segmentBaseElapsedSeconds = elapsedSeconds
        segmentBaseElapsedMillis = elapsedMillis
        segmentStartedAtRealtimeMillis = realtimeMillis().takeIf { running }
        segmentBaseDistanceMeters = distanceMeters
        sourceDistanceBaseline = null
        lastCommittedElapsedSeconds = elapsedSeconds
        lastCommittedDistanceMeters = distanceMeters
        sourceWasFresh = false
        lastCheckpointElapsed = elapsedSeconds
    }

    private fun rebaseFrame(source: MetricsFrame): MetricsFrame {
        val elapsed = maxOf(lastCommittedElapsedSeconds, currentActiveElapsedSeconds()).also {
            lastCommittedElapsedSeconds = it
        }
        val distance = if (source.isDataFresh) {
            if (!sourceWasFresh || sourceDistanceBaseline == null) {
                segmentBaseDistanceMeters = lastCommittedDistanceMeters
                sourceDistanceBaseline = source.distanceMeters
            }
            val distanceBaseline = requireNotNull(sourceDistanceBaseline)
            val rebased = segmentBaseDistanceMeters +
                (source.distanceMeters - distanceBaseline).coerceAtLeast(0.0)
            maxOf(lastCommittedDistanceMeters, rebased)
        } else {
            lastCommittedDistanceMeters
        }
        sourceWasFresh = source.isDataFresh
        lastCommittedDistanceMeters = distance
        return source.copy(
            elapsedSeconds = elapsed,
            distanceMeters = distance,
            splitDistanceMeters = distance,
        )
    }

    private fun currentActiveElapsedSeconds(): Long {
        return maxOf(lastCommittedElapsedSeconds, currentActiveElapsedMillis() / 1_000L)
    }

    private fun currentActiveElapsedMillis(): Long {
        val segmentStart = segmentStartedAtRealtimeMillis ?: return segmentBaseElapsedMillis
        val deltaMillis = (realtimeMillis() - segmentStart).coerceAtLeast(0L)
        return maxOf(lastCommittedElapsedSeconds * 1_000L, segmentBaseElapsedMillis + deltaMillis)
    }

    @Synchronized
    private fun scheduleDisconnect(): Job {
        ++connectionGeneration
        val connecting = connectJob
        disconnectJob?.takeIf(Job::isActive)?.let { activeDisconnect ->
            connecting?.cancel()
            return activeDisconnect
        }
        lateinit var launchedJob: Job
        launchedJob = scope.launch {
            connecting?.cancelAndJoin()
            try {
                connectionMutex.withLock { sensorDataSource.disconnect() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 断开失败不能阻止本地跑步记录落盘。
            }
        }
        disconnectJob = launchedJob
        launchedJob.invokeOnCompletion {
            synchronized(this) {
                if (disconnectJob === launchedJob) disconnectJob = null
            }
        }
        return launchedJob
    }

    @Synchronized
    private fun isCurrentConnectionGeneration(generation: Long): Boolean =
        connectionGeneration == generation

    @Synchronized
    private fun isCurrentPipelineGeneration(generation: Long): Boolean =
        pipelineGeneration == generation

    @Synchronized
    private fun isCurrentRunGeneration(generation: Long): Boolean =
        runGeneration == generation && sessionManager.currentState == RunState.RUNNING

    private fun checkpointActiveSession() {
        val sessionId = synchronized(this) { sessionManager.currentSessionId } ?: return
        scope.launch {
            runCatching {
                persistenceMutex.withLock {
                    val current = synchronized(this@AppCoordinator) {
                        sessionManager.currentSession?.takeIf { session ->
                            session.id == sessionId && session.runState in ACTIVE_RUN_STATES
                        }
                    }
                    if (current != null) {
                        repository.checkpointSession(current)
                    }
                }
            }.onFailure { error ->
                _events.send(CoordinatorEvent.ShowError(error.message ?: "保存跑步检查点失败"))
            }
        }
    }

    private fun wearableSnapshot(state: ConnectionState?): WearableSnapshot {
        val info = sensorDataSource.wearableDeviceInfo
        val supported = sensorDataSource.wearableConnectionState != null || info != null
        return WearableSnapshot(
            supported = supported,
            state = (state ?: ConnectionState.DISCONNECTED).toContractValue(),
            deviceName = info?.name?.takeIf(String::isNotBlank),
            batteryPercent = info?.batteryPercent?.takeIf { state == ConnectionState.CONNECTED },
        )
    }

    private fun updateCompletionSnapshot(
        sessionId: String,
        transform: (AppSnapshot) -> AppSnapshot,
    ) {
        if (activeCompletionId != sessionId) return
        _snapshot.update { current ->
            if (activeCompletionId != sessionId) {
                current
            } else {
                transform(current).copy(
                    revision = current.revision + 1,
                    generatedAt = Instant.ofEpochMilli(nowMillis()).toString(),
                )
            }
        }
    }

    private suspend fun restoreActiveSession(): Boolean {
        val restoredConfig = runCatching {
            val settings = settingsRepository.current()
            pendingLlmApiKey = settings.llmApiKey
            settings.toRunConfig()
        }.getOrDefault(pendingRunConfig)
        return runCatching { repository.getActiveSession() }
            .fold(
                onSuccess = { session ->
                    if (session == null) {
                        false
                    } else {
                        synchronized(this) {
                            if (sessionManager.currentState == RunState.IDLE) {
                                runGeneration++
                                applyActiveRunConfig(restoredConfig)
                                sessionManager.restoreSession(session)
                                prepareFrameRebase(
                                    elapsedSeconds = session.totalDurationSec,
                                    distanceMeters = session.totalDistanceMeters,
                                    running = false,
                                )
                                updateSnapshot {
                                    it.copy(
                                        session = session.toSnapshot(restoredConfig),
                                        metrics = MetricsSnapshot(
                                            distanceMeters = session.totalDistanceMeters,
                                            elapsedSeconds = session.totalDurationSec
                                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                                .toInt(),
                                            isDataFresh = false,
                                        ),
                                        safetyState = SafetySnapshot("normal", "已恢复上次跑步"),
                                    )
                                }
                            }
                        }
                        true
                    }
                },
                onFailure = { error ->
                    updateSnapshot {
                        it.copy(safetyState = SafetySnapshot("warning", "恢复上次跑步失败"))
                    }
                    _events.send(CoordinatorEvent.ShowError(error.message ?: "恢复跑步失败"))
                    false
                },
            )
    }

    private fun updateSnapshot(transform: (AppSnapshot) -> AppSnapshot) {
        _snapshot.update { current ->
            transform(current).copy(
                revision = current.revision + 1,
                generatedAt = Instant.ofEpochMilli(nowMillis()).toString(),
            )
        }
    }

    private companion object {
        const val CHECKPOINT_INTERVAL_SECONDS = 5L
        const val PRIMARY_SOURCE_ERROR_MESSAGE = "手机定位暂时不可用"
        const val WEARABLE_DISCONNECTED_MESSAGE = "Keep B5 已断开，GPS 跑步仍在继续"
        val ACTIVE_RUN_STATES = setOf(RunState.RUNNING, RunState.PAUSED)
        val CONFIG_FROZEN_RUN_STATES = setOf(RunState.PREPARING, RunState.RUNNING, RunState.PAUSED)
    }
}

private fun HealthConnectSyncResult.toSnapshot(): HealthSyncSnapshot = when (this) {
    is HealthConnectSyncResult.Synced -> HealthSyncSnapshot(
        state = "synced",
        routeWritten = routeWritten,
    )
    HealthConnectSyncResult.Unavailable -> HealthSyncSnapshot(state = "unavailable")
    HealthConnectSyncResult.ProviderUpdateRequired -> {
        HealthSyncSnapshot(state = "providerUpdateRequired")
    }
    is HealthConnectSyncResult.PermissionMissing -> HealthSyncSnapshot(state = "permissionRequired")
    is HealthConnectSyncResult.InvalidRun,
    is HealthConnectSyncResult.Failed,
    -> HealthSyncSnapshot(state = "failed")
}

sealed interface CommandOutcome {
    data object Accepted : CommandOutcome

    data class AcceptedWithData(val data: JsonObject) : CommandOutcome

    data class Rejected(
        val code: BridgeErrorCode,
        val message: String,
    ) : CommandOutcome
}

interface CoordinatorEvent {
    data class ShowError(val message: String) : CoordinatorEvent

    data object RequestRunningPermissions : CoordinatorEvent
}
