package com.healthyagent.running.ui.running

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.data.sensor.SensorDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 跑步界面 ViewModel — 管理跑步会话的 UI 状态
 */
class RunningViewModel(
    private val sensorDataSource: SensorDataSource,
    private val pipeline: RunningDataPipeline,
    private val runConfig: RunConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RunningUiState())
    val uiState: StateFlow<RunningUiState> = _uiState.asStateFlow()

    private val _runState = MutableStateFlow(RunState.IDLE)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    /**
     * 连接传感器并开始接收数据
     */
    fun connectSensor() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sensorStatus = ConnectionState.CONNECTING)
            try {
                sensorDataSource.connect()
                _uiState.value = _uiState.value.copy(
                    sensorStatus = ConnectionState.CONNECTED,
                    deviceName = sensorDataSource.deviceInfo.name
                )
                startPipeline()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    sensorStatus = ConnectionState.ERROR,
                    errorMessage = e.message
                )
            }
        }
    }

    /**
     * 启动数据处理管道
     */
    private fun startPipeline() {
        viewModelScope.launch {
            pipeline.process(sensorDataSource.samples)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(errorMessage = "数据管道错误: ${e.message}")
                }
                .collect { frame ->
                    _uiState.value = _uiState.value.copy(
                        currentFrame = frame,
                        elapsedSeconds = frame.elapsedSeconds,
                        isRunning = true,
                    )
                }
        }
    }

    fun startRun() {
        _runState.value = RunState.RUNNING
    }

    fun pauseRun() {
        _runState.value = RunState.PAUSED
    }

    fun resumeRun() {
        _runState.value = RunState.RUNNING
    }

    fun stopRun() {
        _runState.value = RunState.FINISHED
        viewModelScope.launch {
            sensorDataSource.disconnect()
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                sensorStatus = ConnectionState.DISCONNECTED,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            sensorDataSource.disconnect()
        }
    }
}

/**
 * 跑步界面 UI 状态
 */
data class RunningUiState(
    val isRunning: Boolean = false,
    val sensorStatus: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String = "",
    val currentFrame: MetricsFrame? = null,
    val elapsedSeconds: Long = 0,
    val errorMessage: String? = null,
)
