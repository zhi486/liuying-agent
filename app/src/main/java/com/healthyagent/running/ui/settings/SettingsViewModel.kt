package com.healthyagent.running.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.ai.LlmConfig
import com.healthyagent.running.data.local.NullableSettingUpdate
import com.healthyagent.running.data.local.SettingsPatch
import com.healthyagent.running.data.local.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 迁移期 Compose 设置页的聚合状态适配器。 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _targetPaceMin = MutableStateFlow("6")
    val targetPaceMin: StateFlow<String> = _targetPaceMin.asStateFlow()

    private val _targetPaceSec = MutableStateFlow("0")
    val targetPaceSec: StateFlow<String> = _targetPaceSec.asStateFlow()

    private val _targetDistance = MutableStateFlow("5.0")
    val targetDistance: StateFlow<String> = _targetDistance.asStateFlow()

    private val _maxHeartRate = MutableStateFlow("180")
    val maxHeartRate: StateFlow<String> = _maxHeartRate.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _ttsMinInterval = MutableStateFlow("4")
    val ttsMinInterval: StateFlow<String> = _ttsMinInterval.asStateFlow()

    private val _llmEnabled = MutableStateFlow(false)
    val llmEnabled: StateFlow<Boolean> = _llmEnabled.asStateFlow()

    private val _mockScenario = MutableStateFlow("EASY_RUN")
    val mockScenario: StateFlow<String> = _mockScenario.asStateFlow()

    private val _fishApiKey = MutableStateFlow("")
    val fishApiKey: StateFlow<String> = _fishApiKey.asStateFlow()

    private val _llmApiKey = MutableStateFlow("")
    val llmApiKey: StateFlow<String> = _llmApiKey.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { snapshot ->
                snapshot.targetPaceSecPerKm?.let { pace ->
                    _targetPaceMin.value = (pace / 60).toInt().toString()
                    _targetPaceSec.value = (pace % 60).toInt().toString()
                }
                _targetDistance.value = snapshot.targetDistanceKm?.toString() ?: ""
                _maxHeartRate.value = snapshot.maxHeartRate.toString()
                _ttsEnabled.value = snapshot.ttsEnabled
                _ttsMinInterval.value = snapshot.ttsMinIntervalSec.toString()
                _llmEnabled.value = snapshot.llmEnabled
                _mockScenario.value = snapshot.mockScenario
                _fishApiKey.value = snapshot.fishApiKey
                _llmApiKey.value = snapshot.llmApiKey
                LlmConfig.apiKey = snapshot.llmApiKey
            }
        }
    }

    fun setTargetPace(min: String, sec: String) {
        _targetPaceMin.value = min
        _targetPaceSec.value = sec
        val totalSec = (min.toDoubleOrNull() ?: 0.0) * 60 + (sec.toDoubleOrNull() ?: 0.0)
        val update = when {
            min.isBlank() && sec.isBlank() -> NullableSettingUpdate.Clear
            totalSec in 120.0..1200.0 -> NullableSettingUpdate.Set(totalSec)
            else -> return
        }
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(targetPaceSecPerKm = update))
        }
    }

    fun setTargetDistance(km: String) {
        _targetDistance.value = km
        val value = km.toDoubleOrNull()
        val update = when {
            km.isBlank() -> NullableSettingUpdate.Clear
            value != null && value in 0.1..200.0 -> NullableSettingUpdate.Set(value)
            else -> return
        }
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(targetDistanceKm = update))
        }
    }

    fun setMaxHeartRate(hr: String) {
        _maxHeartRate.value = hr
        val value = hr.toIntOrNull()?.takeIf { it in 80..240 } ?: return
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(maxHeartRate = value))
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(ttsEnabled = enabled))
        }
    }

    fun setTtsMinInterval(sec: String) {
        _ttsMinInterval.value = sec
        val value = sec.toIntOrNull()?.takeIf { it in 0..300 } ?: return
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(ttsMinIntervalSec = value))
        }
    }

    fun setLlmEnabled(enabled: Boolean) {
        _llmEnabled.value = enabled
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(llmEnabled = enabled))
        }
    }

    fun setMockScenario(scenario: String) {
        _mockScenario.value = scenario
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(mockScenario = scenario))
        }
    }

    fun setFishApiKey(key: String) {
        _fishApiKey.value = key
        if (key.length > 512) return
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(fishApiKey = key))
        }
    }

    fun setLlmApiKey(key: String) {
        _llmApiKey.value = key
        if (key.length > 512) return
        LlmConfig.apiKey = key
        viewModelScope.launch {
            settingsRepository.update(SettingsPatch(llmApiKey = key))
        }
    }
}
