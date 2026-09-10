package com.healthyagent.running.diagnostics

import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FileDiagnosticLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendsAcrossInstancesWithEnvelope() = runTest {
        val directory = temporaryFolder.newFolder("diagnostics")
        val processStartId = "123e4567-e89b-12d3-a456-426614174000"
        val now = Instant.parse("2026-09-05T00:00:00Z")
        FileDiagnosticLogger(
            directory,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            now = { now },
            processStartId = processStartId,
        ).also {
            it.record(DiagnosticEvent.RuleTriggered("km_split"))
            it.flush()
        }
        FileDiagnosticLogger(
            directory,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            now = { now },
            processStartId = processStartId,
        ).also {
            it.record(DiagnosticEvent.SpeechOutcome("km_split", SpeechOutcomeReason.TTS_SUCCESS))
            it.flush()
        }

        val decoded = File(directory, "diagnostics.jsonl").readLines().map(::decode)
        assertEquals(2, decoded.size)
        assertEquals(setOf("timestamp", "processStartId", "module", "eventName", "payload"), decoded[0].keys)
        assertEquals("rule", decoded[0].getValue("module").jsonPrimitive.content)
        assertEquals("rule_triggered", decoded[0].getValue("eventName").jsonPrimitive.content)
        assertEquals("km_split", decoded[0].getValue("payload").jsonObject.getValue("ruleId").jsonPrimitive.content)
        assertTrue(decoded.all { it.getValue("timestamp").jsonPrimitive.content == "2026-09-05T00:00:00Z" })
        assertTrue(decoded.all { it.getValue("processStartId").jsonPrimitive.content == processStartId })
    }

    @Test
    fun persistsEveryTypedEventWithExactWireNamesAndPayloadKeys() = runTest {
        val logger = newLogger()
        val cases = listOf(
            Case(DiagnosticEvent.BleLifecycleChanged(DiagnosticBleLifecycle.STOPPED, DiagnosticBleLifecycle.RUNNING, 1, 2), "ble", "ble_lifecycle_changed", setOf("from", "to", "epoch", "lifecycleRevision")),
            Case(DiagnosticEvent.BleLinkChanged(DiagnosticBleLink.CONNECTING, DiagnosticBleLink.READY_PRIVATE_B3, 3, null, BleFailureReason.GATT_ERROR), "ble", "ble_link_changed", setOf("from", "to", "attemptToken", "gattToken", "reason")),
            Case(DiagnosticEvent.BleReconnect(BleReconnectStage.FAILED, 4, 5, BleFailureReason.SCAN_TIMEOUT), "ble", "ble_reconnect", setOf("stage", "epoch", "attemptToken", "reason")),
            Case(DiagnosticEvent.BleSyncWrite(BleSyncKind.UPDATE_WORKOUT, BleWriteResult.ACCEPTED, 6, 7), "ble", "ble_sync_write", setOf("kind", "result", "epoch", "gattToken")),
            Case(DiagnosticEvent.BleResumeHealth(BleResumeDecision.STALE, null), "ble", "ble_resume_health", setOf("decision", "activityAgeMillis")),
            Case(DiagnosticEvent.BleStaleEventIgnored(BleStaleEventKind.GATT_CALLBACK, 8, 9, null), "ble", "ble_stale_event_ignored", setOf("kind", "epoch", "attemptToken", "gattToken")),
            Case(DiagnosticEvent.BleStaleWriteIgnored(BleSyncKind.HEART_BEAT, 10), "ble", "ble_stale_write_ignored", setOf("kind", "lifecycleRevision")),
            Case(DiagnosticEvent.BleOperationFailed(BleOperation.START_WRITE, BleFailureReason.START_WRITE_REJECTED, DiagnosticFailure.from(IllegalStateException(), SafeDiagnosticSummary.BLE_OPERATION_FAILED)), "ble", "ble_operation_failed", setOf("operation", "reason", "failure")),
            Case(DiagnosticEvent.RuleTriggered("rule"), "rule", "rule_triggered", setOf("ruleId")),
            Case(DiagnosticEvent.RuleCooldownSkipped("rule", 11), "rule", "rule_cooldown_skipped", setOf("ruleId", "windowStartedAtMillis")),
            Case(DiagnosticEvent.SpeechOutcome("rule", SpeechOutcomeReason.PLAYBACK_TIMEOUT), "speech", "speech_outcome", setOf("ruleId", "reason")),
            Case(DiagnosticEvent.TtsProviderEvent(TtsProvider.ANDROID, TtsEventKind.INITIALIZATION_FAILED), "tts", "tts_provider_event", setOf("provider", "kind", "failure")),
            Case(DiagnosticEvent.AudioFocusEvent(TtsProvider.FISH, AudioFocusResult.RELEASED), "audio", "audio_focus_event", setOf("provider", "result")),
        )

        cases.forEach { logger.record(it.event) }
        logger.flush()
        val lines = currentLog().readLines().map(::decode)

        assertEquals(cases.size, lines.size)
        cases.zip(lines).forEach { (case, decoded) ->
            assertEquals(case.module, decoded.getValue("module").jsonPrimitive.content)
            assertEquals(case.eventName, decoded.getValue("eventName").jsonPrimitive.content)
            assertEquals(case.payloadKeys, decoded.getValue("payload").jsonObject.keys)
        }
        assertEquals("ready_private_b3", lines[1].payloadValue("to"))
        assertEquals("gatt_error", lines[1].payloadValue("reason"))
        assertEquals("failed", lines[2].payloadValue("stage"))
        assertEquals("scan_timeout", lines[2].payloadValue("reason"))
        assertEquals("update_workout", lines[3].payloadValue("kind"))
        assertEquals("accepted", lines[3].payloadValue("result"))
        assertEquals("stale", lines[4].payloadValue("decision"))
        assertEquals("gatt_callback", lines[5].payloadValue("kind"))
        assertEquals("heart_beat", lines[6].payloadValue("kind"))
        assertEquals("start_write", lines[7].payloadValue("operation"))
        assertEquals("start_write_rejected", lines[7].payloadValue("reason"))
        assertEquals(setOf("type", "summary"), lines[7].getValue("payload").jsonObject.getValue("failure").jsonObject.keys)
        assertEquals("playback_timeout", lines[10].payloadValue("reason"))
        assertEquals("android", lines[11].payloadValue("provider"))
        assertEquals("initialization_failed", lines[11].payloadValue("kind"))
        assertEquals("fish", lines[12].payloadValue("provider"))
        assertEquals("released", lines[12].payloadValue("result"))
        assertEquals(JsonNull, lines[1].getValue("payload").jsonObject.getValue("gattToken"))
        assertEquals(JsonNull, lines[4].getValue("payload").jsonObject.getValue("activityAgeMillis"))
        assertEquals(JsonNull, lines[5].getValue("payload").jsonObject.getValue("gattToken"))
        assertEquals(JsonNull, lines[11].getValue("payload").jsonObject.getValue("failure"))
    }

    @Test
    fun serializesEveryEnumValueAsExplicitLowerSnakeCase() = runTest {
        val logger = newLogger()
        val cases = buildList {
            SpeechOutcomeReason.entries.forEach { add(EnumCase(DiagnosticEvent.SpeechOutcome("rule", it), "reason", when (it) { SpeechOutcomeReason.POLICY_DISABLED -> "policy_disabled"; SpeechOutcomeReason.DUPLICATE_INTERVAL -> "duplicate_interval"; SpeechOutcomeReason.PRIORITY_BLOCKED -> "priority_blocked"; SpeechOutcomeReason.STALE_SESSION_GENERATION -> "stale_session_generation"; SpeechOutcomeReason.SESSION_STATUS_SCHEDULED -> "session_status_scheduled"; SpeechOutcomeReason.PENDING_SESSION_STATUS_REPLACED -> "pending_session_status_replaced"; SpeechOutcomeReason.TTS_SUCCESS -> "tts_success"; SpeechOutcomeReason.TTS_FAILURE -> "tts_failure"; SpeechOutcomeReason.PLAYBACK_TIMEOUT -> "playback_timeout"; SpeechOutcomeReason.CANCELLED -> "cancelled" })) }
            TtsEventKind.entries.forEachIndexed { index, kind -> add(EnumCase(DiagnosticEvent.TtsProviderEvent(TtsProvider.entries[index % TtsProvider.entries.size], kind), "kind", when (kind) { TtsEventKind.ATTEMPTED -> "attempted"; TtsEventKind.SKIPPED_MISSING_CONFIG -> "skipped_missing_config"; TtsEventKind.INITIALIZATION_FAILED -> "initialization_failed"; TtsEventKind.COMPLETED -> "completed"; TtsEventKind.FAILED -> "failed"; TtsEventKind.LOCAL_FALLBACK -> "local_fallback"; TtsEventKind.PLAYBACK_TIMEOUT -> "playback_timeout"; TtsEventKind.CANCELLED -> "cancelled"; TtsEventKind.PREPARATION_TIMEOUT -> "preparation_timeout"; TtsEventKind.SKIPPED_OFFLINE -> "skipped_offline" })) }
            TtsProvider.entries.forEach { add(EnumCase(DiagnosticEvent.AudioFocusEvent(it, AudioFocusResult.GRANTED), "provider", when (it) { TtsProvider.ANDROID -> "android"; TtsProvider.FISH -> "fish"; TtsProvider.COMPOSITE -> "composite"; TtsProvider.BUNDLED -> "bundled" })) }
            AudioFocusResult.entries.forEach { add(EnumCase(DiagnosticEvent.AudioFocusEvent(TtsProvider.ANDROID, it), "result", when (it) { AudioFocusResult.GRANTED -> "granted"; AudioFocusResult.DENIED -> "denied"; AudioFocusResult.RELEASED -> "released" })) }
            DiagnosticBleLifecycle.entries.forEach { add(EnumCase(DiagnosticEvent.BleLifecycleChanged(it, it, 1, 1), "from", when (it) { DiagnosticBleLifecycle.STOPPED -> "stopped"; DiagnosticBleLifecycle.RUNNING -> "running"; DiagnosticBleLifecycle.PAUSED -> "paused" })) }
            DiagnosticBleLink.entries.forEach { add(EnumCase(DiagnosticEvent.BleLinkChanged(it, it, 1, null), "from", when (it) { DiagnosticBleLink.DISCONNECTED -> "disconnected"; DiagnosticBleLink.CONNECTING -> "connecting"; DiagnosticBleLink.PREPARED_PRIVATE_B3 -> "prepared_private_b3"; DiagnosticBleLink.READY_STANDARD_HR -> "ready_standard_hr"; DiagnosticBleLink.READY_PRIVATE_B3 -> "ready_private_b3" })) }
            BleReconnectStage.entries.forEach { add(EnumCase(DiagnosticEvent.BleReconnect(it, 1, 1), "stage", when (it) { BleReconnectStage.SCHEDULED -> "scheduled"; BleReconnectStage.STARTED -> "started"; BleReconnectStage.SUCCEEDED -> "succeeded"; BleReconnectStage.FAILED -> "failed" })) }
            BleWriteResult.entries.forEach { add(EnumCase(DiagnosticEvent.BleSyncWrite(BleSyncKind.UPDATE_WORKOUT, it, 1, 1), "result", when (it) { BleWriteResult.ACCEPTED -> "accepted"; BleWriteResult.REJECTED -> "rejected" })) }
            BleSyncKind.entries.forEach { add(EnumCase(DiagnosticEvent.BleSyncWrite(it, BleWriteResult.ACCEPTED, 1, 1), "kind", when (it) { BleSyncKind.UPDATE_WORKOUT -> "update_workout"; BleSyncKind.HEART_BEAT -> "heart_beat" })) }
            BleResumeDecision.entries.forEach { add(EnumCase(DiagnosticEvent.BleResumeHealth(it, null), "decision", when (it) { BleResumeDecision.HEALTHY -> "healthy"; BleResumeDecision.STALE -> "stale"; BleResumeDecision.STANDARD_HR_SKIPPED -> "standard_hr_skipped" })) }
            BleStaleEventKind.entries.forEach { add(EnumCase(DiagnosticEvent.BleStaleEventIgnored(it, 1, 1, null), "kind", when (it) { BleStaleEventKind.SCAN_RESULT -> "scan_result"; BleStaleEventKind.SCAN_TIMEOUT -> "scan_timeout"; BleStaleEventKind.RECONNECT -> "reconnect"; BleStaleEventKind.GATT_CALLBACK -> "gatt_callback"; BleStaleEventKind.CONTROL_ACTION -> "control_action"; BleStaleEventKind.SYNC_WRITE -> "sync_write" })) }
            BleOperation.entries.forEach { add(EnumCase(DiagnosticEvent.BleOperationFailed(it, BleFailureReason.GATT_ERROR, failure()), "operation", when (it) { BleOperation.START_SCAN -> "start_scan"; BleOperation.STOP_SCAN -> "stop_scan"; BleOperation.CONNECT_GATT -> "connect_gatt"; BleOperation.DISCONNECT_GATT -> "disconnect_gatt"; BleOperation.CLOSE_GATT -> "close_gatt"; BleOperation.DISCOVER_SERVICES -> "discover_services"; BleOperation.ENABLE_NOTIFICATIONS -> "enable_notifications"; BleOperation.READ_BATTERY -> "read_battery"; BleOperation.READ_FIRMWARE -> "read_firmware"; BleOperation.START_WRITE -> "start_write"; BleOperation.UPDATE_WRITE -> "update_write"; BleOperation.HEARTBEAT_WRITE -> "heartbeat_write" })) }
            BleFailureReason.entries.forEach { add(EnumCase(DiagnosticEvent.BleOperationFailed(BleOperation.START_SCAN, it, failure()), "reason", when (it) { BleFailureReason.PERMISSION_MISSING -> "permission_missing"; BleFailureReason.ADAPTER_DISABLED -> "adapter_disabled"; BleFailureReason.SCAN_FAILED -> "scan_failed"; BleFailureReason.SCAN_TIMEOUT -> "scan_timeout"; BleFailureReason.GATT_ERROR -> "gatt_error"; BleFailureReason.REMOTE_DISCONNECTED -> "remote_disconnected"; BleFailureReason.READY_TIMEOUT -> "ready_timeout"; BleFailureReason.START_WRITE_REJECTED -> "start_write_rejected"; BleFailureReason.SYNC_WRITE_REJECTED -> "sync_write_rejected" })) }
        }

        cases.forEach { logger.record(it.event) }
        logger.flush()

        assertEquals(cases.map { it.wireValue }, currentLog().readLines().map(::decode).zip(cases) { decoded, case -> decoded.payloadValue(case.field) })
    }

    @Test
    fun truncatesAndEscapesRuleIdsByUnicodeCodePoints() = runTest {
        val ruleId = "quote=\" slash=\\ newline=\n" + "😀".repeat(80)
        val logger = newLogger()

        logger.record(DiagnosticEvent.RuleTriggered(ruleId))
        logger.flush()

        val text = currentLog().readText()
        assertEquals(1, text.lineSequence().filter { it.isNotEmpty() }.count())
        val stored = decode(text).getValue("payload").jsonObject.getValue("ruleId").jsonPrimitive.content
        assertEquals("quote=\" slash=\\ newline=\n" + "😀".repeat(55), stored)
    }

    @Test
    fun preparationTimeoutAndOfflineSkipHaveDistinctWireNames() = runTest {
        val logger = newLogger()
        listOf("PREPARATION_TIMEOUT", "SKIPPED_OFFLINE").forEach {
            logger.record(DiagnosticEvent.TtsProviderEvent(TtsProvider.FISH, TtsEventKind.valueOf(it)))
        }
        logger.flush()
        assertEquals(listOf("preparation_timeout", "skipped_offline"), currentLog().readLines().map { decode(it).payloadValue("kind") })
    }

    @Test
    fun doesNotPersistThrowableSecrets() = runTest {
        val failure = DiagnosticFailure.from(
            IllegalStateException("Bearer secret-token"),
            SafeDiagnosticSummary.FISH_SYNTHESIS_FAILED,
        )
        val logger = newLogger()

        logger.record(DiagnosticEvent.TtsProviderEvent(TtsProvider.FISH, TtsEventKind.FAILED, failure))
        logger.flush()

        val text = currentLog().readText()
        assertFalse(text.contains("secret-token"))
        assertFalse(text.contains("Bearer"))
        assertTrue(text.contains("云端语音合成失败"))
    }

    @Test
    fun rotatesAtLimitAndKeepsOnlyThreeHistoryFiles() = runTest {
        val logger = newLogger(policy = DiagnosticLogPolicy(maxBytes = 1, historyFiles = 3))

        repeat(5) { index -> logger.record(DiagnosticEvent.RuleTriggered("event-$index")) }
        logger.flush()

        assertTrue(currentLog().exists())
        assertTrue(File(directory, "diagnostics.1.jsonl").exists())
        assertTrue(File(directory, "diagnostics.2.jsonl").exists())
        assertTrue(File(directory, "diagnostics.3.jsonl").exists())
        assertFalse(File(directory, "diagnostics.4.jsonl").exists())
    }

    @Test
    fun awaitIdleAndFlushWaitForPreviouslyQueuedEvents() = runTest {
        val logger = newLogger()

        logger.record(DiagnosticEvent.RuleTriggered("before-idle"))
        logger.awaitIdle()
        logger.record(DiagnosticEvent.RuleTriggered("before-flush"))
        logger.flush()

        assertEquals(listOf("before-idle", "before-flush"), currentLog().readLines().map { decode(it).payloadValue("ruleId") })
    }

    @Test
    fun recordSuppressesDirectoryIoFailures() = runTest {
        val directoryFile = temporaryFolder.newFile("not-a-directory")
        val logger = FileDiagnosticLogger(
            directoryFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            now = { Instant.parse("2026-09-05T00:00:00Z") },
            processStartId = "123e4567-e89b-12d3-a456-426614174000",
        )

        logger.record(DiagnosticEvent.RuleTriggered("safe"))
        logger.flush()
    }

    @Test
    fun recordSuppressesRotationRenameFailures() = runTest {
        val logger = FileDiagnosticLogger(
            directory = directory.apply(File::mkdirs),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            now = { Instant.parse("2026-09-05T00:00:00Z") },
            processStartId = "123e4567-e89b-12d3-a456-426614174000",
            policy = DiagnosticLogPolicy(maxBytes = 1, historyFiles = 3),
            renameFile = { _, _ -> false },
        )

        logger.record(DiagnosticEvent.RuleTriggered("first"))
        logger.record(DiagnosticEvent.RuleTriggered("second"))
        logger.flush()

        assertEquals(listOf("first"), currentLog().readLines().map { decode(it).payloadValue("ruleId") })
    }

    @Test
    fun defaultProcessIdentityIsUuidAndSharedAcrossLoggerRebuilds() = runTest {
        val firstDirectory = temporaryFolder.newFolder("first")
        val secondDirectory = temporaryFolder.newFolder("second")
        val first = FileDiagnosticLogger(firstDirectory, UnconfinedTestDispatcher(testScheduler))
        val second = FileDiagnosticLogger(secondDirectory, UnconfinedTestDispatcher(testScheduler))

        first.record(DiagnosticEvent.RuleTriggered("first"))
        second.record(DiagnosticEvent.RuleTriggered("second"))
        first.flush()
        second.flush()

        val firstId = decode(File(firstDirectory, "diagnostics.jsonl").readText()).getValue("processStartId").jsonPrimitive.content
        val secondId = decode(File(secondDirectory, "diagnostics.jsonl").readText()).getValue("processStartId").jsonPrimitive.content
        assertEquals(firstId, secondId)
        assertNotNull(UUID.fromString(firstId))
    }

    @Test
    fun fakeLoggerKeepsOrderedTraceAndAllowsFlushToBeGated() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeDiagnosticLogger(flushGate = gate)

        fake.record(DiagnosticEvent.RuleTriggered("first"))
        fake.awaitIdle()
        fake.record(DiagnosticEvent.RuleTriggered("second"))
        gate.complete(Unit)
        fake.flush()

        assertEquals(listOf("record", "awaitIdle", "record", "flush"), fake.trace)
        assertEquals(1, fake.awaitIdleCalls)
        assertEquals(1, fake.flushCalls)
        assertEquals(listOf("first", "second"), fake.events.map { (it as DiagnosticEvent.RuleTriggered).ruleId })
    }

    private val directory: File
        get() = File(temporaryFolder.root, "diagnostics")

    private fun newLogger(policy: DiagnosticLogPolicy = DiagnosticLogPolicy()) = FileDiagnosticLogger(
        directory = directory.apply(File::mkdirs),
        ioDispatcher = Dispatchers.Unconfined,
        now = { Instant.parse("2026-09-05T00:00:00Z") },
        processStartId = "123e4567-e89b-12d3-a456-426614174000",
        policy = policy,
    )

    private fun currentLog() = File(directory, "diagnostics.jsonl")

    private fun failure() = DiagnosticFailure.from(IllegalStateException(), SafeDiagnosticSummary.BLE_OPERATION_FAILED)

    private fun decode(line: String) = Json.parseToJsonElement(line).jsonObject

    private fun kotlinx.serialization.json.JsonObject.payloadValue(name: String) =
        getValue("payload").jsonObject.getValue(name).jsonPrimitive.content

    private data class Case(
        val event: DiagnosticEvent,
        val module: String,
        val eventName: String,
        val payloadKeys: Set<String>,
    )

    private data class EnumCase(
        val event: DiagnosticEvent,
        val field: String,
        val wireValue: String,
    )
}
