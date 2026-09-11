package com.healthyagent.running.coordinator

import com.healthyagent.running.core.model.CoachMessage
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.data.local.MotionPreference
import com.healthyagent.running.data.local.SettingsSnapshot
import com.healthyagent.running.data.local.ThemePreference
import java.time.Instant
import kotlinx.serialization.Serializable

/** React 与原生安全页共同消费的完整应用快照。 */
@Serializable
data class AppSnapshot(
    val revision: Int,
    val generatedAt: String,
    val connection: ConnectionSnapshot,
    val wearable: WearableSnapshot,
    val session: SessionSnapshot,
    val metrics: MetricsSnapshot,
    val completedRun: CompletedRunSnapshot?,
    val persistenceState: String,
    val healthSync: HealthSyncSnapshot,
    val latestCoachMessage: CoachMessageSnapshot?,
    val settings: SettingsContractSnapshot,
    val permissions: PermissionsSnapshot,
    val theme: String,
    val motionPolicy: String,
    val safetyState: SafetySnapshot,
    val dataQuality: DataQualitySnapshot,
) {
    companion object {
        fun initial(config: RunConfig, nowMillis: Long): AppSnapshot = AppSnapshot(
            revision = 0,
            generatedAt = Instant.ofEpochMilli(nowMillis).toString(),
            connection = ConnectionSnapshot(),
            wearable = WearableSnapshot(),
            session = SessionSnapshot(
                targetPaceSecPerKm = config.targetPaceSecPerKm,
                targetDistanceMeters = config.targetDistanceMeters,
            ),
            metrics = MetricsSnapshot(),
            completedRun = null,
            persistenceState = "idle",
            healthSync = HealthSyncSnapshot(),
            latestCoachMessage = null,
            settings = SettingsContractSnapshot.from(config),
            permissions = PermissionsSnapshot(),
            theme = "night",
            motionPolicy = "balanced",
            safetyState = SafetySnapshot(),
            dataQuality = DataQualitySnapshot(),
        )
    }
}

@Serializable
data class DataQualitySnapshot(
    val gps: String = "waiting",
    val heartRate: String = "not_available",
)

@Serializable
data class ConnectionSnapshot(
    val state: String = "disconnected",
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
)

@Serializable
data class WearableSnapshot(
    val supported: Boolean = false,
    val state: String = "disconnected",
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
)

@Serializable
data class SessionSnapshot(
    val id: String? = null,
    val state: String = "idle",
    val startedAt: String? = null,
    val targetPaceSecPerKm: Double? = null,
    val targetDistanceMeters: Double? = null,
)

@Serializable
data class MetricsSnapshot(
    val heartRate: Int? = null,
    val paceSecPerKm: Double? = null,
    val cadence: Int? = null,
    val strideLengthCm: Double? = null,
    val distanceMeters: Double = 0.0,
    val elapsedSeconds: Int = 0,
    val hrZone: String? = null,
    val isDataFresh: Boolean? = null,
)

@Serializable
data class CompletedRunSnapshot(
    val endedAt: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val averagePaceSecPerKm: Double?,
    val averageHeartRate: Int?,
    val averageCadence: Int?,
)

@Serializable
data class HealthSyncSnapshot(
    val state: String = "idle",
    val routeWritten: Boolean? = null,
)

@Serializable
data class CoachMessageSnapshot(
    val id: String,
    val text: String,
    val priority: String,
    val timestampMillis: Long,
)

@Serializable
data class SettingsContractSnapshot(
    val targetPaceSecPerKm: Double?,
    val targetDistanceKm: Double?,
    val maxHeartRate: Int,
    val hrAlertThresholdBpm: Int?,
    val hrAlertRepeatIntervalSec: Int,
    val ttsEnabled: Boolean,
    val ttsMinIntervalSec: Int,
    val llmEnabled: Boolean,
    val mockScenario: String,
    val coachTone: String,
    val fishReferenceId: String,
    val fishApiKeyConfigured: Boolean,
    val llmApiKeyConfigured: Boolean,
    val themePreference: String,
    val motionPreference: String,
) {
    companion object {
        fun from(config: RunConfig): SettingsContractSnapshot = SettingsContractSnapshot(
            targetPaceSecPerKm = config.targetPaceSecPerKm,
            targetDistanceKm = config.targetDistanceMeters?.div(1000.0),
            maxHeartRate = config.maxHeartRate,
            hrAlertThresholdBpm = config.hrAlertThresholdBpm,
            hrAlertRepeatIntervalSec = config.hrAlertRepeatIntervalSec,
            ttsEnabled = config.ttsEnabled,
            ttsMinIntervalSec = config.ttsMinIntervalSec,
            llmEnabled = config.llmEnabled,
            mockScenario = "EASY_RUN",
            coachTone = config.coachTone.name.lowercase(),
            fishReferenceId = "",
            fishApiKeyConfigured = false,
            llmApiKeyConfigured = false,
            themePreference = "system",
            motionPreference = "system",
        )

        fun from(settings: SettingsSnapshot): SettingsContractSnapshot = SettingsContractSnapshot(
            targetPaceSecPerKm = settings.targetPaceSecPerKm,
            targetDistanceKm = settings.targetDistanceKm,
            maxHeartRate = settings.maxHeartRate,
            hrAlertThresholdBpm = settings.hrAlertThresholdBpm,
            hrAlertRepeatIntervalSec = settings.hrAlertRepeatIntervalSec,
            ttsEnabled = settings.ttsEnabled,
            ttsMinIntervalSec = settings.ttsMinIntervalSec,
            llmEnabled = settings.llmEnabled,
            mockScenario = settings.mockScenario,
            coachTone = settings.coachTone.name.lowercase(),
            fishReferenceId = settings.fishReferenceId,
            fishApiKeyConfigured = settings.fishApiKey.isNotBlank(),
            llmApiKeyConfigured = settings.llmApiKey.isNotBlank(),
            themePreference = settings.themePreference.name.lowercase(),
            motionPreference = settings.motionPreference.name.lowercase(),
        )
    }
}

@Serializable
data class PermissionsSnapshot(
    val bluetooth: String = "notRequested",
    val bodySensors: String = "notRequested",
    val runningSensors: String = "notRequested",
)

@Serializable
data class SafetySnapshot(
    val level: String = "normal",
    val message: String? = null,
)

internal fun ConnectionState.toContractValue(): String = name.lowercase()

internal fun RunState.toContractValue(): String = name.lowercase()

internal fun RunSession.toSnapshot(config: RunConfig): SessionSnapshot = SessionSnapshot(
    id = id,
    state = runState.toContractValue(),
    startedAt = Instant.ofEpochMilli(startTimeMillis).toString(),
    targetPaceSecPerKm = config.targetPaceSecPerKm,
    targetDistanceMeters = config.targetDistanceMeters,
)

internal fun MetricsFrame.toSnapshot(): MetricsSnapshot = MetricsSnapshot(
    heartRate = if (isHeartRateFresh) heartRateSmoothed ?: heartRate else null,
    paceSecPerKm = currentPaceSecPerKm,
    cadence = cadence,
    strideLengthCm = strideLengthCm?.toDouble(),
    distanceMeters = distanceMeters,
    elapsedSeconds = elapsedSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    hrZone = if (isHeartRateFresh && (heartRateSmoothed ?: heartRate) != null) {
        hrZone.toContractValue()
    } else {
        null
    },
    isDataFresh = isDataFresh,
)

internal fun RunSession.toCompletedRunSnapshot(): CompletedRunSnapshot = CompletedRunSnapshot(
    endedAt = Instant.ofEpochMilli(requireNotNull(endTimeMillis)).toString(),
    distanceMeters = totalDistanceMeters,
    durationSeconds = totalDurationSec,
    averagePaceSecPerKm = avgPaceSecPerKm,
    averageHeartRate = avgHeartRate,
    averageCadence = avgCadence,
)

internal fun HrZone.toContractValue(): String = name.lowercase()

internal fun CoachMessage.toSnapshot(): CoachMessageSnapshot = CoachMessageSnapshot(
    id = id,
    text = text,
    priority = priority.name.lowercase(),
    timestampMillis = timestampMillis,
)

internal fun ThemePreference.toTheme(): String = when (this) {
    ThemePreference.MORNING -> "morning"
    ThemePreference.NIGHT, ThemePreference.SYSTEM -> "night"
}

internal fun MotionPreference.toPolicy(): String = when (this) {
    MotionPreference.EXPRESSIVE -> "expressive"
    MotionPreference.REDUCED -> "reduced"
    MotionPreference.OFF -> "off"
    MotionPreference.BALANCED, MotionPreference.SYSTEM -> "balanced"
}
