package com.healthyagent.running.data.local

import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.core.model.RunConfig
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<SettingsSnapshot>

    suspend fun current(): SettingsSnapshot

    suspend fun update(patch: SettingsPatch)
}

enum class ThemePreference {
    SYSTEM,
    MORNING,
    NIGHT,
}

enum class MotionPreference {
    SYSTEM,
    EXPRESSIVE,
    BALANCED,
    REDUCED,
    OFF,
}

data class SettingsSnapshot(
    val targetPaceSecPerKm: Double? = null,
    val targetDistanceKm: Double? = null,
    val maxHeartRate: Int = SettingsDataStore.DEFAULT_MAX_HR,
    val hrAlertThresholdBpm: Int? = null,
    val hrAlertRepeatIntervalSec: Int = 120,
    val ttsEnabled: Boolean = true,
    val ttsMinIntervalSec: Int = SettingsDataStore.DEFAULT_TTS_INTERVAL,
    val llmEnabled: Boolean = false,
    val mockScenario: String = "EASY_RUN",
    val coachTone: CoachTone = CoachTone.BALANCED,
    val fishApiKey: String = "",
    val fishReferenceId: String = SettingsDataStore.DEFAULT_FISH_REFERENCE_ID,
    val llmApiKey: String = "",
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val motionPreference: MotionPreference = MotionPreference.SYSTEM,
)

/** 将持久化偏好转换成一次跑步启动时冻结的配置。 */
fun SettingsSnapshot.toRunConfig(): RunConfig = RunConfig(
    targetPaceSecPerKm = targetPaceSecPerKm,
    targetDistanceMeters = targetDistanceKm?.times(1_000.0),
    maxHeartRate = maxHeartRate,
    hrAlertThresholdBpm = hrAlertThresholdBpm,
    hrAlertRepeatIntervalSec = hrAlertRepeatIntervalSec,
    ttsEnabled = ttsEnabled,
    ttsMinIntervalSec = ttsMinIntervalSec,
    llmEnabled = llmEnabled,
    coachTone = coachTone,
)
