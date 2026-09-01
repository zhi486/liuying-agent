package com.healthyagent.running.di

import com.healthyagent.running.core.engine.Rule
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.core.engine.rules.CadenceDropRule
import com.healthyagent.running.core.engine.rules.CadenceTooHighRule
import com.healthyagent.running.core.engine.rules.DataStaleRule
import com.healthyagent.running.core.engine.rules.EfficiencyAbnormalRule
import com.healthyagent.running.core.engine.rules.GoalAchievedRule
import com.healthyagent.running.core.engine.rules.HrRapidRiseRule
import com.healthyagent.running.core.engine.rules.HrTooLowRule
import com.healthyagent.running.core.engine.rules.HrZone5Rule
import com.healthyagent.running.core.engine.rules.KmSplitRule
import com.healthyagent.running.core.engine.rules.PaceDeviationRule
import com.healthyagent.running.core.engine.rules.StartTooFastRule
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.data.local.AppDatabase
import com.healthyagent.running.data.local.RunSessionDao
import com.healthyagent.running.data.local.SettingsDataStore
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.data.repository.RoomRunRepository
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.data.sensor.ble.KeepB5BleDataSource
import com.healthyagent.running.data.sensor.mock.MockScenario
import com.healthyagent.running.data.sensor.mock.MockSensorDataSource
import com.healthyagent.running.speech.AndroidTtsEngine
import com.healthyagent.running.speech.CompositeTtsEngine
import com.healthyagent.running.speech.FishAudioTtsEngine
import com.healthyagent.running.speech.SpeechBus
import com.healthyagent.running.speech.TtsEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin DI 模块定义
 */
val appModule = module {

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

    // 模拟数据源（后续切换为 BLE）
    // 切换为真机 BLE 时，注释 MockSensorDataSource 行，取消注释 KeepB5BleDataSource 行
    single<SensorDataSource> {
        MockSensorDataSource(scenario = MockScenario.SPEED_TEST)
        // KeepB5BleDataSource(androidContext())
    }

    // 数据处理管道
    single {
        RunningDataPipeline(get<RunConfig>())
    }

    // ── Phase 2: 11 条规则 ──
    single<Rule> { HrZone5Rule() }
    single<Rule> { HrRapidRiseRule() }
    single<Rule> { HrTooLowRule() }
    single<Rule> { CadenceDropRule() }
    single<Rule> { CadenceTooHighRule() }
    single<Rule> { PaceDeviationRule() }
    single<Rule> { StartTooFastRule() }
    single<Rule> { EfficiencyAbnormalRule() }
    single<Rule> { DataStaleRule() }
    single<Rule> { KmSplitRule() }
    single<Rule> { GoalAchievedRule() }

    // ── Phase 2: 规则引擎 ──
    single {
        RuleEngine(rules = getAll<Rule>(), config = get())
    }

    // ── Phase 2: TTS（双层：本地 Android TTS + Fish Audio 云端声音克隆）──
    single<TtsEngine> {
        val local = AndroidTtsEngine(androidContext())
        val settings = runBlocking { get<SettingsRepository>().current() }
        val cloud = FishAudioTtsEngine(
            apiKey = settings.fishApiKey,
            referenceId = settings.fishReferenceId,
            context = androidContext(),
        )
        CompositeTtsEngine(local = local, cloud = cloud)
    }

    // ── Phase 2: SpeechBus ──
    single {
        SpeechBus(ttsEngine = get())
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
}
