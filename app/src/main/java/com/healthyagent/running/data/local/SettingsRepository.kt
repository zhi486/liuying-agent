package com.healthyagent.running.data.local

import com.healthyagent.running.core.model.CoachTone
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
