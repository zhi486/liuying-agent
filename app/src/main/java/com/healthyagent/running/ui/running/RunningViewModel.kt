package com.healthyagent.running.ui.running

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.ai.AnalysisManager
import com.healthyagent.running.ai.EmergencyPhraseCache
import com.healthyagent.running.ai.PromptBuilder
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RuleContext
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.speech.SpeechBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 跑步界面 ViewModel — 管理跑步会话的 UI 状态 + 串联规则引擎与语音播报
 */
class RunningViewModel(
    private val sensorDataSource: SensorDataSource,
    private val pipeline: RunningDataPipeline,
    private val runConfig: RunConfig,
    private val ruleEngine: RuleEngine,
    private val speechBus: SpeechBus,
    private val sessionManager: RunSessionManager,
    private val repository: RunRepository,
    private val analysisManager: AnalysisManager,
    private val emergencyPhraseCache: EmergencyPhraseCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RunningUiState())
    val uiState: StateFlow<RunningUiState> = _uiState.asStateFlow()

    private val _runState = MutableStateFlow(RunState.IDLE)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    /** 规则引擎评估上下文（帧历史在此累积） */
    private var ruleContext = RuleContext()

    /** 最近触发的规则文本（用于 LLM 上下文，最多保留 10 条） */
    private val recentTriggerTexts = mutableListOf<String>()

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
     * 启动数据处理管道 + 规则引擎 + 语音播报 + 会话管理链路
     */
    private fun startPipeline() {
        viewModelScope.launch {
            pipeline.process(sensorDataSource.samples)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(errorMessage = "数据管道错误: ${e.message}")
                }
                .collect { frame ->
                    // 更新 UI 状态
                    _uiState.value = _uiState.value.copy(
                        currentFrame = frame,
                        elapsedSeconds = frame.elapsedSeconds,
                        isRunning = true,
                    )

                    // 会话管理：逐帧累积 + 分段检测
                    val split = sessionManager.onFrame(frame)
                    if (split != null) {
                        // 通知规则引擎有新的 split 事件
                        ruleEngine.onSplit(split.index)

                        // 触发 LLM 异步深度分析
                        val session = sessionManager.currentSession
                        if (session != null && runConfig.llmEnabled) {
                            val llmRequest = PromptBuilder.build(
                                session = session,
                                split = split,
                                config = runConfig,
                                recentTriggers = recentTriggerTexts.toList(),
                            )
                            analysisManager.analyze(llmRequest)
                        }
                    }

                    // 规则引擎评估
                    val triggers = ruleEngine.evaluate(frame, ruleContext)

                    // 更新上下文（追加当前帧到历史）
                    ruleContext = ruleContext.copy(
                        recentFrames = (ruleContext.recentFrames + frame).takeLast(60),
                        elapsedSeconds = frame.elapsedSeconds,
                    )

                    // 记录触发文本（供 LLM 上下文使用）
                    for (trigger in triggers) {
                        recentTriggerTexts.add(trigger.speakText)
                        if (recentTriggerTexts.size > 10) {
                            recentTriggerTexts.removeAt(0)
                        }
                    }

                    // 将触发的规则送入语音播报（紧急指令先用 LLM 预生成修饰语）
                    for (trigger in triggers) {
                        launch {
                            val enhancedText = if (trigger.ttsPriority == com.healthyagent.running.core.model.SpeechPriority.URGENT) {
                                emergencyPhraseCache.consume(trigger.ruleId) ?: trigger.speakText
                            } else {
                                trigger.speakText
                            }
                            speechBus.enqueue(trigger.copy(speakText = enhancedText))
                        }
                    }
                }
        }
    }

    fun startRun() {
        _runState.value = RunState.RUNNING
        ruleEngine.reset()
        sessionManager.startSession(runConfig)
        ruleContext = RuleContext()
        recentTriggerTexts.clear()
        analysisManager.cancel()
        // 跑步开始：预生成紧急指令修饰语（异步，不阻塞起跑）
        emergencyPhraseCache.clear()
        emergencyPhraseCache.pregenerate(runConfig)
    }

    fun pauseRun() {
        _runState.value = RunState.PAUSED
        sessionManager.pause()
    }

    fun resumeRun() {
        _runState.value = RunState.RUNNING
        sessionManager.resume()
    }

    fun stopRun() {
        _runState.value = RunState.FINISHED
        val session = sessionManager.finishSession()
        if (session != null) {
            viewModelScope.launch {
                repository.saveSession(session)
            }
        }
        speechBus.stop()
        emergencyPhraseCache.clear()
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
        speechBus.shutdown()
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
