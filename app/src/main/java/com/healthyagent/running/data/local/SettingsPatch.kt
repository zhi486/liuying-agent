package com.healthyagent.running.data.local

import com.healthyagent.running.core.model.CoachTone

sealed interface NullableSettingUpdate<out T> {
    data object Unchanged : NullableSettingUpdate<Nothing>

    data object Clear : NullableSettingUpdate<Nothing>

    data class Set<T>(val value: T) : NullableSettingUpdate<T>
}

data class SettingsPatch(
    val targetPaceSecPerKm: NullableSettingUpdate<Double> = NullableSettingUpdate.Unchanged,
    val targetDistanceKm: NullableSettingUpdate<Double> = NullableSettingUpdate.Unchanged,
    val maxHeartRate: Int? = null,
    val hrAlertThresholdBpm: NullableSettingUpdate<Int> = NullableSettingUpdate.Unchanged,
    val hrAlertRepeatIntervalSec: Int? = null,
    val ttsEnabled: Boolean? = null,
    val ttsMinIntervalSec: Int? = null,
    val llmEnabled: Boolean? = null,
    val mockScenario: String? = null,
    val coachTone: CoachTone? = null,
    val fishApiKey: String? = null,
    val fishReferenceId: String? = null,
    val llmApiKey: String? = null,
    val themePreference: ThemePreference? = null,
    val motionPreference: MotionPreference? = null,
)

enum class SettingsPatchError {
    EMPTY_PATCH,
    INVALID_TARGET_PACE,
    INVALID_TARGET_DISTANCE,
    INVALID_MAX_HEART_RATE,
    INVALID_HR_ALERT_THRESHOLD,
    INVALID_HR_ALERT_INTERVAL,
    INVALID_TTS_INTERVAL,
    INVALID_MOCK_SCENARIO,
    API_KEY_TOO_LONG,
    INVALID_REFERENCE_ID,
}

fun SettingsPatch.validate(): List<SettingsPatchError> {
    val patch = this
    return buildList {
        if (patch.hasNoChanges()) add(SettingsPatchError.EMPTY_PATCH)

        if (targetPaceSecPerKm is NullableSettingUpdate.Set &&
            targetPaceSecPerKm.value !in 120.0..1200.0
        ) {
            add(SettingsPatchError.INVALID_TARGET_PACE)
        }
        if (targetDistanceKm is NullableSettingUpdate.Set &&
            targetDistanceKm.value !in 0.1..200.0
        ) {
            add(SettingsPatchError.INVALID_TARGET_DISTANCE)
        }
        if (maxHeartRate != null && maxHeartRate !in 80..240) {
            add(SettingsPatchError.INVALID_MAX_HEART_RATE)
        }
        if (hrAlertThresholdBpm is NullableSettingUpdate.Set && hrAlertThresholdBpm.value !in 80..240) {
            add(SettingsPatchError.INVALID_HR_ALERT_THRESHOLD)
        }
        if (hrAlertRepeatIntervalSec != null && hrAlertRepeatIntervalSec !in 30..300) {
            add(SettingsPatchError.INVALID_HR_ALERT_INTERVAL)
        }
        if (ttsMinIntervalSec != null && ttsMinIntervalSec !in 0..300) {
            add(SettingsPatchError.INVALID_TTS_INTERVAL)
        }
        if (mockScenario != null && mockScenario !in VALID_MOCK_SCENARIOS) {
            add(SettingsPatchError.INVALID_MOCK_SCENARIO)
        }
        if ((fishApiKey?.length ?: 0) > 512 || (llmApiKey?.length ?: 0) > 512) {
            add(SettingsPatchError.API_KEY_TOO_LONG)
        }
        if (fishReferenceId != null && fishReferenceId.length !in 1..128) {
            add(SettingsPatchError.INVALID_REFERENCE_ID)
        }
    }
}

private fun SettingsPatch.hasNoChanges(): Boolean =
    targetPaceSecPerKm == NullableSettingUpdate.Unchanged &&
        targetDistanceKm == NullableSettingUpdate.Unchanged &&
        maxHeartRate == null &&
        hrAlertThresholdBpm == NullableSettingUpdate.Unchanged &&
        hrAlertRepeatIntervalSec == null &&
        ttsEnabled == null &&
        ttsMinIntervalSec == null &&
        llmEnabled == null &&
        mockScenario == null &&
        coachTone == null &&
        fishApiKey == null &&
        fishReferenceId == null &&
        llmApiKey == null &&
        themePreference == null &&
        motionPreference == null

private val VALID_MOCK_SCENARIOS = setOf(
    "EASY_RUN",
    "SPEED_TEST",
    "PROGRESSIVE",
    "HEART_RATE_SPIKE",
    "CADENCE_DROP",
)
