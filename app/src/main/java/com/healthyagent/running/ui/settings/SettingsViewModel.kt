package com.healthyagent.running.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.ai.LlmConfig
import com.healthyagent.running.data.local.SettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel — DataStore 双向绑定
 */
class SettingsViewModel(
    private val settings: SettingsDataStore,
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
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settings.targetPaceSecPerKm.collect { pace ->
                if (pace != null) {
                    _targetPaceMin.value = (pace / 60).toInt().toString()
                    _targetPaceSec.value = (pace % 60).toInt().toString()
                }
            }
        }
        viewModelScope.launch {
            settings.targetDistanceKm.collect { dist ->
                _targetDistance.value = dist?.toString() ?: ""
            }
        }
        viewModelScope.launch {
            settings.maxHeartRate.collect { hr ->
                _maxHeartRate.value = hr.toString()
            }
        }
        viewModelScope.launch {
            settings.ttsEnabled.collect { _ttsEnabled.value = it }
        }
        viewModelScope.launch {
            settings.ttsMinIntervalSec.collect { _ttsMinInterval.value = it.toString() }
        }
        viewModelScope.launch {
            settings.llmEnabled.collect { _llmEnabled.value = it }
        }
        viewModelScope.launch {
            settings.mockScenario.collect { _mockScenario.value = it }
        }
        viewModelScope.launch {
            settings.fishApiKey.collect { _fishApiKey.value = it }
        }
        viewModelScope.launch {
            settings.llmApiKey.collect {
                _llmApiKey.value = it
                LlmConfig.apiKey = it  // 同步到 AnthropicLlmClient
            }
        }
    }

    // ── 写入 ──

    fun setTargetPace(min: String, sec: String) {
        _targetPaceMin.value = min
        _targetPaceSec.value = sec
        val totalSec = (min.toDoubleOrNull() ?: 0.0) * 60 + (sec.toDoubleOrNull() ?: 0.0)
        viewModelScope.launch {
            settings.setTargetPace(if (totalSec > 0) totalSec else null)
        }
    }

    fun setTargetDistance(km: String) {
        _targetDistance.value = km
        viewModelScope.launch {
            settings.setTargetDistance(km.toDoubleOrNull())
        }
    }

    fun setMaxHeartRate(hr: String) {
        _maxHeartRate.value = hr
        viewModelScope.launch {
            hr.toIntOrNull()?.let { settings.setMaxHeartRate(it) }
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
        viewModelScope.launch { settings.setTtsEnabled(enabled) }
    }

    fun setTtsMinInterval(sec: String) {
        _ttsMinInterval.value = sec
        viewModelScope.launch {
            sec.toIntOrNull()?.let { settings.setTtsMinInterval(it) }
        }
    }

    fun setLlmEnabled(enabled: Boolean) {
        _llmEnabled.value = enabled
        viewModelScope.launch { settings.setLlmEnabled(enabled) }
    }

    fun setMockScenario(scenario: String) {
        _mockScenario.value = scenario
        viewModelScope.launch { settings.setMockScenario(scenario) }
    }

    fun setFishApiKey(key: String) {
        _fishApiKey.value = key
        viewModelScope.launch { settings.setFishApiKey(key) }
    }

    fun setLlmApiKey(key: String) {
        _llmApiKey.value = key
        LlmConfig.apiKey = key  // 即时生效
        viewModelScope.launch { settings.setLlmApiKey(key) }
    }
}
