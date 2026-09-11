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
import kotlinx.coroutines.CancellationException

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

    private val _hrAlertThresholdBpm = MutableStateFlow("")
    val hrAlertThresholdBpm: StateFlow<String> = _hrAlertThresholdBpm.asStateFlow()
    private val _hrAlertRepeatIntervalSec = MutableStateFlow("120")
    val hrAlertRepeatIntervalSec: StateFlow<String> = _hrAlertRepeatIntervalSec.asStateFlow()
    private val _hrAlertError = MutableStateFlow<String?>(null)
    val hrAlertError: StateFlow<String?> = _hrAlertError.asStateFlow()
    private var hrThresholdRevision = 0L
    private var hrIntervalRevision = 0L
    private var hrThresholdDirty = false
    private var hrIntervalDirty = false
    private var hrThresholdError: String? = null
    private var hrIntervalError: String? = null

    fun setHrAlertThresholdBpm(value: String) {
        val revision = ++hrThresholdRevision
        hrThresholdDirty = true
        _hrAlertThresholdBpm.value = value
        val parsed = value.toIntOrNull()
        val update = when {
            value.isBlank() -> NullableSettingUpdate.Clear
            parsed != null && parsed in 80..240 -> NullableSettingUpdate.Set(parsed)
            else -> {
                hrThresholdError = "警戒心率应为 80-240 的整数，或留空恢复自动"
                publishHrAlertErrors()
                return
            }
        }
        persistHrAlertPatch(SettingsPatch(hrAlertThresholdBpm = update)) { error ->
            if (revision == hrThresholdRevision) {
                hrThresholdError = error
                if (error == null) {
                    hrThresholdDirty = false
                    _hrAlertThresholdBpm.value = parsed?.toString() ?: ""
                }
                publishHrAlertErrors()
            }
        }
    }

    fun setHrAlertRepeatIntervalSec(value: String) {
        val revision = ++hrIntervalRevision
        hrIntervalDirty = true
        _hrAlertRepeatIntervalSec.value = value
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed !in 30..300) {
            hrIntervalError = "高心率重复提醒间隔应为 30-300 的整数"
            publishHrAlertErrors()
            return
        }
        persistHrAlertPatch(SettingsPatch(hrAlertRepeatIntervalSec = parsed)) { error ->
            if (revision == hrIntervalRevision) {
                hrIntervalError = error
                if (error == null) {
                    hrIntervalDirty = false
                    _hrAlertRepeatIntervalSec.value = parsed.toString()
                }
                publishHrAlertErrors()
            }
        }
    }

    private fun persistHrAlertPatch(patch: SettingsPatch, completed: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                settingsRepository.update(patch)
                completed(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                completed("心率警戒设置保存失败，请重试")
            }
        }
    }

    private fun publishHrAlertErrors() {
        _hrAlertError.value = listOfNotNull(hrThresholdError, hrIntervalError)
            .distinct().joinToString("\n").ifEmpty { null }
    }

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
                // 未保存或保存中的新输入不被旧回显及另一字段的快照覆盖。
                if (!hrThresholdDirty) _hrAlertThresholdBpm.value = snapshot.hrAlertThresholdBpm?.toString() ?: ""
                if (!hrIntervalDirty) _hrAlertRepeatIntervalSec.value = snapshot.hrAlertRepeatIntervalSec.toString()
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
