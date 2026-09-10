package com.healthyagent.running.data.sensor.mock

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.data.sensor.SensorDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * 模拟传感器数据源 — 内置多种跑步场景脚本
 *
 * 以 1Hz 频率产生带噪声的传感器样本，用于原型验证和全链路测试。
 */
class MockSensorDataSource(
    private val scenario: MockScenario = MockScenario.EASY_RUN
) : SensorDataSource {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    override val deviceInfo = DeviceInfo(
        name = "模拟跑步数据",
        batteryPercent = 85,
        firmwareVersion = "1.0.0-mock"
    )

    private var running = false
    private var elapsedSec = 0L
    private var totalDistance = 0.0
    private var totalSteps = 0L

    override suspend fun connect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) {
            elapsedSec = 0L
            totalDistance = 0.0
            totalSteps = 0L
        }
        _connectionState.value = ConnectionState.CONNECTING
        delay(500) // 模拟连接延迟
        _connectionState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        running = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override val samples: Flow<SensorSample> = flow {
        running = true

        while (running) {
            val point = scenario.pointAt(elapsedSec)

            // 添加少量噪声（±2bpm 心率，±2spm 步频）
            val hr = point.hr + Random.nextInt(-2, 3)
            val cadence = point.cadence + Random.nextInt(-2, 3)
            val speed = point.paceSecPerKm?.let { 1000.0 / it } ?: 2.5
            val deltaDist = speed * 1.0 // 1 秒移动距离
            totalDistance += deltaDist
            totalSteps += (cadence / 60).toInt().coerceAtLeast(1)

            // 心率样本（1Hz）
            emit(
                SensorSample(
                    type = SampleType.HEART_RATE,
                    value = hr.toDouble(),
                    timestampMillis = System.currentTimeMillis(),
                    source = "mock"
                )
            )

            // 步频样本（1Hz）
            emit(
                SensorSample(
                    type = SampleType.CADENCE,
                    value = cadence.toDouble(),
                    timestampMillis = System.currentTimeMillis(),
                    source = "mock"
                )
            )

            // 模拟器代表手机 GPS 主源，因此距离与步数也按 1Hz 更新。
            emit(
                SensorSample(
                    type = SampleType.DISTANCE,
                    value = totalDistance,
                    timestampMillis = System.currentTimeMillis(),
                    source = "mock_phone"
                )
            )
            emit(
                SensorSample(
                    type = SampleType.STEPS,
                    value = totalSteps.toDouble(),
                    timestampMillis = System.currentTimeMillis(),
                    source = "mock_phone"
                )
            )

            elapsedSec++
            delay(1000) // 1Hz
        }
    }
}
