package com.healthyagent.running.bridge

import com.healthyagent.running.agent.AgentCommands
import com.healthyagent.running.agent.cloud.CloudAgentCommands
import com.healthyagent.running.agent.memory.MemoryCommands
import com.healthyagent.running.agent.memory.MemoryExportCommands

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BridgeCodec {
    private const val MAX_MESSAGE_BYTES = 262_144
    private val canonicalUuidV4 = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )

    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun decode(raw: String): BridgeDecodeResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            return BridgeDecodeResult.Rejected(BridgeErrorCode.INVALID_MESSAGE)
        }

        val envelope = try {
            json.decodeFromString<BridgeEnvelope>(raw)
        } catch (_: Exception) {
            return BridgeDecodeResult.Rejected(BridgeErrorCode.INVALID_MESSAGE)
        }

        if (envelope.contractVersion != 1) {
            return BridgeDecodeResult.Rejected(BridgeErrorCode.BRIDGE_VERSION_UNSUPPORTED)
        }

        return try {
            requireCanonicalUuidV4(envelope.id)
            Instant.parse(envelope.sentAt)
            validatePayload(envelope.type, envelope.payload)
            BridgeDecodeResult.Accepted(envelope)
        } catch (_: Exception) {
            BridgeDecodeResult.Rejected(BridgeErrorCode.INVALID_MESSAGE)
        }
    }

    fun encode(envelope: BridgeEnvelope): String {
        require(envelope.contractVersion == 1)
        requireCanonicalUuidV4(envelope.id)
        Instant.parse(envelope.sentAt)
        validatePayload(envelope.type, envelope.payload)
        return json.encodeToString(envelope)
    }

    private fun validatePayload(type: BridgeMessageType, payload: JsonObject) {
        when (type) {
            BridgeMessageType.HELLO -> validateHello(payload)
            BridgeMessageType.READY -> validateReady(payload)
            BridgeMessageType.SNAPSHOT -> validateSnapshot(payload)
            BridgeMessageType.COMMAND -> validateCommand(payload)
            BridgeMessageType.RESULT -> validateResult(payload)
            BridgeMessageType.EVENT -> validateEvent(payload)
        }
    }

    private fun validateHello(payload: JsonObject) {
        val value = json.decodeFromJsonElement<HelloPayloadWire>(payload)
        require(value.buildId.isNotBlank())
        require(value.supportedContractVersions == listOf(1))
    }

    private fun validateReady(payload: JsonObject) {
        validateDeepseekConfiguration(payload.getValue("snapshot").jsonObject)
        val value = json.decodeFromJsonElement<ReadyPayloadWire>(payload)
        requireCanonicalUuidV4(value.helloId)
        require(value.buildId.isNotBlank())
        require(
            value.capabilities == listOf(
                "snapshot.v1",
                "history.pagination",
                "settings.v1",
                "heart-rate-alerts.v1",
                "data-quality.v1",
                "deepseek-companion.v1",
                "agent.tasks.v1",
                "memory.v1",
                "agent.cloud.tasks.v1",
            ),
        )
        validateSnapshotValue(value.snapshot)
    }

    private fun validateSnapshot(payload: JsonObject) {
        validateDeepseekConfiguration(payload)
        validateSnapshotValue(json.decodeFromJsonElement<AppSnapshotWire>(payload))
    }

    private fun validateDeepseekConfiguration(snapshot: JsonObject) {
        val value = snapshot.getValue("settings").jsonObject.getValue("deepseekApiKeyConfigured").jsonPrimitive
        require(!value.isString && value.booleanOrNull != null)
    }

    private fun validateSnapshotValue(value: AppSnapshotWire) {
        require(value.revision >= 0)
        Instant.parse(value.generatedAt)

        require(value.connection.state in CONNECTION_STATES)
        require(value.connection.deviceName == null || value.connection.deviceName.isNotBlank())
        require(value.connection.batteryPercent == null || value.connection.batteryPercent in 0..100)

        require(value.wearable.state in CONNECTION_STATES)
        require(value.wearable.deviceName == null || value.wearable.deviceName.isNotBlank())
        require(value.wearable.batteryPercent == null || value.wearable.batteryPercent in 0..100)

        value.session.id?.let(::requireCanonicalUuidV4)
        require(value.session.state in SESSION_STATES)
        value.session.startedAt?.let(Instant::parse)
        requireNonNegative(value.session.targetPaceSecPerKm)
        requireNonNegative(value.session.targetDistanceMeters)

        require(value.metrics.heartRate == null || value.metrics.heartRate >= 0)
        require(value.dataQuality.gps in setOf("waiting", "fresh", "stale"))
        require(value.dataQuality.heartRate in setOf("not_available", "waiting", "fresh", "stale"))
        requireNonNegative(value.metrics.paceSecPerKm)
        require(value.metrics.cadence == null || value.metrics.cadence >= 0)
        requireNonNegative(value.metrics.strideLengthCm)
        require(value.metrics.distanceMeters >= 0)
        require(value.metrics.elapsedSeconds >= 0)
        require(value.metrics.hrZone == null || value.metrics.hrZone in HR_ZONES)
        require(value.metrics.heartRate != null || value.metrics.hrZone == null)

        value.completedRun?.let { run ->
            Instant.parse(run.endedAt)
            require(run.distanceMeters >= 0)
            require(run.durationSeconds >= 0)
            requireNonNegative(run.averagePaceSecPerKm)
            require(run.averageHeartRate == null || run.averageHeartRate >= 0)
            require(run.averageCadence == null || run.averageCadence >= 0)
        }
        require(value.persistenceState in PERSISTENCE_STATES)
        require(value.healthSync.state in HEALTH_SYNC_STATES)

        value.latestCoachMessage?.let { message ->
            requireCanonicalUuidV4(message.id)
            require(message.text.isNotBlank())
            require(message.priority in MESSAGE_PRIORITIES)
            require(message.timestampMillis >= 0)
        }

        requireNonNegative(value.settings.targetPaceSecPerKm)
        requireNonNegative(value.settings.targetDistanceKm)
        require(value.settings.maxHeartRate > 0)
        require(value.settings.hrAlertThresholdBpm == null || value.settings.hrAlertThresholdBpm in 80..240)
        require(value.settings.hrAlertRepeatIntervalSec in 30..300)
        require(value.settings.ttsMinIntervalSec >= 0)
        require(value.settings.mockScenario in MOCK_SCENARIOS)
        require(value.settings.coachTone in COACH_TONES)
        require(value.settings.themePreference in THEME_PREFERENCES)
        require(value.settings.motionPreference in MOTION_PREFERENCES)

        require(value.permissions.bluetooth in PERMISSION_STATES)
        require(value.permissions.bodySensors in PERMISSION_STATES)
        require(value.permissions.runningSensors in PERMISSION_STATES)
        require(value.theme in THEMES)
        require(value.motionPolicy in MOTION_POLICIES)
        require(value.safetyState.level in SAFETY_LEVELS)
        require(value.safetyState.message == null || value.safetyState.message.isNotBlank())
    }

    private fun validateCommand(payload: JsonObject) {
        requireExactKeys(payload, setOf("helloId", "name", "body"))
        requireCanonicalUuidV4(payload.requiredString("helloId"))
        val name = payload.requiredString("name")
        val body = payload.getValue("body").jsonObject

        when (name) {
            "mcp.status", "mcp.list", "mcp.detail", "mcp.register", "mcp.configure", "mcp.revokePrivate", "mcp.disable", "mcp.approve", "mcp.discover", "mcp.recover", "mcp.endpoint", "mcp.remove" -> com.healthyagent.running.agent.mcp.McpManagementCommands.validate(name, body)
            "voice.begin", "voice.status", "voice.finish", "voice.cancel" -> com.healthyagent.running.agent.voice.VoiceInputService.validate(name, body)
            "cloud.speak", "cloud.stopSpeech" -> CloudAgentCommands.validate(name, body)
            "cloud.list", "cloud.configure", "cloud.prepare", "cloud.confirm", "cloud.send", "cloud.chatConsent", "cloud.memoryAuthorization", "cloud.memoryConsent", "cloud.discard", "cloud.recover", "cloud.cancel", "cloud.withdraw", "cloud.newConversation", "cloud.selectConversation", "cloud.renameConversation", "cloud.archiveConversation", "cloud.restoreConversation", "cloud.deleteConversation", "cloud.retryConversationCleanup" -> CloudAgentCommands.validate(name, body)
            "agent.submit", "agent.list", "agent.detail", "agent.cancel" -> AgentCommands.decode(name, body)
            "memory.exportBegin", "memory.exportStatus", "memory.exportCancel" -> MemoryExportCommands.decode(name, body)
            "memory.create", "memory.list", "memory.update", "memory.delete", "memory.preview", "memory.history", "memory.restore", "memory.document" -> MemoryCommands.decode(name, body)
            "device.connect",
            "device.disconnect",
            "run.start",
            "run.pause",
            "run.resume",
            "run.stop",
            "settings.query",
            "settings.configureDeepseek",
            -> require(body.isEmpty())

            "history.query" -> {
                requireExactKeys(body, setOf("page", "pageSize"))
                val page = body.requiredInt("page")
                val pageSize = body.requiredInt("pageSize")
                require(page > 0)
                require(pageSize in 1..100)
            }

            "history.detail" -> {
                requireExactKeys(body, setOf("sessionId"))
                val id = body.requiredString("sessionId")
                require(id.isNotBlank() && id.length <= 128 && id == id.trim())
            }
            "history.series" -> {
                requireExactKeys(body, setOf("sessionId", "offset", "limit"))
                val id = body.requiredString("sessionId")
                require(id.isNotBlank() && id.length <= 128 && id == id.trim())
                require(body.getValue("offset").nonNegativeIntOrNull() != null)
                require(body.getValue("limit").positiveIntOrNull()?.let { it <= 300 } == true)
            }
            "settings.update" -> validateSettingsUpdate(body)
            "permission.request" -> {
                requireExactKeys(body, setOf("kind"))
                require(body.requiredString("kind") == "runningSensors")
            }

            else -> error("未知命令")
        }
    }

    private fun validateSettingsUpdate(body: JsonObject) {
        require(body.isNotEmpty())
        require(body.keys.all { it in SETTINGS_UPDATE_KEYS })

        body.forEach { (key, value) ->
            when (key) {
                "targetPaceSecPerKm", "targetDistanceKm" ->
                    require(value is JsonNull || value.nonNegativeNumberOrNull() != null)

                "maxHeartRate" -> require(value.positiveIntOrNull() != null)
                "hrAlertThresholdBpm" -> require(value is JsonNull || value.positiveIntOrNull()?.let { it in 80..240 } == true)
                "hrAlertRepeatIntervalSec" -> require(value.positiveIntOrNull()?.let { it in 30..300 } == true)
                "ttsEnabled", "llmEnabled" -> require(value.booleanValueOrNull() != null)
                "ttsMinIntervalSec" -> require(value.nonNegativeIntOrNull() != null)
                "mockScenario" -> require(value.stringValueOrNull() in MOCK_SCENARIOS)
                "coachTone" -> require(value.stringValueOrNull() in COACH_TONES)
                "fishApiKey", "llmApiKey" -> require(!value.stringValueOrNull().isNullOrBlank())
                "fishReferenceId" -> require(value.stringValueOrNull() != null)
                "themePreference" -> require(value.stringValueOrNull() in THEME_PREFERENCES)
                "motionPreference" -> require(value.stringValueOrNull() in MOTION_PREFERENCES)
            }
        }
    }

    private fun validateResult(payload: JsonObject) {
        when (payload.requiredString("status")) {
            "accepted" -> requireExactKeys(payload, setOf("status", "data"))
            "rejected" -> {
                requireExactKeys(payload, setOf("status", "error"))
                val error = payload.getValue("error").jsonObject
                requireExactKeys(error, setOf("code", "message"))
                require(error.requiredString("code") in BridgeErrorCode.entries.map { it.name })
                require(error.requiredString("message").isNotBlank())
            }

            else -> error("未知结果状态")
        }
    }

    private fun validateEvent(payload: JsonObject) {
        when (payload.requiredString("name")) {
            "cloud.changed" -> requireExactKeys(payload, setOf("name"))
            "permission.result" -> {
                requireExactKeys(payload, setOf("name", "requestId", "kind", "granted"))
                requireCanonicalUuidV4(payload.requiredString("requestId"))
                require(payload.requiredString("kind") == "runningSensors")
                require(payload.getValue("granted").booleanValueOrNull() != null)
            }

            "notice" -> {
                requireExactKeys(payload, setOf("name", "code", "message"))
                require(payload.requiredString("code").isNotBlank())
                require(payload.requiredString("message").isNotBlank())
            }

            else -> error("未知事件")
        }
    }

    private fun requireCanonicalUuidV4(value: String) {
        require(canonicalUuidV4.matches(value))
        require(UUID.fromString(value).toString() == value)
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected)
    }

    private fun requireNonNegative(value: Double?) {
        require(value == null || value >= 0)
    }

    private fun JsonObject.requiredString(key: String): String =
        getValue(key).stringValueOrNull() ?: error("$key 必须是字符串")

    private fun JsonObject.requiredInt(key: String): Int =
        getValue(key).jsonPrimitive.intOrNull ?: error("$key 必须是整数")

    private fun JsonElement.stringValueOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement.booleanValueOrNull(): Boolean? =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonElement.nonNegativeNumberOrNull(): Double? =
        (this as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.doubleOrNull
            ?.takeIf { it >= 0 }

    private fun JsonElement.nonNegativeIntOrNull(): Int? =
        (this as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.intOrNull
            ?.takeIf { it >= 0 }

    private fun JsonElement.positiveIntOrNull(): Int? =
        nonNegativeIntOrNull()?.takeIf { it > 0 }

    private val CONNECTION_STATES = setOf("disconnected", "connecting", "connected", "error")
    private val PERSISTENCE_STATES = setOf("idle", "saving", "saved", "failed")
    private val HEALTH_SYNC_STATES = setOf(
        "idle",
        "syncing",
        "synced",
        "permissionRequired",
        "providerUpdateRequired",
        "unavailable",
        "failed",
    )
    private val SESSION_STATES = setOf("idle", "preparing", "running", "paused", "finished", "error")
    private val HR_ZONES = setOf("z1", "z2", "z3", "z4", "z5")
    private val MESSAGE_PRIORITIES = setOf("urgent", "split", "coach", "app")
    private val MOCK_SCENARIOS = setOf(
        "EASY_RUN",
        "SPEED_TEST",
        "PROGRESSIVE",
        "HEART_RATE_SPIKE",
        "CADENCE_DROP",
    )
    private val COACH_TONES = setOf("gentle", "balanced", "direct")
    private val THEME_PREFERENCES = setOf("system", "morning", "night")
    private val MOTION_PREFERENCES = setOf("system", "expressive", "balanced", "reduced", "off")
    private val PERMISSION_STATES = setOf("granted", "denied", "notRequested")
    private val THEMES = setOf("morning", "night")
    private val MOTION_POLICIES = setOf("expressive", "balanced", "reduced", "off")
    private val SAFETY_LEVELS = setOf("normal", "warning", "emergency")
    private val SETTINGS_UPDATE_KEYS = setOf(
        "targetPaceSecPerKm",
        "targetDistanceKm",
        "maxHeartRate",
        "hrAlertThresholdBpm",
        "hrAlertRepeatIntervalSec",
        "ttsEnabled",
        "ttsMinIntervalSec",
        "llmEnabled",
        "mockScenario",
        "coachTone",
        "fishApiKey",
        "fishReferenceId",
        "llmApiKey",
        "themePreference",
        "motionPreference",
    )
}

@Serializable
private data class HelloPayloadWire(
    val buildId: String,
    val supportedContractVersions: List<Int>,
)

@Serializable
private data class ReadyPayloadWire(
    val helloId: String,
    val buildId: String,
    val capabilities: List<String>,
    val snapshot: AppSnapshotWire,
)

@Serializable
private data class AppSnapshotWire(
    val revision: Int,
    val generatedAt: String,
    val connection: ConnectionWire,
    val wearable: WearableWire,
    val session: SessionWire,
    val metrics: MetricsWire,
    val dataQuality: DataQualityWire,
    val completedRun: CompletedRunWire?,
    val persistenceState: String,
    val healthSync: HealthSyncWire,
    val latestCoachMessage: CoachMessageWire?,
    val settings: SettingsWire,
    val permissions: PermissionsWire,
    val theme: String,
    val motionPolicy: String,
    val safetyState: SafetyStateWire,
)

@Serializable
private data class DataQualityWire(val gps: String, val heartRate: String)

@Serializable
private data class ConnectionWire(
    val state: String,
    val deviceName: String?,
    val batteryPercent: Int?,
)

@Serializable
private data class WearableWire(
    val supported: Boolean,
    val state: String,
    val deviceName: String?,
    val batteryPercent: Int?,
)

@Serializable
private data class SessionWire(
    val id: String?,
    val state: String,
    val startedAt: String?,
    val targetPaceSecPerKm: Double?,
    val targetDistanceMeters: Double?,
)

@Serializable
private data class MetricsWire(
    val heartRate: Int?,
    val paceSecPerKm: Double?,
    val cadence: Int?,
    val strideLengthCm: Double?,
    val distanceMeters: Double,
    val elapsedSeconds: Int,
    val hrZone: String?,
    val isDataFresh: Boolean?,
)

@Serializable
private data class CompletedRunWire(
    val endedAt: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val averagePaceSecPerKm: Double?,
    val averageHeartRate: Int?,
    val averageCadence: Int?,
)

@Serializable
private data class HealthSyncWire(
    val state: String,
    val routeWritten: Boolean?,
)

@Serializable
private data class CoachMessageWire(
    val id: String,
    val text: String,
    val priority: String,
    val timestampMillis: Long,
)

@Serializable
private data class SettingsWire(
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
    val deepseekApiKeyConfigured: Boolean,
    val themePreference: String,
    val motionPreference: String,
)

@Serializable
private data class PermissionsWire(
    val bluetooth: String,
    val bodySensors: String,
    val runningSensors: String,
)

@Serializable
private data class SafetyStateWire(
    val level: String,
    val message: String?,
)
