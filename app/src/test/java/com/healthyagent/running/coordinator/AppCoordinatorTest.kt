package com.healthyagent.running.coordinator

import com.healthyagent.running.ai.AnalysisManager
import com.healthyagent.running.ai.EmergencyPhraseCache
import com.healthyagent.running.ai.LlmAnalyzeRequest
import com.healthyagent.running.ai.LlmAnalyzeResponse
import com.healthyagent.running.ai.LlmClient
import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.RuleCatalog
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.core.engine.rules.HrZone5Rule
import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.data.local.SettingsPatch
import com.healthyagent.running.data.local.NullableSettingUpdate
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.data.local.SettingsSnapshot
import com.healthyagent.running.data.health.HealthConnectAvailability
import com.healthyagent.running.data.health.HealthConnectPermissionState
import com.healthyagent.running.data.health.HealthConnectSync
import com.healthyagent.running.data.health.HealthConnectSyncResult
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.data.repository.RunHistoryPage
import com.healthyagent.running.data.sensor.RunLifecycleAwareSensor
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.data.sensor.WearableLinkObservation
import com.healthyagent.running.data.sensor.WearableLinkPhase
import com.healthyagent.running.data.sensor.WearableLinkFault
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.SpeechOutcomeReason
import com.healthyagent.running.speech.SpeechBus
import com.healthyagent.running.speech.SpeechRequest
import com.healthyagent.running.speech.SpeechDelivery
import com.healthyagent.running.speech.VoicePolicyTtsEngine
import com.healthyagent.running.speech.BundledVoiceStore
import com.healthyagent.running.speech.CompanionTextSource
import com.healthyagent.running.speech.FishSynthesisClient
import com.healthyagent.running.speech.PreparedVoicePlayer
import com.healthyagent.running.speech.AudioFocusController
import com.healthyagent.running.speech.AudioFocusLease
import com.healthyagent.running.speech.AudioPlayerPort
import com.healthyagent.running.speech.SpeechAudioProfile
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import com.healthyagent.running.speech.TtsEngine
import com.healthyagent.running.testing.FakeDiagnosticLogger
import com.healthyagent.running.testing.FakeTtsEngine
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.healthyagent.running.data.local.SettingsDataStore
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class AppCoordinatorTest {
    @Test fun `关闭时已等待协调器锁的旧观察不能再写快照或提交故障`() = runTest {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val source = MutableStateFlow(WearableLinkObservation())
        val entered = CountDownLatch(1)
        val emitted = CountDownLatch(1)
        val observations = object : StateFlow<WearableLinkObservation> by source {
            override suspend fun collect(collector: FlowCollector<WearableLinkObservation>): Nothing =
                source.collect(FlowCollector { observation ->
                    if (observation.generation == 1L) entered.countDown()
                    collector.emit(observation)
                    if (observation.generation == 1L) emitted.countDown()
                })
        }
        val sensor = FakeSensorDataSource(
            initialWearableState = ConnectionState.CONNECTED,
            wearableLinkObservation = observations,
        )
        val bus = SpeechBus(FakeTtsEngine(), dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(
            FakeRunRepository(), sensor, speechBus = bus,
            coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        try {
            coordinator.awaitRestoration()
            assertTrue(coordinator.startRun() is CommandOutcome.Accepted)
            runCurrent()
            withContext(dispatcher) { }
            val closedRevision = synchronized(coordinator) {
                source.value = lostLink()
                assertTrue("观察应已抵达被占用的协调器锁", entered.await(5, TimeUnit.SECONDS))
                coordinator.close()
                source.value = lostLink().copy(generation = 2)
                sensor.advanceClockTo(20_000)
                coordinator.snapshot.value.revision
            }
            assertTrue("等待中的观察应已返回", emitted.await(5, TimeUnit.SECONDS))
            withContext(dispatcher) { }
            assertEquals(closedRevision, coordinator.snapshot.value.revision)
            runCurrent()
        } finally {
            coordinator.close()
            runCurrent()
            dispatcher.close()
        }
    }

    @Test fun `无GPS帧时真实手环故障连续十秒仅播一次且恢复立即取消`() = runTest {
        val fixture = linkFixture()
        fixture.observations.value = lostLink()
        runCurrent()
        fixture.sensor.advanceClockTo(9_999)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, fixture.tts.requests.size)
        fixture.sensor.advanceClockTo(10_000)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("手环数据中断，请检查", fixture.tts.requests.last().text)
        assertEquals(2, fixture.tts.requests.size)
        assertTrue(fixture.tts.isSpeaking)
        fixture.observations.value = WearableLinkObservation(1, WearableLinkPhase.READY, 2, 10_000)
        runCurrent()
        assertTrue(!fixture.tts.isSpeaking)
        assertNull(fixture.coordinator.snapshot.value.latestCoachMessage?.takeIf { it.text == "手环数据中断，请检查" })
        fixture.observations.value = lostLink(2, 2, 1, 11_000)
        runCurrent()
        fixture.sensor.advanceClockTo(21_000)
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(2, fixture.tts.requests.size)
        fixture.coordinator.close()
        runCurrent()
    }

    @Test fun `暂停时仍监听真实故障且恢复后未满十秒不播`() = runTest {
        val fixture = linkFixture()
        fixture.coordinator.pauseRun()
        runCurrent()
        fixture.tts.completeNextSuccessfully()
        runCurrent()
        fixture.observations.value = lostLink()
        runCurrent()
        fixture.sensor.advanceClockTo(10_000)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("手环数据中断，请检查", fixture.tts.requests.last().text)
        fixture.coordinator.close()
        runCurrent()
    }

    @Test fun `恢复连接发生在资格定时器到期前不产生迟到通知`() = runTest {
        val fixture = linkFixture()
        fixture.observations.value = lostLink()
        runCurrent()
        fixture.sensor.advanceClockTo(9_999)
        advanceTimeBy(9_999)
        fixture.observations.value = WearableLinkObservation(1, WearableLinkPhase.READY, 2, 9_999)
        fixture.sensor.advanceClockTo(10_000)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fixture.tts.requests.size)
        fixture.coordinator.close()
        runCurrent()
    }

    @Test fun `恢复进程建立基线不补播旧故障但新Ready后的故障可播`() = runTest {
        val fixture = linkFixture(
            RunSession(id = "restored", startTimeMillis = 1_000, runState = RunState.PAUSED),
            lostLink(),
        )
        fixture.sensor.advanceClockTo(20_000)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(fixture.tts.requests.isEmpty())
        fixture.observations.value = lostLink(2, 2, 1, 20_000)
        runCurrent()
        fixture.sensor.advanceClockTo(30_000)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("手环数据中断，请检查"), fixture.tts.requests.map { it.text })
        fixture.coordinator.close()
        runCurrent()
    }

    @Test fun `停止和新跑取消旧资格定时器与通知`() = runTest {
        val fixture = linkFixture()
        fixture.observations.value = lostLink()
        runCurrent()
        fixture.coordinator.stopRun()
        runCurrent()
        fixture.tts.completeNextSuccessfully()
        runCurrent()
        fixture.coordinator.startRun()
        runCurrent()
        fixture.tts.completeNextSuccessfully()
        runCurrent()
        fixture.sensor.advanceClockTo(20_000)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(fixture.tts.requests.none { it.text == "手环数据中断，请检查" })
        fixture.coordinator.close()
        runCurrent()
    }

    @Test fun `仅定位或心率陈旧都不能派发手环中断规则`() = runTest {
        for (type in listOf(SampleType.HEART_RATE, SampleType.DISTANCE)) {
            val sensor = FakeSensorDataSource(initialWearableState = ConnectionState.CONNECTED)
            val logger = FakeDiagnosticLogger()
            val coordinator = createCoordinator(
                FakeRunRepository(), sensor,
                ruleEngine = RuleEngine(RuleCatalog.defaultRules(), RunConfig(), diagnosticLogger = logger),
            )
            coordinator.startRun()
            runCurrent()
            repeat(15) { index ->
                val time = index * 1_000L
                sensor.emitSample(type, if (type == SampleType.HEART_RATE) 140.0 else index * 2.0, time)
                sensor.emitTick(time)
                runCurrent()
            }
            assertTrue(logger.events.filterIsInstance<DiagnosticEvent.RuleTriggered>().none { it.ruleId == "data_stale" })
            coordinator.close()
            runCurrent()
        }
    }

    private data class LinkFixture(
        val sensor: FakeSensorDataSource,
        val observations: MutableStateFlow<WearableLinkObservation>,
        val tts: FakeTtsEngine,
        val coordinator: AppCoordinator,
    )

    private fun kotlinx.coroutines.test.TestScope.linkFixture(
        activeSession: RunSession? = null,
        baseline: WearableLinkObservation = WearableLinkObservation(),
    ): LinkFixture {
        val observations = MutableStateFlow(baseline)
        val sensor = FakeSensorDataSource(
            wearableInfo = DeviceInfo("Keep B5"),
            initialWearableState = ConnectionState.CONNECTED,
            wearableLinkObservation = observations,
        )
        val tts = FakeTtsEngine()
        val bus = SpeechBus(tts, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(FakeRunRepository(activeSession), sensor, speechBus = bus)
        if (activeSession == null) {
            coordinator.startRun()
            runCurrent()
            tts.completeNextSuccessfully()
            runCurrent()
        }
        return LinkFixture(sensor, observations, tts, coordinator)
    }

    private fun lostLink(readyRevision: Long = 1, id: Long = 1, episode: Long = 1, at: Long = 0) =
        WearableLinkObservation(1, WearableLinkPhase.RECOVERING, readyRevision, 0, WearableLinkFault(id, episode, at))

    @Test
    fun `运行与暂停中保存警戒偏好不改变本跑而下一跑使用新阈值和间隔`() = runTest {
        val clock = TestTimeSource()
        val sensor = FakeSensorDataSource()
        val logger = FakeDiagnosticLogger()
        val settings = SettingsDataStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        })
        settings.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Set(160)))
        val coordinator = createCoordinator(
            FakeRunRepository(), sensor, settingsRepository = settings,
            ruleEngine = RuleEngine(listOf(HrZone5Rule(clock)), RunConfig(), diagnosticLogger = logger),
        )
        var elapsedMillis = 0L
        suspend fun sample(hr: Double = 165.0) {
            clock += 1.seconds
            elapsedMillis += 1000L
            sensor.emitSample(SampleType.HEART_RATE, hr, elapsedMillis)
            sensor.emitTick(elapsedMillis)
            runCurrent()
        }
        fun alerts() = logger.events.filterIsInstance<DiagnosticEvent.RuleTriggered>().count { it.ruleId == "hr_z5_danger" }
        coordinator.startRun()
        runCurrent()
        repeat(11) { sample() }
        assertEquals(1, alerts())
        assertTrue(coordinator.updateSettings(SettingsPatch(
            hrAlertThresholdBpm = NullableSettingUpdate.Set(180), hrAlertRepeatIntervalSec = 30,
        )) is CommandOutcome.AcceptedWithData)
        assertEquals(180, coordinator.snapshot.value.settings.hrAlertThresholdBpm)
        repeat(119) { sample() }
        assertEquals(1, alerts())
        sample()
        assertEquals(2, alerts())
        coordinator.pauseRun()
        runCurrent()
        coordinator.updateSettings(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Set(190), hrAlertRepeatIntervalSec = 60))
        coordinator.resumeRun()
        runCurrent()
        repeat(119) { sample() }
        assertEquals(2, alerts())
        sample()
        assertEquals(3, alerts())
        coordinator.stopRun()
        runCurrent()
        coordinator.startRun()
        runCurrent()
        repeat(20) { sample(185.0) }
        assertEquals(3, alerts())
        // 经真实平滑管线达到新阈值并连续十秒后，才有下一次事件。
        var observedNewAlert = false
        repeat(30) {
            if (!observedNewAlert) {
                sample(200.0)
                observedNewAlert = alerts() == 4
            }
        }
        assertTrue(observedNewAlert)
        repeat(59) { sample(200.0) }
        assertEquals(4, alerts())
        sample(200.0)
        assertEquals(5, alerts())
        coordinator.close()
    }

    @Test
    fun `短暂停后必须重新连续十秒才产生高心率事件`() = runTest {
        val clock = TestTimeSource()
        val sensor = FakeSensorDataSource()
        val logger = FakeDiagnosticLogger()
        val engine = RuleEngine(listOf(HrZone5Rule(clock)), RunConfig(), diagnosticLogger = logger)
        val coordinator = createCoordinator(FakeRunRepository(), sensor, ruleEngine = engine)
        coordinator.startRun()
        runCurrent()
        suspend fun highAt(second: Int) {
            sensor.emitSample(SampleType.HEART_RATE, 170.0, second * 1000L)
            sensor.emitTick(second * 1000L)
            runCurrent()
        }
        for (second in 0..9) {
            highAt(second)
            clock += 1.seconds
        }
        coordinator.pauseRun()
        runCurrent()
        coordinator.resumeRun()
        runCurrent()
        for (second in 10..19) {
            highAt(second)
            assertTrue(logger.events.filterIsInstance<DiagnosticEvent.RuleTriggered>().isEmpty())
            clock += 1.seconds
        }
        highAt(20)
        assertEquals(listOf("hr_z5_danger"), logger.events.filterIsInstance<DiagnosticEvent.RuleTriggered>().map { it.ruleId })
        coordinator.close()
    }

    @Test
    fun `开跑前手动连接仅准备手环且不建记录计时或播报`() = runTest {
        val sensor = FakeSensorDataSource(initialWearableState = ConnectionState.DISCONNECTED)
        val repository = FakeRunRepository()
        val tts = FakeTtsEngine()
        val coordinator = createCoordinator(
            repository, sensor, speechBus = SpeechBus(tts, dispatcher = StandardTestDispatcher(testScheduler)),
        )
        assertTrue(coordinator.connectSensor() is CommandOutcome.Accepted)
        runCurrent()
        assertEquals(1, sensor.prepareCalls)
        assertEquals(0, sensor.connectCalls)
        assertTrue(sensor.runLifecycleEvents.isEmpty())
        assertTrue(repository.checkpoints.isEmpty())
        assertEquals("idle", coordinator.snapshot.value.session.state)
        assertEquals("connected", coordinator.snapshot.value.wearable.state)
        assertEquals("disconnected", coordinator.snapshot.value.connection.state)
        assertEquals(0, coordinator.snapshot.value.metrics.elapsedSeconds)
        assertTrue(tts.requests.isEmpty())
    }

    @Test
    fun `手环预连接未完成时开跑会在串行连接后启用手机采样`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sensor = FakeSensorDataSource(initialWearableState = ConnectionState.DISCONNECTED, prepareGate = gate)
        val coordinator = createCoordinator(FakeRunRepository(), sensor)
        coordinator.connectSensor()
        coordinator.connectSensor()
        runCurrent()
        assertEquals(1, sensor.prepareCalls)
        coordinator.startRun()
        runCurrent()
        assertEquals(0, sensor.connectCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, sensor.connectCalls)
        assertEquals(1, sensor.maxConcurrentOperations)
        assertEquals("running", coordinator.snapshot.value.session.state)
        sensor.emitDistance(1_000L, 100.0)
        sensor.emitDistance(2_000L, 110.0)
        runCurrent()
        assertEquals(10.0, coordinator.snapshot.value.metrics.distanceMeters, 0.01)
    }

    @Test
    fun `取消手环预连接不创建运动且不启动手机`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sensor = FakeSensorDataSource(initialWearableState = ConnectionState.DISCONNECTED, prepareGate = gate)
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(repository, sensor)
        coordinator.connectSensor()
        runCurrent()
        coordinator.disconnectSensor()
        runCurrent()
        assertEquals(1, sensor.disconnectCalls)
        assertEquals(0, sensor.connectCalls)
        assertEquals(1, sensor.maxConcurrentOperations)
        assertTrue(repository.checkpoints.isEmpty())
    }

    @Test
    fun `手环得到手机累计指标且定位陈旧时配速为空`() = runTest {
        val sensor = FakeSensorDataSource()
        val coordinator = createCoordinator(FakeRunRepository(), sensor)
        coordinator.startRun()
        runCurrent()
        sensor.emitDistance(1_000L, 100.0)
        sensor.emitDistance(2_000L, 110.0)
        sensor.emitDistance(3_000L, 120.0)
        runCurrent()
        val metrics = coordinator.snapshot.value.metrics
        assertEquals(metrics.distanceMeters to metrics.paceSecPerKm, sensor.displayMetrics.last())
        assertEquals(20.0, sensor.displayMetrics.last().first, 0.01)
        assertTrue(sensor.displayMetrics.last().second != null)
        sensor.emitTick(9_000L)
        runCurrent()
        assertEquals(20.0 to null, sensor.displayMetrics.last())
    }

    @Test
    fun `已接受的四条会话命令依次播报且拒绝命令不产生请求`() = runTest {
        val ttsEngine = FakeTtsEngine()
        val speechBus = SpeechBus(
            ttsEngine = ttsEngine,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            speechBus = speechBus,
        )
        val initialSpeechGeneration = speechBus.sessionGenerationToken()

        assertEquals(CommandOutcome.Accepted, coordinator.startRun())
        assertEquals(initialSpeechGeneration + 1, speechBus.sessionGenerationToken())
        runCurrent()
        ttsEngine.completeNextSuccessfully()
        runCurrent()

        val afterAcceptedStartGeneration = speechBus.sessionGenerationToken()
        val requestCountAfterStart = ttsEngine.requests.size
        assertTrue(coordinator.startRun() is CommandOutcome.Rejected)
        assertEquals(afterAcceptedStartGeneration, speechBus.sessionGenerationToken())
        assertEquals(requestCountAfterStart, ttsEngine.requests.size)

        val beforePauseGeneration = speechBus.sessionGenerationToken()
        assertEquals(CommandOutcome.Accepted, coordinator.pauseRun())
        assertEquals(beforePauseGeneration, speechBus.sessionGenerationToken())
        runCurrent()
        ttsEngine.completeNextSuccessfully()
        runCurrent()
        val requestCountAfterPause = ttsEngine.requests.size
        assertTrue(coordinator.pauseRun() is CommandOutcome.Rejected)
        assertEquals(requestCountAfterPause, ttsEngine.requests.size)

        val beforeResumeGeneration = speechBus.sessionGenerationToken()
        assertEquals(CommandOutcome.Accepted, coordinator.resumeRun())
        assertEquals(beforeResumeGeneration, speechBus.sessionGenerationToken())
        runCurrent()
        ttsEngine.completeNextSuccessfully()
        runCurrent()
        val requestCountAfterResume = ttsEngine.requests.size
        assertTrue(coordinator.resumeRun() is CommandOutcome.Rejected)
        assertEquals(requestCountAfterResume, ttsEngine.requests.size)

        val beforeStopGeneration = speechBus.sessionGenerationToken()
        assertEquals(CommandOutcome.Accepted, coordinator.stopRun())
        assertEquals(beforeStopGeneration, speechBus.sessionGenerationToken())
        runCurrent()
        ttsEngine.completeNextSuccessfully()
        runCurrent()
        val requestCountAfterStop = ttsEngine.requests.size
        assertTrue(coordinator.stopRun() is CommandOutcome.Rejected)
        assertEquals(requestCountAfterStop, ttsEngine.requests.size)

        assertEquals(
            listOf(
                "流萤已开始记录，出发吧。",
                "跑步已暂停。",
                "跑步已恢复。",
                "跑步已结束。",
            ),
            ttsEngine.completedTexts,
        )

        coordinator.close()
        runCurrent()
        assertEquals(1, ttsEngine.shutdownCalls)
    }

    @Test
    fun `关闭播报设置时四条已接受命令只记录策略禁用`() = runTest {
        val config = RunConfig(llmEnabled = false, ttsEnabled = false)
        val ttsEngine = FakeTtsEngine()
        val diagnosticLogger = FakeDiagnosticLogger()
        val speechBus = SpeechBus(
            ttsEngine = ttsEngine,
            diagnosticLogger = diagnosticLogger,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            settingsRepository = FakeSettingsRepository(
                SettingsSnapshot(ttsEnabled = false, llmEnabled = false),
            ),
            config = config,
            speechBus = speechBus,
        )

        assertEquals(CommandOutcome.Accepted, coordinator.startRun())
        assertEquals(CommandOutcome.Accepted, coordinator.pauseRun())
        assertEquals(CommandOutcome.Accepted, coordinator.resumeRun())
        assertEquals(CommandOutcome.Accepted, coordinator.stopRun())
        runCurrent()

        assertTrue(ttsEngine.requests.isEmpty())
        val outcomes = diagnosticLogger.events.filterIsInstance<DiagnosticEvent.SpeechOutcome>()
        assertEquals(4, outcomes.count { it.reason == SpeechOutcomeReason.POLICY_DISABLED })
        assertEquals(0, outcomes.count { it.reason == SpeechOutcomeReason.SESSION_STATUS_SCHEDULED })
    }

    @Test
    fun `暂停时只更新心率并在接收满五秒时清空`() = runTest {
        val sensor = FakeSensorDataSource()
        val monitor = PausedHeartRateMonitor(
            tickSource = sensor.ticks,
            monotonicClockMillis = { sensor.monotonicMillis },
        )
        val countingRule = CountingRule()
        val sessionManager = RunSessionManager()
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            sensorDataSource = sensor,
            ruleEngine = RuleEngine(listOf(countingRule), RunConfig(llmEnabled = false)),
            sessionManager = sessionManager,
            pausedHeartRateMonitor = monitor,
        )
        coordinator.startRun()
        runCurrent()
        sensor.emitSample(SampleType.HEART_RATE, 150.0, 0L)
        sensor.emitSample(SampleType.CADENCE, 170.0, 0L)
        sensor.emitSample(SampleType.DISTANCE, 100.0, 0L)
        sensor.emitTick(0L)
        runCurrent()

        coordinator.pauseRun()
        runCurrent()
        val pausedMetrics = coordinator.snapshot.value.metrics
        val pausedSplits = sessionManager.currentSession?.splits?.size
        val pausedEvaluateCalls = countingRule.evaluateCalls

        sensor.emitSample(SampleType.DISTANCE, 900.0, 1_000L)
        sensor.emitSample(SampleType.CADENCE, 190.0, 1_000L)
        sensor.emitSample(SampleType.HEART_RATE, 160.0, 1_000L)
        sensor.emitTick(1_000L)
        runCurrent()

        assertEquals(160, coordinator.snapshot.value.metrics.heartRate)
        assertEquals("z4", coordinator.snapshot.value.metrics.hrZone)
        assertEquals(
            pausedMetrics,
            coordinator.snapshot.value.metrics.copy(heartRate = pausedMetrics.heartRate, hrZone = pausedMetrics.hrZone),
        )
        assertEquals(pausedSplits, sessionManager.currentSession?.splits?.size)
        assertEquals(pausedEvaluateCalls, countingRule.evaluateCalls)

        sensor.emitTick(5_999L)
        runCurrent()
        assertEquals(160, coordinator.snapshot.value.metrics.heartRate)
        sensor.emitTick(6_000L)
        runCurrent()
        assertEquals(null, coordinator.snapshot.value.metrics.heartRate)
        assertEquals(null, coordinator.snapshot.value.metrics.hrZone)
        assertEquals(pausedEvaluateCalls, countingRule.evaluateCalls)
    }

    @Test
    fun `暂停交接保留原心率时钟与本次最大心率配置`() = runTest {
        val sensor = FakeSensorDataSource()
        val monitor = PausedHeartRateMonitor(
            tickSource = sensor.ticks,
            monotonicClockMillis = { sensor.monotonicMillis },
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            sensorDataSource = sensor,
            settingsRepository = FakeSettingsRepository(
                SettingsSnapshot(maxHeartRate = 200, llmEnabled = false),
            ),
            config = RunConfig(maxHeartRate = 180, llmEnabled = false),
            pausedHeartRateMonitor = monitor,
        )
        coordinator.startRun()
        runCurrent()
        sensor.emitSample(SampleType.HEART_RATE, 150.0, 0L)
        sensor.emitTick(0L)
        runCurrent()
        assertEquals("z3", coordinator.snapshot.value.metrics.hrZone)

        sensor.advanceClockTo(4_999L)
        val runningGeneration = coordinator.pipelineGenerationToken()
        coordinator.pauseRun()
        runCurrent()
        assertEquals(150, coordinator.snapshot.value.metrics.heartRate)
        assertEquals("z3", coordinator.snapshot.value.metrics.hrZone)

        listOf(0.0, 301.0, Double.NaN, Double.POSITIVE_INFINITY, 150.5).forEach { value ->
            sensor.emitSample(SampleType.HEART_RATE, value, 4_999L)
        }
        monitor.recordRunningSample(
            runningGeneration,
            SensorSample(SampleType.HEART_RATE, 170.0, 4_999L, "late"),
            4_999L,
        )
        sensor.emitTick(5_000L)
        runCurrent()
        assertEquals(null, coordinator.snapshot.value.metrics.heartRate)
        assertEquals(null, coordinator.snapshot.value.metrics.hrZone)

        sensor.emitSample(SampleType.HEART_RATE, 150.0, 5_000L)
        runCurrent()
        assertEquals(150, coordinator.snapshot.value.metrics.heartRate)
        assertEquals("z3", coordinator.snapshot.value.metrics.hrZone)
    }

    @Test
    fun `过期 seed 首轮清空且所有生命周期方法原子失效旧 generation`() = runTest {
        val sensor = FakeSensorDataSource()
        val monitor = PausedHeartRateMonitor(
            tickSource = sensor.ticks,
            monotonicClockMillis = { sensor.monotonicMillis },
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            sensorDataSource = sensor,
            pausedHeartRateMonitor = monitor,
        )
        val lifecycleNames = setOf("startRun", "pauseRun", "resumeRun", "stopRun", "close")
        lifecycleNames.forEach { name ->
            val method = AppCoordinator::class.java.declaredMethods.single { it.name == name }
            assertTrue("$name 必须持有同一个协调器 monitor", Modifier.isSynchronized(method.modifiers))
        }
        val pausedProcessor = AppCoordinator::class.java.declaredMethods.single {
            it.name.startsWith("processPausedHeartRate") && !Modifier.isStatic(it.modifiers)
        }
        assertTrue(Modifier.isSynchronized(pausedProcessor.modifiers))

        val beforeStart = coordinator.pipelineGenerationToken()
        coordinator.startRun()
        runCurrent()
        assertTrue(coordinator.pipelineGenerationToken() != beforeStart)
        sensor.emitSample(SampleType.HEART_RATE, 150.0, 0L)
        sensor.emitTick(0L)
        runCurrent()
        sensor.advanceClockTo(5_000L)

        val beforePause = coordinator.pipelineGenerationToken()
        coordinator.pauseRun()
        runCurrent()
        val pausedGeneration = coordinator.pipelineGenerationToken()
        assertTrue(pausedGeneration != beforePause)
        assertEquals(null, coordinator.snapshot.value.metrics.heartRate)

        synchronized(coordinator) {
            coordinator.resumeRun()
            coordinator.processPausedHeartRate(
                PausedHeartRateState(170, HrZone.Z5),
                pausedGeneration,
            )
        }
        assertEquals(null, coordinator.snapshot.value.metrics.heartRate)
        val afterResume = coordinator.pipelineGenerationToken()
        assertTrue(afterResume != pausedGeneration)

        coordinator.stopRun()
        val afterStop = coordinator.pipelineGenerationToken()
        assertTrue(afterStop != afterResume)
        coordinator.close()
        assertTrue(coordinator.pipelineGenerationToken() != afterStop)
    }

    @Test
    fun `权威 Split 在长于规则窗口时仍只播报一次`() = runTest {
        val sensor = FakeSensorDataSource()
        val config = RunConfig(llmEnabled = true, ttsMinIntervalSec = 0)
        val ttsEngine = FakeTtsEngine()
        val requests = mutableListOf<SpeechRequest>()
        var independentAnalysisCalls = 0
        val speechBus = SpeechBus(
            ttsEngine = object : TtsEngine by ttsEngine {
                override suspend fun speak(request: SpeechRequest): Result<SpeechDelivery> {
                    requests += request
                    return ttsEngine.speak(request)
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val ruleEngine = RuleEngine(
            rules = RuleCatalog.defaultRules(),
            initialConfig = config,
            nowMillis = { sensor.monotonicMillis },
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            sensorDataSource = sensor,
            config = config,
            speechBus = speechBus,
            ruleEngine = ruleEngine,
            settingsRepository = FakeSettingsRepository(SettingsSnapshot(llmEnabled = true, ttsMinIntervalSec = 0)),
            llmClient = object : LlmClient {
                override suspend fun analyze(request: LlmAnalyzeRequest): Result<LlmAnalyzeResponse> {
                    independentAnalysisCalls++
                    return Result.success(LlmAnalyzeResponse("不应独立播出。"))
                }
            },
        )
        coordinator.startRun()
        runCurrent()
        ttsEngine.completeNextSuccessfully()
        runCurrent()
        val generation = coordinator.pipelineGenerationToken()
        val processFrame = AppCoordinator::class.java.getDeclaredMethod(
            "processFrame",
            MetricsFrame::class.java,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }

        fun acceptFrame(elapsedMillis: Long, distanceMeters: Double) {
            sensor.advanceClockTo(elapsedMillis)
            processFrame.invoke(
                coordinator,
                MetricsFrame(
                    timestampMillis = elapsedMillis,
                    elapsedSeconds = elapsedMillis / 1_000L,
                    heartRate = 140,
                    heartRateSmoothed = 140,
                    hrZone = HrZone.Z3,
                    cadence = 170,
                    strideLengthCm = 100.0f,
                    currentPaceSecPerKm = 400.0,
                    distanceMeters = distanceMeters,
                    splitDistanceMeters = distanceMeters,
                    totalSteps = elapsedMillis / 400L,
                    isDataFresh = true,
                    hrPaceRatio = 0.35,
                    isHeartRateFresh = true,
                ),
                generation,
            )
        }

        acceptFrame(0L, 0.0)
        for (index in 1..119) {
            acceptFrame(
                elapsedMillis = 400_000L * index / 120L,
                distanceMeters = 1_000.0 * index / 120.0,
            )
        }
        acceptFrame(400_000L, 1_000.0)
        runCurrent()

        val expected = "第一公里，用时六分四十秒，配速每公里六分四十秒。"
        assertEquals(1, ttsEngine.requests.count { it.text == expected })
        val kilometer = requests.single { it.trigger.ruleId == "km_split" }
        assertTrue(kilometer.companionEnabled)
        assertEquals(1, kilometer.analysis?.split_index)
        assertEquals(400L, kilometer.analysis?.split_data?.duration_sec)
        assertEquals(400.0, kilometer.analysis?.split_data?.avg_pace_sec_per_km)
        assertEquals(0, independentAnalysisCalls)

        ttsEngine.completeNextSuccessfully()
        runCurrent()
        acceptFrame(401_000L, 1_001.0)
        runCurrent()
        assertEquals(1, ttsEngine.requests.count { it.text == expected })
        assertEquals(0, independentAnalysisCalls)
        assertTrue(requests.none { it.trigger.ruleId == "llm_coach" })
    }

    @Test
    fun `结束先通知传感器再登记播报且终态后仅由 SpeechBus flush`() = runTest {
        val rejectedLogger = TraceDiagnosticLogger(mutableListOf())
        val rejectedCoordinator = createCoordinator(
            repository = FakeRunRepository(),
            speechBus = SpeechBus(
                ttsEngine = FakeTtsEngine(),
                diagnosticLogger = rejectedLogger,
                dispatcher = StandardTestDispatcher(testScheduler),
            ),
        )
        assertTrue(rejectedCoordinator.stopRun() is CommandOutcome.Rejected)
        runCurrent()
        assertEquals(0, rejectedLogger.flushCalls)

        val trace = mutableListOf<String>()
        val flushGate = CompletableDeferred<Unit>()
        val diagnosticLogger = TraceDiagnosticLogger(trace, flushGate)
        val ttsEngine = FakeTtsEngine()
        val speechBus = SpeechBus(
            ttsEngine = ttsEngine,
            diagnosticLogger = diagnosticLogger,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val sensor = FakeSensorDataSource(trace = trace)
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(
            repository = repository,
            sensorDataSource = sensor,
            speechBus = speechBus,
        )
        coordinator.startRun()
        runCurrent()
        ttsEngine.completeNextSuccessfully()
        runCurrent()
        trace.clear()

        assertEquals(CommandOutcome.Accepted, coordinator.stopRun())
        val sensorStopped = trace.indexOf("sensor:onRunStopped")
        val finishedScheduled = trace.indexOf(
            "speech:session_finished:${SpeechOutcomeReason.SESSION_STATUS_SCHEDULED}",
        )
        assertTrue(sensorStopped >= 0)
        assertTrue(finishedScheduled > sensorStopped)
        assertEquals(0, diagnosticLogger.flushCalls)

        runCurrent()
        assertEquals(1, repository.savedSessions.size)
        assertEquals(0, diagnosticLogger.flushCalls)
        ttsEngine.completeNextSuccessfully()
        runCurrent()

        val finishedSucceeded = trace.indexOf(
            "speech:session_finished:${SpeechOutcomeReason.TTS_SUCCESS}",
        )
        val terminalFlush = trace.indexOf("flush")
        assertTrue(finishedSucceeded >= 0)
        assertTrue(terminalFlush > finishedSucceeded)
        assertEquals(1, diagnosticLogger.flushCalls)
        assertEquals(CommandOutcome.Accepted, coordinator.startRun())

        flushGate.complete(Unit)
        runCurrent()
        coordinator.close()
        runCurrent()
    }

    @Test
    fun `禁用播报时结束策略终态仍异步安排一次 SpeechBus flush`() = runTest {
        val trace = mutableListOf<String>()
        val flushGate = CompletableDeferred<Unit>()
        val diagnosticLogger = TraceDiagnosticLogger(trace, flushGate)
        val ttsEngine = FakeTtsEngine()
        val speechBus = SpeechBus(
            ttsEngine = ttsEngine,
            diagnosticLogger = diagnosticLogger,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val config = RunConfig(llmEnabled = false, ttsEnabled = false)
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(
            repository = repository,
            sensorDataSource = FakeSensorDataSource(trace = trace),
            settingsRepository = FakeSettingsRepository(
                SettingsSnapshot(llmEnabled = false, ttsEnabled = false),
            ),
            config = config,
            speechBus = speechBus,
        )
        coordinator.startRun()
        coordinator.pauseRun()
        coordinator.resumeRun()
        trace.clear()

        assertEquals(CommandOutcome.Accepted, coordinator.stopRun())
        assertTrue(
            trace.indexOf("speech:session_finished:${SpeechOutcomeReason.POLICY_DISABLED}") >
                trace.indexOf("sensor:onRunStopped"),
        )
        assertEquals(0, diagnosticLogger.flushCalls)
        assertTrue(ttsEngine.requests.isEmpty())

        runCurrent()
        assertEquals(1, diagnosticLogger.flushCalls)
        assertEquals(1, repository.savedSessions.size)
        flushGate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `开始暂停继续结束由协调器维护且结束后保存`() = runTest {
        val repository = FakeRunRepository()
        val healthSync = FakeHealthConnectSync()
        val coordinator = createCoordinator(repository, healthConnectSync = healthSync)

        assertEquals(CommandOutcome.Accepted, coordinator.startRun())
        assertEquals("running", coordinator.snapshot.value.session.state)
        assertTrue(coordinator.snapshot.value.session.id != null)

        assertEquals(CommandOutcome.Accepted, coordinator.pauseRun())
        assertEquals("paused", coordinator.snapshot.value.session.state)

        assertEquals(CommandOutcome.Accepted, coordinator.resumeRun())
        assertEquals("running", coordinator.snapshot.value.session.state)

        assertEquals(CommandOutcome.Accepted, coordinator.stopRun())
        assertEquals("saving", coordinator.snapshot.value.persistenceState)
        assertTrue(coordinator.snapshot.value.completedRun != null)
        assertEquals("idle", coordinator.snapshot.value.healthSync.state)
        runCurrent()

        assertEquals("finished", coordinator.snapshot.value.session.state)
        assertEquals(1, repository.savedSessions.size)
        assertEquals("saved", coordinator.snapshot.value.persistenceState)
        assertEquals("synced", coordinator.snapshot.value.healthSync.state)
        assertEquals(false, coordinator.snapshot.value.healthSync.routeWritten)
        assertEquals(1, healthSync.syncedSessions.size)
    }

    @Test
    fun `本地保存失败不会调用 Health Connect`() = runTest {
        val repository = FakeRunRepository(saveError = IllegalStateException("磁盘不可用"))
        val healthSync = FakeHealthConnectSync()
        val coordinator = createCoordinator(repository, healthConnectSync = healthSync)
        coordinator.startRun()

        coordinator.stopRun()
        runCurrent()

        assertEquals("failed", coordinator.snapshot.value.persistenceState)
        assertEquals("idle", coordinator.snapshot.value.healthSync.state)
        assertTrue(healthSync.syncedSessions.isEmpty())
    }

    @Test
    fun `Health Connect 缺权限不会反转本地保存成功`() = runTest {
        val healthSync = FakeHealthConnectSync(
            result = HealthConnectSyncResult.PermissionMissing(setOf("write.exercise")),
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            healthConnectSync = healthSync,
        )
        coordinator.startRun()

        coordinator.stopRun()
        runCurrent()

        assertEquals("saved", coordinator.snapshot.value.persistenceState)
        assertEquals("permissionRequired", coordinator.snapshot.value.healthSync.state)
        assertEquals(null, coordinator.snapshot.value.healthSync.routeWritten)
    }

    @Test
    fun `Health Connect 全部结果映射为稳定合同状态`() = runTest {
        val cases = listOf(
            Triple(HealthConnectSyncResult.Unavailable, "unavailable", null),
            Triple(
                HealthConnectSyncResult.ProviderUpdateRequired,
                "providerUpdateRequired",
                null,
            ),
            Triple(
                HealthConnectSyncResult.InvalidRun(
                    HealthConnectSyncResult.InvalidRunReason.NOT_FINISHED,
                ),
                "failed",
                null,
            ),
            Triple(
                HealthConnectSyncResult.Failed(
                    HealthConnectSyncResult.FailureStage.WRITE_RECORDS,
                    "IOException",
                ),
                "failed",
                null,
            ),
            Triple(HealthConnectSyncResult.Synced(true, 2), "synced", true),
        )

        cases.forEach { (result, expectedState, expectedRouteWritten) ->
            val coordinator = createCoordinator(
                repository = FakeRunRepository(),
                healthConnectSync = FakeHealthConnectSync(result),
            )
            coordinator.startRun()
            coordinator.stopRun()
            runCurrent()

            assertEquals("saved", coordinator.snapshot.value.persistenceState)
            assertEquals(expectedState, coordinator.snapshot.value.healthSync.state)
            assertEquals(expectedRouteWritten, coordinator.snapshot.value.healthSync.routeWritten)
        }
    }

    @Test
    fun `本地保存完成后才进入独立健康同步状态`() = runTest {
        val syncGate = CompletableDeferred<Unit>()
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            healthConnectSync = FakeHealthConnectSync(syncGate = syncGate),
        )
        coordinator.startRun()
        coordinator.stopRun()

        runCurrent()
        assertEquals("saved", coordinator.snapshot.value.persistenceState)
        assertEquals("syncing", coordinator.snapshot.value.healthSync.state)

        syncGate.complete(Unit)
        runCurrent()
        assertEquals("synced", coordinator.snapshot.value.healthSync.state)
    }

    @Test
    fun `开始新跑会清空上一条完成与同步状态`() = runTest {
        val coordinator = createCoordinator(FakeRunRepository())
        coordinator.startRun()
        coordinator.stopRun()
        runCurrent()
        assertTrue(coordinator.snapshot.value.completedRun != null)

        coordinator.startRun()

        assertEquals(null, coordinator.snapshot.value.completedRun)
        assertEquals("idle", coordinator.snapshot.value.persistenceState)
        assertEquals("idle", coordinator.snapshot.value.healthSync.state)
    }

    @Test
    fun `重复开始返回会话冲突且不替换会话`() = runTest {
        val coordinator = createCoordinator(FakeRunRepository())
        coordinator.startRun()
        val sessionId = coordinator.snapshot.value.session.id

        val result = coordinator.startRun()

        assertTrue(result is CommandOutcome.Rejected)
        assertEquals(sessionId, coordinator.snapshot.value.session.id)
    }

    @Test
    fun `历史查询返回真实分页摘要且不改变快照`() = runTest {
        val repository = FakeRunRepository()
        repeat(12) { index ->
            repository.savedSessions += RunSession(
                id = "run-$index",
                startTimeMillis = 1_000L + index,
                endTimeMillis = 2_000L + index,
                totalDistanceMeters = 5_000.0 + index,
                totalDurationSec = 1_800L + index,
                avgHeartRate = 140 + index,
                avgCadence = 170 + index,
                avgPaceSecPerKm = 360.0 + index,
                runState = RunState.FINISHED,
            )
        }
        val coordinator = createCoordinator(repository)
        val revision = coordinator.snapshot.value.revision

        val outcome = coordinator.queryHistory(page = 2, pageSize = 10)

        assertTrue(outcome is CommandOutcome.AcceptedWithData)
        val data = (outcome as CommandOutcome.AcceptedWithData).data
        assertEquals(10, repository.lastHistoryOffset)
        assertEquals(10, repository.lastHistoryLimit)
        assertEquals(12, data.getValue("totalItems").jsonPrimitive.content.toInt())
        assertEquals(false, data.getValue("hasNextPage").jsonPrimitive.content.toBoolean())
        assertEquals(2, data.getValue("items").jsonArray.size)
        val first = data.getValue("items").jsonArray.first().jsonObject
        assertEquals(
            setOf(
                "id",
                "startedAt",
                "endedAt",
                "distanceMeters",
                "durationSeconds",
                "averagePaceSecPerKm",
                "averageHeartRate",
                "averageCadence",
            ),
            first.keys,
        )
        assertEquals("run-10", first.getValue("id").jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:01.010Z", first.getValue("startedAt").jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:02.010Z", first.getValue("endedAt").jsonPrimitive.content)
        assertEquals(5_010.0, first.getValue("distanceMeters").jsonPrimitive.content.toDouble(), 0.01)
        assertEquals(1_810L, first.getValue("durationSeconds").jsonPrimitive.content.toLong())
        assertEquals(370.0, first.getValue("averagePaceSecPerKm").jsonPrimitive.content.toDouble(), 0.01)
        assertEquals(150, first.getValue("averageHeartRate").jsonPrimitive.content.toInt())
        assertEquals(180, first.getValue("averageCadence").jsonPrimitive.content.toInt())
        assertEquals(revision, coordinator.snapshot.value.revision)
    }

    @Test
    fun `运行中语音关闭立即取消旧请求并使后续状态静音`() = runTest {
        val tts = FakeTtsEngine()
        val bus = SpeechBus(tts, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(repository = FakeRunRepository(), speechBus = bus)
        coordinator.startRun()
        runCurrent()
        val before = tts.stopCalls
        coordinator.updateSettings(SettingsPatch(ttsEnabled = false))
        runCurrent()
        assertTrue(tts.stopCalls > before)
        tts.completeNextSuccessfully()
        coordinator.pauseRun()
        runCurrent()
        assertEquals(1, tts.requests.size)
        assertNull(coordinator.snapshot.value.latestCoachMessage)
    }

    @Test
    fun `真实语音链路生命周期等实际完成才更新文字且等待网络不取焦点`() = runTest {
        val voice = VoiceFixture()
        val bus = SpeechBus(voice.engine, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(FakeRunRepository(), speechBus = bus,
            onVoiceSettingsChanged = voice.settings::set)
        val actions = listOf(coordinator::startRun, coordinator::pauseRun, coordinator::resumeRun, coordinator::stopRun)
        val expected = listOf("流萤已开始记录，出发吧。", "跑步已暂停。", "跑步已恢复。", "跑步已结束。")
        actions.forEachIndexed { index, action ->
            action()
            runCurrent()
            assertEquals(index, voice.focusRequests)
            voice.clients.last().gate.complete(voice.file)
            runCurrent()
            assertEquals(index + 1, voice.focusRequests)
            voice.ports.last().listener.onCompletion()
            runCurrent()
            assertEquals(expected[index], coordinator.snapshot.value.latestCoachMessage?.text)
            assertEquals(index + 1, voice.focusReleases)
        }
        assertEquals(4, voice.clients.size)
        assertTrue(voice.clients.all { it.shutdownCalls == 1 })
        assertTrue(voice.ports.all { it.releaseCalls == 1 })
    }

    @Test
    fun `同步配置回调重载Fish且取消旧请求关闭语音后不补播`() = runTest {
        val voice = VoiceFixture()
        val bus = SpeechBus(voice.engine, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(FakeRunRepository(), speechBus = bus,
            onVoiceSettingsChanged = voice.settings::set)
        coordinator.startRun()
        runCurrent()
        val old = voice.clients.single()
        val generation = bus.sessionGenerationToken()
        coordinator.updateSettings(SettingsPatch(maxHeartRate = 170))
        assertEquals(generation, bus.sessionGenerationToken())
        coordinator.updateSettings(SettingsPatch(fishApiKey = "new-test-key", fishReferenceId = "new-test-voice"))
        assertEquals("new-test-key", voice.settings.get().fishApiKey)
        coordinator.pauseRun()
        runCurrent()
        assertTrue(old.cancelCalls > 0)
        assertEquals("new-test-key", voice.configurations.last().fishApiKey)
        assertEquals("new-test-voice", voice.configurations.last().fishReferenceId)
        old.gate.complete(voice.file)
        runCurrent()
        assertEquals(0, voice.focusRequests)
        coordinator.updateSettings(SettingsPatch(ttsEnabled = false))
        coordinator.resumeRun()
        runCurrent()
        voice.clients.last().gate.complete(voice.file)
        runCurrent()
        assertEquals(0, voice.focusRequests)
        assertNull(coordinator.snapshot.value.latestCoachMessage)
        assertEquals(2, voice.clients.size)
    }

    @Test
    fun `旧Flow快照晚于关闭返回时不得重新打开语音`() = runTest {
        val settings = DelayedSettingsRepository(SettingsSnapshot())
        val voice = VoiceFixture()
        val bus = SpeechBus(voice.engine, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(FakeRunRepository(), speechBus = bus,
            settingsRepository = settings, onVoiceSettingsChanged = voice.settings::set)
        assertTrue(settings.oldSnapshotCaptured.isCompleted)
        coordinator.startRun()
        runCurrent()
        coordinator.updateSettings(SettingsPatch(ttsEnabled = false))
        assertEquals(false, voice.settings.get().ttsEnabled)
        val generation = bus.sessionGenerationToken()

        settings.releaseOldSnapshot.complete(Unit)
        runCurrent()

        assertEquals(false, voice.settings.get().ttsEnabled)
        assertEquals(generation, bus.sessionGenerationToken())
        coordinator.pauseRun()
        runCurrent()
        voice.clients.single().gate.complete(voice.file)
        runCurrent()
        assertEquals(1, voice.clients.size)
        assertEquals(0, voice.focusRequests)
        assertNull(coordinator.snapshot.value.latestCoachMessage)
    }

    @Test
    fun `旧Flow快照不得回滚新Fish配置或取消更新后登记的状态播报`() = runTest {
        val settings = DelayedSettingsRepository(SettingsSnapshot())
        val voice = VoiceFixture()
        val bus = SpeechBus(voice.engine, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(FakeRunRepository(), speechBus = bus,
            settingsRepository = settings, onVoiceSettingsChanged = voice.settings::set)
        assertTrue(settings.oldSnapshotCaptured.isCompleted)
        coordinator.startRun()
        runCurrent()
        coordinator.updateSettings(SettingsPatch(fishApiKey = "new-test-key", fishReferenceId = "new-test-voice"))
        coordinator.pauseRun()
        runCurrent()
        val paused = voice.clients.last()
        val generation = bus.sessionGenerationToken()

        settings.releaseOldSnapshot.complete(Unit)
        runCurrent()

        assertEquals("new-test-key", voice.settings.get().fishApiKey)
        assertEquals("new-test-voice", voice.settings.get().fishReferenceId)
        assertEquals(generation, bus.sessionGenerationToken())
        assertEquals(0, paused.cancelCalls)
        voice.clients.first().gate.complete(voice.file)
        paused.gate.complete(voice.file)
        runCurrent()
        voice.ports.single().listener.onCompletion()
        runCurrent()
        assertEquals("跑步已暂停。", coordinator.snapshot.value.latestCoachMessage?.text)
    }

    private class DelayedSettingsRepository(initial: SettingsSnapshot) : SettingsRepository {
        private val backing = FakeSettingsRepository(initial)
        val oldSnapshotCaptured = CompletableDeferred<Unit>()
        val releaseOldSnapshot = CompletableDeferred<Unit>()
        override val settings: Flow<SettingsSnapshot> = flow {
            emit(backing.current())
            val oldSnapshot = backing.current()
            oldSnapshotCaptured.complete(Unit)
            releaseOldSnapshot.await()
            emit(oldSnapshot)
            awaitCancellation()
        }
        override suspend fun current() = backing.current()
        override suspend fun update(patch: SettingsPatch) = backing.update(patch)
    }

    @Test
    fun `真实链路URGENT抢占网络并等待完成后提升结束状态且跨会话迟到结果失效`() = runTest {
        val voice = VoiceFixture()
        val logger = FakeDiagnosticLogger()
        val bus = SpeechBus(voice.engine, diagnosticLogger = logger, dispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = createCoordinator(FakeRunRepository(), speechBus = bus,
            onVoiceSettingsChanged = voice.settings::set)
        coordinator.startRun()
        runCurrent()
        val started = voice.clients.single()
        bus.enqueue(RuleTrigger("hr_rapid_rise", com.healthyagent.running.core.model.Severity.NORMAL,
            "不得采用的改写。", SpeechPriority.URGENT))
        runCurrent()
        assertTrue(started.cancelCalls > 0)
        assertEquals(1, voice.clients.size)
        assertEquals(1, voice.focusRequests)
        coordinator.stopRun()
        runCurrent()
        assertEquals(1, voice.clients.size)
        voice.ports.single().listener.onCompletion()
        runCurrent()
        assertEquals(2, voice.clients.size)
        val finished = voice.clients.last()
        coordinator.startRun()
        runCurrent()
        assertTrue(finished.cancelCalls > 0)
        started.gate.complete(voice.file)
        finished.gate.complete(voice.file)
        runCurrent()
        assertNull(coordinator.snapshot.value.latestCoachMessage)
        assertEquals(1, voice.focusRequests)
        voice.clients.last().gate.complete(voice.file)
        runCurrent()
        voice.ports.last().listener.onCompletion()
        runCurrent()
        assertEquals("流萤已开始记录，出发吧。", coordinator.snapshot.value.latestCoachMessage?.text)
        assertEquals(1, logger.events.filterIsInstance<DiagnosticEvent.SpeechOutcome>().count {
            it.ruleId == "session_finished" && it.reason == SpeechOutcomeReason.CANCELLED
        })
    }

    private class VoiceFixture {
        val file = File("测试语音.mp3")
        val settings = AtomicReference(SettingsSnapshot())
        val configurations = mutableListOf<SettingsSnapshot>()
        val clients = mutableListOf<VoiceClient>()
        val ports = mutableListOf<VoicePort>()
        var focusRequests = 0
        var focusReleases = 0
        private val logger = FakeDiagnosticLogger()
        private val focus = object : AudioFocusController {
            override fun request(): AudioFocusLease {
                focusRequests++
                return object : AudioFocusLease {
                    override val granted = true
                    override fun release() { focusReleases++ }
                }
            }
        }
        val engine = VoicePolicyTtsEngine(
            bundledStore = object : BundledVoiceStore { override suspend fun open(ruleId: String) = file },
            onlineFactory = {
                configurations += settings.get()
                VoiceClient().also(clients::add)
            },
            companionTextSource = object : CompanionTextSource {
                override suspend fun create(request: SpeechRequest) = "能和你在一起，我很开心。"
            },
            playerFactory = { PreparedVoicePlayer({ VoicePort().also(ports::add) }, focus, logger) },
            diagnosticLogger = logger,
        )
    }

    private class VoiceClient : FishSynthesisClient {
        val gate = CompletableDeferred<File>()
        var cancelCalls = 0
        var shutdownCalls = 0
        override suspend fun synthesize(text: String): File = withContext(NonCancellable) { gate.await() }
        override fun cancel() { cancelCalls++ }
        override fun shutdown() { shutdownCalls++ }
    }

    private class VoicePort : AudioPlayerPort {
        lateinit var listener: AudioPlayerPort.Listener
        var releaseCalls = 0
        override suspend fun prepare(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener): Result<Unit> {
            this.listener = listener
            return Result.success(Unit)
        }
        override suspend fun startPrepared(startIfValid: (() -> Unit) -> Boolean): Result<Unit> {
            startIfValid {}
            return Result.success(Unit)
        }
        override fun start(file: File, profile: SpeechAudioProfile, listener: AudioPlayerPort.Listener): Result<Unit> {
            this.listener = listener
            return Result.success(Unit)
        }
        override fun stop() = Unit
        override fun release() { releaseCalls++ }
    }

    @Test
    fun `设置更新持久化且只影响下一次开跑配置`() = runTest {
        val settingsRepository = FakeSettingsRepository(
            SettingsSnapshot(
                targetPaceSecPerKm = 360.0,
                targetDistanceKm = 5.0,
            ),
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            settingsRepository = settingsRepository,
        )

        coordinator.startRun()
        assertEquals(360.0, coordinator.snapshot.value.session.targetPaceSecPerKm)
        assertEquals(5_000.0, coordinator.snapshot.value.session.targetDistanceMeters)

        val outcome = coordinator.updateSettings(
            SettingsPatch(
                targetPaceSecPerKm = NullableSettingUpdate.Set(420.0),
                targetDistanceKm = NullableSettingUpdate.Set(8.0),
            ),
        )

        assertTrue(outcome is CommandOutcome.AcceptedWithData)
        assertEquals(360.0, coordinator.snapshot.value.session.targetPaceSecPerKm)
        assertEquals(5_000.0, coordinator.snapshot.value.session.targetDistanceMeters)
        assertEquals(420.0, coordinator.snapshot.value.settings.targetPaceSecPerKm)

        coordinator.stopRun()
        runCurrent()
        coordinator.startRun()

        assertEquals(420.0, coordinator.snapshot.value.session.targetPaceSecPerKm)
        assertEquals(8_000.0, coordinator.snapshot.value.session.targetDistanceMeters)
    }

    @Test
    fun `设置查询返回当前本地快照`() = runTest {
        val settingsRepository = FakeSettingsRepository(
            SettingsSnapshot(maxHeartRate = 172, coachTone = CoachTone.GENTLE),
        )
        val coordinator = createCoordinator(
            repository = FakeRunRepository(),
            settingsRepository = settingsRepository,
        )

        val outcome = coordinator.querySettings()

        assertTrue(outcome is CommandOutcome.AcceptedWithData)
        val settings = (outcome as CommandOutcome.AcceptedWithData).data
            .getValue("settings")
            .jsonObject
        assertEquals(172, settings.getValue("maxHeartRate").jsonPrimitive.content.toInt())
        assertEquals("gentle", settings.getValue("coachTone").jsonPrimitive.content)
    }

    private fun kotlinx.coroutines.test.TestScope.createCoordinator(
        repository: FakeRunRepository,
        sensorDataSource: FakeSensorDataSource = FakeSensorDataSource(),
        settingsRepository: SettingsRepository = FakeSettingsRepository(),
        healthConnectSync: FakeHealthConnectSync = FakeHealthConnectSync(),
        config: RunConfig = RunConfig(llmEnabled = false),
        speechBus: SpeechBus? = null,
        ruleEngine: RuleEngine? = null,
        sessionManager: RunSessionManager = RunSessionManager(),
        pausedHeartRateMonitor: PausedHeartRateMonitor = PausedHeartRateMonitor(),
        coordinatorScope: CoroutineScope = backgroundScope,
        onVoiceSettingsChanged: (SettingsSnapshot) -> Unit = {},
        llmClient: LlmClient = object : LlmClient {
            override suspend fun analyze(request: LlmAnalyzeRequest): Result<LlmAnalyzeResponse> =
                Result.failure(IllegalStateException("测试中不调用网络"))
        },
    ): AppCoordinator {
        val effectiveSpeechBus = speechBus ?: SpeechBus(object : TtsEngine {
            override val isSpeaking: Boolean = false
            override suspend fun speak(text: String, priority: SpeechPriority) = Result.success(Unit)
            override fun stop() = Unit
            override fun shutdown() = Unit
        })
        val coordinator = AppCoordinator(
            sensorDataSource = sensorDataSource,
            pipeline = RunningDataPipeline(
                initialConfig = config,
                tickSource = sensorDataSource.ticks,
                monotonicClockMillis = { sensorDataSource.monotonicMillis },
                wallClockMillis = { 1_787_745_600_000L + sensorDataSource.monotonicMillis },
            ),
            runConfig = config,
            ruleEngine = ruleEngine ?: RuleEngine(emptyList(), config),
            speechBus = effectiveSpeechBus,
            sessionManager = sessionManager,
            repository = repository,
            settingsRepository = settingsRepository,
            healthConnectSync = healthConnectSync,
            analysisManager = AnalysisManager(llmClient),
            emergencyPhraseCache = EmergencyPhraseCache(llmClient),
            scope = coordinatorScope,
            nowMillis = { 1_787_745_600_000L },
            realtimeMillis = { sensorDataSource.monotonicMillis },
            pausedHeartRateMonitor = pausedHeartRateMonitor,
            onVoiceSettingsChanged = onVoiceSettingsChanged,
        )
        runCurrent()
        return coordinator
    }

    @Test
    fun `启动时恢复本地未结束会话`() = runTest {
        val activeSession = RunSession(
            id = "active-run",
            startTimeMillis = 1_000L,
            totalDistanceMeters = 1_250.0,
            totalDurationSec = 500,
            avgHeartRate = 145,
            avgCadence = 172,
            runState = RunState.PAUSED,
        )

        val coordinator = createCoordinator(FakeRunRepository(activeSession))

        assertEquals("active-run", coordinator.snapshot.value.session.id)
        assertEquals("paused", coordinator.snapshot.value.session.state)
        assertEquals(1_250.0, coordinator.snapshot.value.metrics.distanceMeters, 0.01)
        assertEquals(500, coordinator.snapshot.value.metrics.elapsedSeconds)
        assertEquals(null, coordinator.snapshot.value.metrics.heartRate)
        assertEquals(null, coordinator.snapshot.value.metrics.paceSecPerKm)
        assertEquals(null, coordinator.snapshot.value.metrics.cadence)
    }

    @Test
    fun `运行中会话恢复后只从前台服务重新挂接时继续计时`() = runTest {
        val sensor = FakeSensorDataSource()
        val activeSession = RunSession(
            id = "active-running",
            startTimeMillis = 1_000L,
            totalDistanceMeters = 1_250.0,
            totalDurationSec = 500,
            runState = RunState.RUNNING,
        )
        val coordinator = createCoordinator(FakeRunRepository(activeSession), sensor)

        sensor.advanceClockTo(20_000L)
        assertEquals(500, coordinator.snapshot.value.metrics.elapsedSeconds)

        coordinator.attachRecoveredRun()
        runCurrent()
        sensor.emitDistance(21_000L, 1_300.0)
        runCurrent()

        assertEquals(501, coordinator.snapshot.value.metrics.elapsedSeconds)
        assertEquals(1_250.0, coordinator.snapshot.value.metrics.distanceMeters, 0.01)
    }

    @Test
    fun `协调器漏发传感器生命周期通知会丢失开始暂停继续结束状态`() = runTest {
        val sensor = FakeSensorDataSource()
        val coordinator = createCoordinator(FakeRunRepository(), sensor)

        coordinator.startRun()
        sensor.advanceClockTo(3_400L)
        coordinator.pauseRun()
        sensor.advanceClockTo(10_000L)
        coordinator.resumeRun()
        sensor.advanceClockTo(12_100L)
        coordinator.stopRun()

        assertEquals(
            listOf(
                RunLifecycleEvent.Started(0L),
                RunLifecycleEvent.Paused(3L),
                RunLifecycleEvent.Resumed(3L),
                RunLifecycleEvent.Stopped,
            ),
            sensor.runLifecycleEvents,
        )
    }

    @Test
    fun `恢复运行会话若在挂接前通知继续会让传感器过早开始采样`() = runTest {
        val sensor = FakeSensorDataSource()
        val activeSession = RunSession(
            id = "active-running-lifecycle",
            startTimeMillis = 1_000L,
            totalDistanceMeters = 1_250.0,
            totalDurationSec = 500L,
            runState = RunState.RUNNING,
        )
        val coordinator = createCoordinator(FakeRunRepository(activeSession), sensor)

        assertEquals(emptyList<RunLifecycleEvent>(), sensor.runLifecycleEvents)
        assertEquals(0, sensor.connectCalls)

        coordinator.attachRecoveredRun()
        runCurrent()
        coordinator.attachRecoveredRun()
        runCurrent()

        assertEquals(listOf(RunLifecycleEvent.Resumed(500L)), sensor.runLifecycleEvents)
        assertEquals(listOf(1_250.0 to null), sensor.displayMetrics)
        assertEquals(1, sensor.connectCalls)
    }

    @Test
    fun `恢复暂停会话若通知或连接传感器会破坏暂停冻结状态`() = runTest {
        val sensor = FakeSensorDataSource()
        val activeSession = RunSession(
            id = "active-paused-lifecycle",
            startTimeMillis = 1_000L,
            totalDistanceMeters = 1_250.0,
            totalDurationSec = 500L,
            runState = RunState.PAUSED,
        )
        val coordinator = createCoordinator(FakeRunRepository(activeSession), sensor)

        coordinator.attachRecoveredRun()
        runCurrent()

        assertEquals(emptyList<RunLifecycleEvent>(), sensor.runLifecycleEvents)
        assertEquals(0, sensor.connectCalls)
    }

    @Test
    fun `暂停期间指标冻结且继续后从原值增长`() = runTest {
        val sensor = FakeSensorDataSource()
        val coordinator = createCoordinator(FakeRunRepository(), sensor)
        coordinator.startRun()
        runCurrent()

        sensor.emitDistance(1_000L, 100.0)
        sensor.emitDistance(2_000L, 110.0)
        sensor.emitDistance(3_000L, 120.0)
        runCurrent()
        coordinator.pauseRun()
        val pausedMetrics = coordinator.snapshot.value.metrics
        val frozenDisplayCount = sensor.displayMetrics.size

        sensor.emitDistance(4_000L, 130.0)
        sensor.emitDistance(5_000L, 140.0)
        runCurrent()

        assertEquals(pausedMetrics, coordinator.snapshot.value.metrics)
        assertEquals(frozenDisplayCount, sensor.displayMetrics.size)

        coordinator.resumeRun()
        assertEquals(pausedMetrics.distanceMeters to null, sensor.displayMetrics.last())
        runCurrent()
        sensor.emitDistance(6_000L, 150.0)
        sensor.emitDistance(7_000L, 160.0)
        runCurrent()

        assertEquals(pausedMetrics.elapsedSeconds + 2, coordinator.snapshot.value.metrics.elapsedSeconds)
        assertEquals(pausedMetrics.distanceMeters + 10.0, coordinator.snapshot.value.metrics.distanceMeters, 0.01)
        assertEquals(coordinator.snapshot.value.metrics.distanceMeters, sensor.displayMetrics.last().first, 0.01)
    }

    @Test
    fun `上一代管线迟到帧不会跨过暂停继续写入新阶段`() = runTest {
        val sensor = FakeSensorDataSource()
        val coordinator = createCoordinator(FakeRunRepository(), sensor)
        coordinator.startRun()
        runCurrent()
        val generationField = AppCoordinator::class.java
            .getDeclaredField("pipelineGeneration")
            .apply { isAccessible = true }
        val oldGeneration = generationField.getLong(coordinator)

        coordinator.pauseRun()
        coordinator.resumeRun()
        val displayCount = sensor.displayMetrics.size
        val lateFrame = MetricsFrame(
            timestampMillis = 2_000L,
            elapsedSeconds = 99L,
            heartRate = 180,
            heartRateSmoothed = 180,
            hrZone = HrZone.Z5,
            cadence = 190,
            strideLengthCm = 120.0f,
            currentPaceSecPerKm = 200.0,
            distanceMeters = 900.0,
            splitDistanceMeters = 900.0,
            totalSteps = 200L,
            isDataFresh = true,
            hrPaceRatio = 0.9,
            isHeartRateFresh = true,
        )
        AppCoordinator::class.java.getDeclaredMethod(
            "processFrame",
            MetricsFrame::class.java,
            Long::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
            .invoke(coordinator, lateFrame, oldGeneration)

        assertEquals(0.0, coordinator.snapshot.value.metrics.distanceMeters, 0.01)
        assertEquals(null, coordinator.snapshot.value.metrics.heartRate)
        assertEquals(0, coordinator.snapshot.value.metrics.elapsedSeconds)
        assertEquals(displayCount, sensor.displayMetrics.size)
    }

    @Test
    fun `多次暂停继续会保留不足一秒的活动时间`() = runTest {
        val sensor = FakeSensorDataSource()
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(repository, sensor)
        coordinator.startRun()
        runCurrent()

        sensor.advanceClockTo(900L)
        coordinator.pauseRun()
        sensor.advanceClockTo(10_000L)
        coordinator.resumeRun()
        sensor.advanceClockTo(10_100L)
        coordinator.stopRun()
        runCurrent()

        assertEquals(1L, repository.savedSessions.single().totalDurationSec)
    }

    @Test
    fun `旧连接取消后才会断开且两者不会并发`() = runTest {
        val connectGate = CompletableDeferred<Unit>()
        val sensor = FakeSensorDataSource(
            connectGate = connectGate,
            ignoreConnectCancellation = true,
        )
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(repository, sensor)
        coordinator.startRun()
        runCurrent()

        coordinator.stopRun()
        runCurrent()

        assertEquals(0, sensor.disconnectCalls)
        assertEquals(1, sensor.maxConcurrentOperations)
        assertTrue(repository.savedSessions.isEmpty())

        connectGate.complete(Unit)
        runCurrent()

        assertEquals(1, sensor.disconnectCalls)
        assertEquals(1, sensor.maxConcurrentOperations)
        assertEquals(1, repository.savedSessions.size)
    }

    @Test
    fun `连接协程取消不会写入设备错误`() = runTest {
        val sensor = FakeSensorDataSource(
            connectFailure = CancellationException("测试取消"),
        )
        val coordinator = createCoordinator(FakeRunRepository(), sensor)

        coordinator.startRun()
        runCurrent()

        assertEquals("normal", coordinator.snapshot.value.safetyState.level)
        assertEquals(null, coordinator.snapshot.value.safetyState.message)
    }

    @Test
    fun `结束会话会取消排在旧断开操作之后的连接`() = runTest {
        val disconnectGate = CompletableDeferred<Unit>()
        val sensor = FakeSensorDataSource(disconnectGate = disconnectGate)
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(repository, sensor)
        coordinator.disconnectSensor()
        runCurrent()

        coordinator.startRun()
        coordinator.stopRun()
        runCurrent()

        disconnectGate.complete(Unit)
        runCurrent()

        assertEquals(0, sensor.connectCalls)
        assertEquals(1, sensor.disconnectCalls)
        assertEquals(1, repository.savedSessions.size)
    }

    @Test
    fun `上次跑步仍在保存时拒绝开始新跑`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val coordinator = createCoordinator(FakeRunRepository(saveGate = saveGate))
        coordinator.startRun()

        coordinator.stopRun()
        runCurrent()

        val outcome = coordinator.startRun()
        assertTrue(outcome is CommandOutcome.Rejected)
        assertEquals("saving", coordinator.snapshot.value.persistenceState)

        saveGate.complete(Unit)
        runCurrent()
        assertEquals("saved", coordinator.snapshot.value.persistenceState)
    }

    @Test
    fun `主数据源与穿戴设备状态不会互相覆盖`() = runTest {
        val sensor = FakeSensorDataSource(
            primaryInfo = DeviceInfo("手机 GPS"),
            wearableInfo = DeviceInfo("Keep B5", batteryPercent = 88),
            initialWearableState = ConnectionState.CONNECTED,
        )
        val coordinator = createCoordinator(FakeRunRepository(), sensor)

        coordinator.startRun()
        runCurrent()
        sensor.updateWearableState(ConnectionState.ERROR)
        runCurrent()

        assertEquals("connected", coordinator.snapshot.value.connection.state)
        assertEquals("手机 GPS", coordinator.snapshot.value.connection.deviceName)
        assertEquals("error", coordinator.snapshot.value.wearable.state)
        assertEquals("Keep B5", coordinator.snapshot.value.wearable.deviceName)
        assertEquals(null, coordinator.snapshot.value.wearable.batteryPercent)
        // 只有连接枚举，没有已运动 Ready 后真实故障的证据，不冒充跑中断开。
        assertNull(coordinator.snapshot.value.safetyState.message)
    }

    @Test
    fun `主数据源错误不会被描述成心率错误`() = runTest {
        val sensor = FakeSensorDataSource(primaryInfo = DeviceInfo("手机 GPS"))
        val coordinator = createCoordinator(FakeRunRepository(), sensor)

        sensor.updatePrimaryState(ConnectionState.ERROR)
        runCurrent()

        assertEquals("error", coordinator.snapshot.value.connection.state)
        assertEquals("手机定位暂时不可用", coordinator.snapshot.value.safetyState.message)
    }

    @Test
    fun `路线点会进入后续检查点`() = runTest {
        val sensor = FakeSensorDataSource()
        val repository = FakeRunRepository()
        val coordinator = createCoordinator(repository, sensor)
        coordinator.startRun()
        runCurrent()

        sensor.emitRoute(RoutePoint(31.2, 121.4, 1_500L, 4.0))
        runCurrent()
        coordinator.pauseRun()
        runCurrent()

        assertEquals(1, repository.checkpoints.last().routePoints.size)
        assertEquals(31.2, repository.checkpoints.last().routePoints.single().latitude, 0.0001)
    }

    @Test
    fun `没有新鲜心率时快照不输出心率区间`() {
        val snapshot = MetricsFrame(
            timestampMillis = 1_000L,
            elapsedSeconds = 1L,
            heartRate = null,
            heartRateSmoothed = null,
            hrZone = HrZone.Z3,
            cadence = null,
            strideLengthCm = null,
            currentPaceSecPerKm = 360.0,
            distanceMeters = 10.0,
            splitDistanceMeters = 10.0,
            totalSteps = 0L,
            isDataFresh = true,
            hrPaceRatio = null,
            isHeartRateFresh = false,
        ).toSnapshot()

        assertEquals(null, snapshot.heartRate)
        assertEquals(null, snapshot.hrZone)
    }

    @Test
    fun `Web 权限请求会通知 Activity 且命令被接受`() = runTest {
        val coordinator = createCoordinator(FakeRunRepository())
        val event = async { coordinator.events.first() }

        val outcome = coordinator.requestRunningPermissions()
        runCurrent()

        assertEquals(CommandOutcome.Accepted, outcome)
        assertEquals(CoordinatorEvent.RequestRunningPermissions, event.await())
    }
}

private class FakeSensorDataSource(
    private val primaryInfo: DeviceInfo = DeviceInfo("测试手机"),
    private val wearableInfo: DeviceInfo? = null,
    initialWearableState: ConnectionState? = null,
    private val connectGate: CompletableDeferred<Unit>? = null,
    private val prepareGate: CompletableDeferred<Unit>? = null,
    private val ignoreConnectCancellation: Boolean = false,
    private val connectFailure: Throwable? = null,
    private val disconnectGate: CompletableDeferred<Unit>? = null,
    private val trace: MutableList<String>? = null,
    override val wearableLinkObservation: StateFlow<WearableLinkObservation>? = null,
) : SensorDataSource, RunLifecycleAwareSensor {
    private val mutableConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val mutableWearableConnectionState = initialWearableState?.let(::MutableStateFlow)
    private val mutableSamples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 16)
    val ticks = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    var monotonicMillis: Long = 0L
        private set
    private val mutableRoutePoints = MutableSharedFlow<RoutePoint>(extraBufferCapacity = 4)
    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState
    override val wearableConnectionState: StateFlow<ConnectionState>? = mutableWearableConnectionState
    override val samples: Flow<SensorSample> = mutableSamples
    override val routePoints: Flow<RoutePoint> = mutableRoutePoints
    override val deviceInfo: DeviceInfo = primaryInfo
    override val primaryDeviceInfo: DeviceInfo = primaryInfo
    override val wearableDeviceInfo: DeviceInfo? = wearableInfo
    var connectCalls = 0
        private set
    var prepareCalls = 0
        private set
    var disconnectCalls = 0
        private set
    var maxConcurrentOperations = 0
        private set
    val runLifecycleEvents = mutableListOf<RunLifecycleEvent>()
    val displayMetrics = mutableListOf<Pair<Double, Double?>>()
    private var concurrentOperations = 0

    override fun onRunStarted(elapsedSeconds: Long) {
        runLifecycleEvents += RunLifecycleEvent.Started(elapsedSeconds)
    }

    override fun onRunPaused(elapsedSeconds: Long) {
        runLifecycleEvents += RunLifecycleEvent.Paused(elapsedSeconds)
    }

    override fun onRunResumed(elapsedSeconds: Long) {
        runLifecycleEvents += RunLifecycleEvent.Resumed(elapsedSeconds)
    }

    override fun onRunMetricsUpdated(distanceMeters: Double, paceSecPerKm: Double?) {
        displayMetrics += distanceMeters to paceSecPerKm
    }

    override fun onRunStopped() {
        runLifecycleEvents += RunLifecycleEvent.Stopped
        trace?.add("sensor:onRunStopped")
    }

    override suspend fun connect() {
        enterOperation()
        try {
            connectCalls++
            mutableConnectionState.value = ConnectionState.CONNECTING
            connectFailure?.let { throw it }
            if (ignoreConnectCancellation) {
                withContext(NonCancellable) { connectGate?.await() }
            } else {
                connectGate?.await()
            }
            mutableConnectionState.value = ConnectionState.CONNECTED
        } finally {
            leaveOperation()
        }
    }

    override suspend fun prepareConnection() {
        enterOperation()
        try {
            prepareCalls++
            prepareGate?.await()
            mutableWearableConnectionState?.value = ConnectionState.CONNECTED
        } finally {
            leaveOperation()
        }
    }

    override suspend fun disconnect() {
        enterOperation()
        try {
            disconnectCalls++
            disconnectGate?.await()
            mutableConnectionState.value = ConnectionState.DISCONNECTED
        } finally {
            leaveOperation()
        }
    }

    private fun enterOperation() {
        concurrentOperations++
        maxConcurrentOperations = maxOf(maxConcurrentOperations, concurrentOperations)
    }

    private fun leaveOperation() {
        concurrentOperations--
    }

    suspend fun emitDistance(timestampMillis: Long, distanceMeters: Double) {
        emitSample(SampleType.DISTANCE, distanceMeters, timestampMillis)
        emitTick(timestampMillis)
    }

    suspend fun emitSample(type: SampleType, value: Double, timestampMillis: Long) {
        monotonicMillis = timestampMillis
        mutableSamples.emit(SensorSample(type, value, timestampMillis, "test"))
        yield()
    }

    suspend fun emitTick(timestampMillis: Long) {
        monotonicMillis = timestampMillis
        ticks.emit(timestampMillis)
        yield()
    }

    fun advanceClockTo(timestampMillis: Long) {
        monotonicMillis = timestampMillis
    }

    suspend fun emitRoute(routePoint: RoutePoint) {
        mutableRoutePoints.emit(routePoint)
    }

    fun updatePrimaryState(state: ConnectionState) {
        mutableConnectionState.value = state
    }

    fun updateWearableState(state: ConnectionState) {
        requireNotNull(mutableWearableConnectionState).value = state
    }
}

private class CountingRule : Rule {
    override val id = "counting_rule"
    override val cooldownMs = 0L
    override val defaultPriority = SpeechPriority.APP
    var evaluateCalls = 0
        private set

    override fun evaluate(ctx: RuleContext, config: RunConfig): RuleTrigger? {
        evaluateCalls += 1
        return null
    }
}

private class TraceDiagnosticLogger(
    private val trace: MutableList<String>,
    private val flushGate: CompletableDeferred<Unit>? = null,
) : DiagnosticLogger {
    private val lock = Any()
    var flushCalls = 0
        private set

    override fun record(event: DiagnosticEvent) {
        if (event is DiagnosticEvent.SpeechOutcome) {
            synchronized(lock) {
                trace += "speech:${event.ruleId}:${event.reason}"
            }
        }
    }

    override suspend fun awaitIdle() = Unit

    override suspend fun flush() {
        synchronized(lock) {
            flushCalls += 1
            trace += "flush"
        }
        flushGate?.await()
    }
}

private sealed interface RunLifecycleEvent {
    data class Started(val elapsedSeconds: Long) : RunLifecycleEvent

    data class Paused(val elapsedSeconds: Long) : RunLifecycleEvent

    data class Resumed(val elapsedSeconds: Long) : RunLifecycleEvent

    data object Stopped : RunLifecycleEvent
}

private class FakeRunRepository(
    private var activeSession: RunSession? = null,
    private val saveError: Throwable? = null,
    private val saveGate: CompletableDeferred<Unit>? = null,
) : RunRepository {
    val savedSessions = mutableListOf<RunSession>()
    val checkpoints = mutableListOf<RunSession>()
    var lastHistoryOffset: Int? = null
    var lastHistoryLimit: Int? = null

    override suspend fun saveSession(session: RunSession) {
        saveGate?.await()
        saveError?.let { throw it }
        savedSessions += session
        activeSession = null
    }

    override suspend fun checkpointSession(session: RunSession) {
        checkpoints += session
        activeSession = session
    }

    override suspend fun getActiveSession(): RunSession? = activeSession

    override suspend fun getAllSessions(): List<RunSession> = savedSessions

    override suspend fun getHistoryPage(offset: Int, limit: Int): RunHistoryPage {
        lastHistoryOffset = offset
        lastHistoryLimit = limit
        return RunHistoryPage(
            totalItems = savedSessions.size,
            items = savedSessions.drop(offset).take(limit),
        )
    }

    override suspend fun getSessionWithSplits(sessionId: String): RunSession? =
        savedSessions.firstOrNull { it.id == sessionId }
}

private class FakeHealthConnectSync(
    private val result: HealthConnectSyncResult = HealthConnectSyncResult.Synced(
        routeWritten = false,
        writtenRecordCount = 2,
    ),
    private val syncGate: CompletableDeferred<Unit>? = null,
) : HealthConnectSync {
    override val requestedPermissions: Set<String> = emptySet()
    override val permissionState: StateFlow<HealthConnectPermissionState> = MutableStateFlow(
        HealthConnectPermissionState(HealthConnectAvailability.AVAILABLE),
    )
    val syncedSessions = mutableListOf<RunSession>()

    override fun availability(): HealthConnectAvailability = HealthConnectAvailability.AVAILABLE

    override suspend fun refreshPermissions(): HealthConnectPermissionState = permissionState.value

    override fun updateGrantedPermissions(grantedPermissions: Set<String>) = Unit

    override suspend fun syncRun(session: RunSession): HealthConnectSyncResult {
        syncedSessions += session
        syncGate?.await()
        return result
    }
}

private class FakeSettingsRepository(initial: SettingsSnapshot = SettingsSnapshot()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<SettingsSnapshot> = state
    override suspend fun current(): SettingsSnapshot = state.value
    override suspend fun update(patch: SettingsPatch) {
        val current = state.value
        state.value = current.copy(
            targetPaceSecPerKm = when (val update = patch.targetPaceSecPerKm) {
                NullableSettingUpdate.Unchanged -> current.targetPaceSecPerKm
                NullableSettingUpdate.Clear -> null
                is NullableSettingUpdate.Set -> update.value
            },
            targetDistanceKm = when (val update = patch.targetDistanceKm) {
                NullableSettingUpdate.Unchanged -> current.targetDistanceKm
                NullableSettingUpdate.Clear -> null
                is NullableSettingUpdate.Set -> update.value
            },
            maxHeartRate = patch.maxHeartRate ?: current.maxHeartRate,
            ttsEnabled = patch.ttsEnabled ?: current.ttsEnabled,
            ttsMinIntervalSec = patch.ttsMinIntervalSec ?: current.ttsMinIntervalSec,
            llmEnabled = patch.llmEnabled ?: current.llmEnabled,
            mockScenario = patch.mockScenario ?: current.mockScenario,
            coachTone = patch.coachTone ?: current.coachTone,
            fishApiKey = patch.fishApiKey ?: current.fishApiKey,
            fishReferenceId = patch.fishReferenceId ?: current.fishReferenceId,
            llmApiKey = patch.llmApiKey ?: current.llmApiKey,
            themePreference = patch.themePreference ?: current.themePreference,
            motionPreference = patch.motionPreference ?: current.motionPreference,
        )
    }
}
