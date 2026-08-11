package com.healthyagent.running.di

import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.data.sensor.mock.MockScenario
import com.healthyagent.running.data.sensor.mock.MockSensorDataSource
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
            llmEnabled = false,            // Phase 4 启用
        )
    }

    // 模拟数据源（后续切换为 BLE）
    single<SensorDataSource> {
        MockSensorDataSource(scenario = MockScenario.EASY_RUN)
    }

    // 数据处理管道
    single {
        RunningDataPipeline(get<RunConfig>())
    }
}
