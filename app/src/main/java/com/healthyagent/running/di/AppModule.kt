package com.healthyagent.running.di

import android.os.Build
import com.healthyagent.running.coordinator.PausedHeartRateMonitor
import com.healthyagent.running.core.engine.RuleCatalog
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.coordinator.AndroidRunCommandDispatcher
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.coordinator.RunCommandDispatcher
import com.healthyagent.running.data.local.AppDatabase
import com.healthyagent.running.data.local.RunSessionDao
import com.healthyagent.running.data.local.SettingsDataStore
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.data.health.AndroidHealthConnectSync
import com.healthyagent.running.data.health.HealthConnectSync
import com.healthyagent.running.data.repository.RoomRunRepository
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.data.sensor.CompositeSensorDataSource
import com.healthyagent.running.data.sensor.ble.KeepB5BleDataSource
import com.healthyagent.running.data.sensor.location.PhoneGpsDataSource
import com.healthyagent.running.data.sensor.mock.MockScenario
import com.healthyagent.running.data.sensor.mock.MockSensorDataSource
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.diagnostics.FileDiagnosticLogger
import com.healthyagent.running.speech.AndroidAudioFocusController
import com.healthyagent.running.speech.AndroidAudioPlayerPort
import com.healthyagent.running.speech.AndroidTextToSpeechPort
import com.healthyagent.running.speech.AndroidTtsEngine
import com.healthyagent.running.speech.AudioFocusController
import com.healthyagent.running.speech.CompositeTtsEngine
import com.healthyagent.running.speech.FishAudioTtsEngine
import com.healthyagent.running.speech.OkHttpFishSynthesisClient
import com.healthyagent.running.speech.SpeechBus
import com.healthyagent.running.speech.TtsEngine
import com.healthyagent.running.ui.history.HistoryViewModel
import com.healthyagent.running.ui.running.RunningViewModel
import com.healthyagent.running.ui.settings.SettingsViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI 模块定义
 */
val appModule = module {

    single<DiagnosticLogger> {
        FileDiagnosticLogger(File(androidContext().filesDir, "diagnostics"))
    }
    single<AudioFocusController> {
        AndroidAudioFocusController(androidContext())
    }
    single {
        PausedHeartRateMonitor()
    }

    // 跑步配置（默认值）
    single {
        RunConfig(
            targetPaceSecPerKm = 360.0,    // 目标配速 6:00/km
            targetDistanceMeters = 5000.0, // 目标 5 公里
            maxHeartRate = 180,
            ttsEnabled = true,
            ttsMinIntervalSec = 4,
            llmEnabled = true,             // 默认启用，直连 Anthropic Claude API
        )
    }

    // 模拟器保留可复现脚本；真机使用手机 GPS 主链路，Keep B5 仅补充心率和电量。
    single<SensorDataSource> {
        val scenarioKey = runBlocking {
            get<SettingsRepository>().current().mockScenario
        }
        if (isProbablyEmulator()) {
            MockSensorDataSource(scenario = mockScenarioFromKey(scenarioKey))
        } else {
            CompositeSensorDataSource(
                phone = PhoneGpsDataSource(androidContext()),
                wearable = KeepB5BleDataSource(androidContext(), diagnosticLogger = get()),
            )
        }
    }

    // 数据处理管道
    single {
        RunningDataPipeline(get<RunConfig>())
    }

    // ── Phase 2: 规则引擎 ──
    single {
        RuleEngine(
            rules = RuleCatalog.defaultRules(),
            initialConfig = get(),
            diagnosticLogger = get(),
        )
    }

    // ── Phase 2: TTS（双层：本地 Android TTS + Fish Audio 云端声音克隆）──
    single<TtsEngine> {
        val logger = get<DiagnosticLogger>()
        val focus = get<AudioFocusController>()
        val settings = runBlocking { get<SettingsRepository>().current() }
        val local = AndroidTtsEngine(
            port = AndroidTextToSpeechPort(androidContext()),
            audioFocusController = focus,
            diagnosticLogger = logger,
        )
        val cloud = if (
            settings.fishApiKey.isNotBlank() &&
            settings.fishReferenceId.isNotBlank()
        ) {
            FishAudioTtsEngine(
                synthesisClient = OkHttpFishSynthesisClient(
                    apiKey = settings.fishApiKey,
                    referenceId = settings.fishReferenceId,
                    cacheDirectory = File(androidContext().cacheDir, "tts_cache"),
                ),
                audioPlayerFactory = { AndroidAudioPlayerPort() },
                audioFocusController = focus,
                diagnosticLogger = logger,
            )
        } else {
            null
        }
        CompositeTtsEngine(local = local, cloud = cloud, diagnosticLogger = logger)
    }

    // ── Phase 2: SpeechBus ──
    single {
        SpeechBus(ttsEngine = get(), diagnosticLogger = get())
    }

    // ── Phase 3: 数据库 ──
    single {
        AppDatabase.getInstance(androidContext())
    }

    // ── Phase 6: 设置持久化 ──
    single {
        SettingsDataStore(androidContext())
    }
    single<SettingsRepository> { get<SettingsDataStore>() }

    single<RunSessionDao> {
        get<AppDatabase>().runSessionDao()
    }

    // ── Phase 3: 仓库 ──
    single<RunRepository> {
        RoomRunRepository(get())
    }

    // Health Connect 与协调器、Activity 权限回调共享同一状态实例。
    single<HealthConnectSync> {
        AndroidHealthConnectSync(androidContext())
    }

    // ── Phase 3: 会话管理器 ──
    single {
        RunSessionManager()
    }

    // ── Phase 4: LLM 异步分析（直连 Anthropic API，户外场景可用）──
    single<com.healthyagent.running.ai.LlmClient> {
        // 从 LlmConfig.apiKey 动态读取密钥（设置页配置后即时生效）
        com.healthyagent.running.ai.AnthropicLlmClient()
    }

    single {
        com.healthyagent.running.ai.AnalysisManager(client = get()).also { manager ->
            manager.speechBus = get()
        }
    }

    // ── 紧急指令预生成缓存 ──
    single {
        com.healthyagent.running.ai.EmergencyPhraseCache(llmClient = get())
    }

    // ── 应用级运行协调器：生命周期独立于 Activity 与 WebView ──
    single {
        AppCoordinator(
            sensorDataSource = get(),
            pipeline = get(),
            runConfig = get(),
            ruleEngine = get(),
            speechBus = get(),
            sessionManager = get(),
            repository = get(),
            settingsRepository = get(),
            healthConnectSync = get(),
            analysisManager = get(),
            emergencyPhraseCache = get(),
            pausedHeartRateMonitor = get(),
        )
    }
    single<RunCommandDispatcher> {
        AndroidRunCommandDispatcher(
            context = androidContext(),
            coordinator = get(),
        )
    }

    // ── UI 状态持有者：仅适配协调器快照，不编排业务 ──
    viewModel {
        RunningViewModel(
            coordinator = get(),
            commandDispatcher = get(),
        )
    }
    viewModel { HistoryViewModel(repository = get()) }
    viewModel { SettingsViewModel(settingsRepository = get()) }
}

/** 将持久化键映射为模拟场景；未知旧值安全回退到轻松跑。 */
private fun mockScenarioFromKey(key: String): MockScenario = when (key) {
    "SPEED_TEST" -> MockScenario.SPEED_TEST
    "PROGRESSIVE" -> MockScenario.PROGRESSIVE
    "HEART_RATE_SPIKE" -> MockScenario.HEART_RATE_SPIKE
    "CADENCE_DROP" -> MockScenario.CADENCE_DROP
    else -> MockScenario.EASY_RUN
}

private fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()
    return fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator") ||
        model.contains("sdk_gphone") ||
        model.contains("emulator") ||
        product.contains("sdk_gphone")
}
