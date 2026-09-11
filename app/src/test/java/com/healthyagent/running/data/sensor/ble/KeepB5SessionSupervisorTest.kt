package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.data.sensor.WearableLinkFault
import com.healthyagent.running.data.sensor.WearableLinkPhase
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleStaleEventKind
import com.healthyagent.running.diagnostics.BleSyncKind as DiagnosticSyncKind
import com.healthyagent.running.diagnostics.BleWriteResult
import com.healthyagent.running.diagnostics.DiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5SessionSupervisorTest {
    @Test
    fun `预连接与初次握手失败没有运动故障资格`() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = preparedPrivate(supervisor)
        assertEquals(WearableLinkPhase.PREPARING, supervisor.linkObservation.phase)
        assertEquals(0L, supervisor.linkObservation.readyRevision)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 1_000)
        assertEquals(WearableLinkPhase.CONNECTING, supervisor.linkObservation.phase)
        supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, false), 2_000)
        assertNull(supervisor.linkObservation.fault)
        assertEquals(0L, supervisor.linkObservation.readyRevision)
    }

    @Test
    fun `标准心率预连接的内部Ready不冒充运动Ready`() {
        val supervisor = KeepB5SessionSupervisor()
        readyStandard(supervisor)
        assertEquals(WearableLinkPhase.PREPARING, supervisor.linkObservation.phase)
        assertEquals(0L, supervisor.linkObservation.readyRevision)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 2_000)
        assertEquals(WearableLinkPhase.READY, supervisor.linkObservation.phase)
        assertEquals(1L, supervisor.linkObservation.readyRevision)
        assertEquals(2_000L, supervisor.linkObservation.readySinceMillis)
    }

    @Test
    fun `真实失败保留故障起点和曾Ready证据而重试与旧回调不重置`() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = workoutReady(supervisor)
        val failed = supervisor.handle(KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.REMOTE_DISCONNECTED), 1_000)
        val fault = WearableLinkFault(1, 1, 1_000)
        assertEquals(fault, supervisor.linkObservation.fault)
        assertEquals(1L, supervisor.linkObservation.readyRevision)
        val retry = failed.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 2_000)
        assertEquals(WearableLinkPhase.RECOVERING, supervisor.linkObservation.phase)
        supervisor.handle(KeepB5SessionEvent.ScanFailed(retry, BleFailureReason.SCAN_TIMEOUT), 5_000)
        assertEquals(fault, supervisor.linkObservation.fault)
        supervisor.handle(KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.GATT_ERROR), 6_000)
        assertEquals(fault, supervisor.linkObservation.fault)
    }

    @Test
    fun `短暂Ready换连续故障身份但稳定十秒后才换通知episode`() {
        val supervisor = KeepB5SessionSupervisor()
        var guard = workoutReady(supervisor)
        var retry = supervisor.handle(KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.REMOTE_DISCONNECTED), 1_000)
            .filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 2_000)
        guard = readyPrivate(supervisor, retry, 2, activityAtMillis = 3_000)
        assertNull(supervisor.linkObservation.fault)
        retry = supervisor.handle(KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.REMOTE_DISCONNECTED), 12_999)
            .filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        assertEquals(WearableLinkFault(2, 1, 12_999), supervisor.linkObservation.fault)
        supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 13_000)
        guard = readyPrivate(supervisor, retry, 3, activityAtMillis = 14_000)
        supervisor.handle(KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.REMOTE_DISCONNECTED), 24_000)
        assertEquals(WearableLinkFault(3, 2, 24_000), supervisor.linkObservation.fault)
        assertEquals(3L, supervisor.linkObservation.readyRevision)
    }

    @Test
    fun `暂停不清观察而主动断开和新跑换代清资格`() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = workoutReady(supervisor)
        val generation = supervisor.linkObservation.generation
        supervisor.handle(KeepB5SessionEvent.RunPaused(1), 1_000)
        supervisor.handle(KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.REMOTE_DISCONNECTED), 2_000)
        assertEquals(generation, supervisor.linkObservation.generation)
        assertNotNull(supervisor.linkObservation.fault)
        supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 3_000)
        assertEquals(WearableLinkPhase.IDLE, supervisor.linkObservation.phase)
        assertNull(supervisor.linkObservation.fault)
        assertTrue(supervisor.linkObservation.generation > generation)
        supervisor.handle(KeepB5SessionEvent.RunStopped, 4_000)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0), 5_000)
        assertEquals(0L, supervisor.linkObservation.readyRevision)
        assertNull(supervisor.linkObservation.fault)
    }

    private fun workoutReady(supervisor: KeepB5SessionSupervisor): KeepB5GattGuard {
        val guard = preparedPrivate(supervisor)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0), 0)
        supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, true), 0)
        supervisor.handle(KeepB5SessionEvent.PrivateActivity(PrivateWorkoutActivity(guard, 0)), 0)
        return guard
    }

    @Test
    fun `孤立同步拒绝不重连且同类成功清零拒绝计数`() {
        KeepB5SyncKind.entries.forEach { kind ->
            val supervisor = KeepB5SessionSupervisor()
            val guard = readyPrivate(supervisor, activityAtMillis = 0L)
            repeat(5) {
                val rejected = completeSync(supervisor, guard, kind, accepted = false)
                assertTrue(rejected.withoutDiagnostics().isEmpty())
                val diagnostic = (rejected.single() as KeepB5SessionAction.RecordDiagnostic).event as DiagnosticEvent.BleSyncWrite
                assertEquals(BleWriteResult.REJECTED, diagnostic.result)
                assertNotNull(supervisor.captureSyncPollGuard(guard))
                assertTrue(completeSync(supervisor, guard, kind, accepted = true).withoutDiagnostics().isEmpty())
            }
        }
    }

    @Test
    fun `同类连续三次拒绝才重连且其他类成功不能掩盖失败`() {
        KeepB5SyncKind.entries.forEach { kind ->
            val supervisor = KeepB5SessionSupervisor()
            val guard = readyPrivate(supervisor, activityAtMillis = 0L)
            val other = KeepB5SyncKind.entries.single { it != kind }
            repeat(2) {
                assertTrue(completeSync(supervisor, guard, kind, false).withoutDiagnostics().isEmpty())
                completeSync(supervisor, guard, other, true)
            }
            val failed = completeSync(supervisor, guard, kind, false)
            val retry = failed.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
            assertFailureOrder(failed, guard, retry, BleFailureReason.SYNC_WRITE_REJECTED)
            assertNull(supervisor.captureSyncPollGuard(guard))
        }
    }

    @Test
    fun `拒绝计数不跨连接且旧连接迟到拒绝不会污染新连接`() {
        val supervisor = KeepB5SessionSupervisor()
        val old = readyPrivate(supervisor, activityAtMillis = 0L)
        repeat(2) { completeSync(supervisor, old, KeepB5SyncKind.HEART_BEAT, false) }
        val pending = (supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(old)),
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ) as KeepB5WriteResolution.Allowed).submission
        supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 1_000L)
        val replacement = readyPrivate(supervisor, activityAtMillis = 2_000L)
        assertOnlyTypedStale(
            supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(pending, false), 2_000L),
            BleStaleEventKind.SYNC_WRITE,
        )
        repeat(2) {
            assertTrue(completeSync(supervisor, replacement, KeepB5SyncKind.HEART_BEAT, false).withoutDiagnostics().isEmpty())
        }
        assertNotNull(supervisor.captureSyncPollGuard(replacement))
    }

    private fun completeSync(
        supervisor: KeepB5SessionSupervisor,
        guard: KeepB5GattGuard,
        kind: KeepB5SyncKind,
        accepted: Boolean,
    ): List<KeepB5SessionAction> {
        val write = supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(guard)),
            KeepB5SyncDue(kind, if (kind == KeepB5SyncKind.UPDATE_WORKOUT) 1L else 0L),
        ) as KeepB5WriteResolution.Allowed
        return supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(write.submission, accepted), 0L)
    }

    @Test
    fun `预连接不发送训练指令且开跑复用原连接仅启动一次`() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = preparedPrivate(supervisor)
        assertNull(supervisor.captureSyncPollGuard(guard))
        assertTrue(supervisor.handle(KeepB5SessionEvent.PrepareConnection, 1_000L).isEmpty())
        assertFalse(supervisor.handle(KeepB5SessionEvent.ReadyTimeout(guard), 30_000L)
            .any { it is KeepB5SessionAction.CloseGatt || it is KeepB5SessionAction.ScheduleReconnect })
        val started = supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 31_000L)
        assertEquals(listOf(KeepB5SessionAction.SendStart(guard)), started.filterIsInstance<KeepB5SessionAction.SendStart>())
        assertFalse(started.any { it is KeepB5SessionAction.BeginScan || it is KeepB5SessionAction.CloseGatt })
        assertTrue(started.contains(KeepB5SessionAction.ArmReadyTimeout(guard)))
        assertTrue(supervisor.handle(KeepB5SessionEvent.ExplicitConnect, 31_001L).isEmpty())
        supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, true), 31_002L)
        supervisor.handle(KeepB5SessionEvent.PrivateActivity(PrivateWorkoutActivity(guard, 32_000L)), 32_000L)
        assertNotNull(supervisor.captureSyncPollGuard(guard))
        supervisor.handle(KeepB5SessionEvent.RunPaused(2L), 33_000L)
        val resumed = supervisor.handle(KeepB5SessionEvent.RunResumed(2L), 34_000L)
        assertFalse(resumed.any { it is KeepB5SessionAction.SendStart || it is KeepB5SessionAction.CloseGatt })
    }

    @Test
    fun `预连接握手途中开跑在订阅成功后只发送一次开始`() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = supervisor.handle(KeepB5SessionEvent.PrepareConnection, 0L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard
        val guard = createGatt(supervisor, attempt)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        assertFalse(supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 1_000L)
            .any { it is KeepB5SessionAction.SendStart || it is KeepB5SessionAction.BeginScan })
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3), 1_001L)
        val enabled = supervisor.handle(KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3), 1_002L)
        assertEquals(listOf(KeepB5SessionAction.SendStart(guard)), enabled)
        assertFalse(supervisor.handle(KeepB5SessionEvent.PrepareConnection, 1_003L)
            .any { it is KeepB5SessionAction.SendStart })
    }

    @Test
    fun `预连接失败后手动重试取消旧重连且新连接仍不启动训练`() {
        val supervisor = KeepB5SessionSupervisor()
        val old = preparedPrivate(supervisor)
        val retry = supervisor.handle(KeepB5SessionEvent.LinkFailed(old, BleFailureReason.REMOTE_DISCONNECTED), 1_000L)
            .filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        val manual = supervisor.handle(KeepB5SessionEvent.PrepareConnection, 1_100L)
        val attempt = manual.filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard
        assertTrue(manual.contains(KeepB5SessionAction.CancelReconnect(retry.epoch, retry.connectionAttemptToken)))
        assertFalse(supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 2_000L)
            .any { it is KeepB5SessionAction.BeginScan })
        val next = preparedPrivate(supervisor, attempt, old.gattToken + 1)
        assertNull(supervisor.captureSyncPollGuard(next))
        assertTrue(supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 3_000L)
            .contains(KeepB5SessionAction.CloseGatt(next, disconnectFirst = true)))
    }

    private fun preparedPrivate(
        supervisor: KeepB5SessionSupervisor,
        attempt: KeepB5AttemptGuard = supervisor.handle(KeepB5SessionEvent.PrepareConnection, 0L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard,
        gattToken: Long = 1L,
    ): KeepB5GattGuard {
        val guard = createGatt(supervisor, attempt, gattToken)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        val enabled = supervisor.handle(KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        assertTrue(enabled.contains(KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED)))
        assertFalse(enabled.any { it is KeepB5SessionAction.SendStart || it is KeepB5SessionAction.StartSync })
        return guard
    }

    @Test
    fun `手动断开后结束仍拒绝迟到指标且重连不再累计时间`() {
        val supervisor = KeepB5SessionSupervisor()
        readyPrivate(supervisor, activityAtMillis = 1_000L)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 1_000L)
        supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 2_000L)
        supervisor.handle(KeepB5SessionEvent.RunStopped, 3_000L)
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(9_999, 200)), 3_100L)
        val next = readyPrivate(supervisor, activityAtMillis = 4_000L)
        val update = supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(next)),
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 10L),
        ) as KeepB5WriteResolution.Allowed
        assertEquals(KeepB5DisplayMetrics(), update.displayMetrics)
        assertEquals(0L, update.elapsedSeconds)
    }

    @Test
    fun `提交读取最新指标且暂停后旧批次不能带入指标`() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyPrivate(supervisor, activityAtMillis = 1_000L)
        fun resolve(): KeepB5WriteResolution.Allowed = supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(guard)),
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT),
        ) as KeepB5WriteResolution.Allowed
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(9_999, 200)), 1_000L)
        assertEquals(KeepB5DisplayMetrics(), resolve().displayMetrics)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 1_000L)
        val oldPoll = requireNotNull(supervisor.captureSyncPollGuard(guard))
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(153, 816)), 1_100L)
        assertEquals(KeepB5DisplayMetrics(153, 816), resolve().displayMetrics)
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(186, 0)), 1_200L)
        assertEquals(KeepB5DisplayMetrics(186, 0), resolve().displayMetrics)

        supervisor.handle(KeepB5SessionEvent.RunPaused(2L), 2_000L)
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(9_999, 200)), 2_100L)
        assertEquals(KeepB5DisplayMetrics(186, 0), resolve().displayMetrics)
        assertTrue(supervisor.resolveSyncWrite(oldPoll, KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT))
            is KeepB5WriteResolution.Rejected)
        supervisor.handle(KeepB5SessionEvent.RunResumed(2L), 3_000L)
        assertEquals(KeepB5DisplayMetrics(186, 0), resolve().displayMetrics)
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(200, 359)), 3_100L)
        assertEquals(KeepB5DisplayMetrics(200, 359), resolve().displayMetrics)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 4_000L)
        assertEquals(KeepB5DisplayMetrics(), resolve().displayMetrics)
    }

    @Test
    fun `暂停重连保留累计距离但结束后重新连接不泄漏旧指标`() {
        val supervisor = KeepB5SessionSupervisor()
        val old = readyPrivate(supervisor, activityAtMillis = 1_000L)
        supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 1_000L)
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(1_250, 360)), 1_100L)
        supervisor.handle(KeepB5SessionEvent.RunPaused(42L), 2_000L)
        val retry = supervisor.handle(
            KeepB5SessionEvent.LinkFailed(old, BleFailureReason.REMOTE_DISCONNECTED), 3_000L,
        ).filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        val scan = supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 4_000L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard
        val replacement = readyPrivate(supervisor, scan, gattToken = old.gattToken + 1, activityAtMillis = 5_000L)
        val update = supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(replacement)),
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 10L),
        ) as KeepB5WriteResolution.Allowed
        assertEquals(KeepB5DisplayMetrics(1_250, 0), update.displayMetrics)
        assertEquals(42L, update.elapsedSeconds)

        supervisor.handle(KeepB5SessionEvent.RunStopped, 6_000L)
        supervisor.handle(KeepB5SessionEvent.MetricsUpdated(KeepB5DisplayMetrics(9_999, 200)), 6_100L)
        val next = readyPrivate(supervisor, activityAtMillis = 7_000L)
        val nextUpdate = supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(next)),
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT),
        ) as KeepB5WriteResolution.Allowed
        assertEquals(KeepB5DisplayMetrics(), nextUpdate.displayMetrics)
    }

    @Test
    fun preRunPrivateSessionFreezesUpdatesAtZeroAndKeepsHeartbeat() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyPrivate(supervisor, activityAtMillis = 1_000L)
        val pollGuard = requireNotNull(supervisor.captureSyncPollGuard(guard))

        val update = supervisor.resolveSyncWrite(
            pollGuard,
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, elapsedIntervals = 18L),
        ) as KeepB5WriteResolution.Allowed
        val heartbeat = supervisor.resolveSyncWrite(
            pollGuard,
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ) as KeepB5WriteResolution.Allowed

        assertEquals(0L, update.elapsedSeconds)
        assertNull(heartbeat.elapsedSeconds)
        assertTrue(heartbeat.submission.operationId > update.submission.operationId)
    }

    @Test
    fun stoppedCurrentPublicationsRemainAllowedUntilConnectionIntentEnds() {
        KeepB5ChannelKind.entries.forEach { channel ->
            val supervisor = KeepB5SessionSupervisor()
            val guard = if (channel == KeepB5ChannelKind.PRIVATE_B3) {
                readyPrivate(supervisor, activityAtMillis = 1_000L)
            } else {
                readyStandard(supervisor)
            }
            val tokenized = TokenizedSensorSample(
                guard,
                SensorSample(SampleType.HEART_RATE, 132.0, 1_000L, "keep_b5"),
                1_000L,
            )
            val sampleAction = supervisor.handle(
                KeepB5SessionEvent.SensorSampleReceived(tokenized),
                1_000L,
            ).single() as KeepB5SessionAction.PublishSample
            val deviceAction = supervisor.handle(
                KeepB5SessionEvent.DeviceInfoReceived(guard, DeviceInfo("Keep B5", 80, "1.0")),
                1_000L,
            ).single() as KeepB5SessionAction.PublishDeviceInfo

            assertEquals(tokenized, sampleAction.sample)
            assertEquals(KeepB5ActionResolution.Allowed, supervisor.resolveGuardedAction(sampleAction))
            assertEquals(KeepB5ActionResolution.Allowed, supervisor.resolveGuardedAction(deviceAction))

            supervisor.handle(KeepB5SessionEvent.RunStopped, 2_000L)
            assertGattCallbackStale(supervisor.resolveGuardedAction(sampleAction))
            assertGattCallbackStale(supervisor.resolveGuardedAction(deviceAction))
        }
    }

    @Test
    fun scanMatchStopsItsScanBeforeConnectingAndStaleAttemptCannotAffectRetry() {
        val supervisor = KeepB5SessionSupervisor()
        val first = begin(supervisor)
        assertEquals(
            listOf(
                KeepB5SessionAction.StopScan(first, preserveMatchedTarget = true),
                KeepB5SessionAction.ConnectRemembered(first),
            ),
            supervisor.handle(KeepB5SessionEvent.ScanMatched(first), 0L).withoutDiagnostics(),
        )

        val another = KeepB5SessionSupervisor()
        val old = begin(another)
        val failed = another.handle(
            KeepB5SessionEvent.ScanFailed(old, BleFailureReason.SCAN_TIMEOUT),
            15_000L,
        ).withoutDiagnostics()
        assertEquals(KeepB5SessionAction.StopScan(old), failed.first())
        val retry = failed.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        another.handle(KeepB5SessionEvent.ReconnectDue(retry), 16_000L)

        val stale = another.handle(KeepB5SessionEvent.ScanMatched(old), 16_000L)
        assertTrue(stale.single() is KeepB5SessionAction.RecordDiagnostic)
        assertFalse(stale.any { it is KeepB5SessionAction.StopScan || it is KeepB5SessionAction.ConnectRemembered })
    }

    @Test
    fun staleScanFailuresKeepTheirTypedReasonWithoutStoppingOrRescheduling() {
        val supervisor = KeepB5SessionSupervisor()
        val old = begin(supervisor)
        val firstFailure = supervisor.handle(
            KeepB5SessionEvent.ScanFailed(old, BleFailureReason.SCAN_FAILED),
            1_000L,
        )
        val retry = firstFailure.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 2_000L)

        listOf(
            BleFailureReason.SCAN_FAILED to BleStaleEventKind.SCAN_RESULT,
            BleFailureReason.SCAN_TIMEOUT to BleStaleEventKind.SCAN_TIMEOUT,
        ).forEach { (reason, expectedKind) ->
            val actions = supervisor.handle(KeepB5SessionEvent.ScanFailed(old, reason), 3_000L)
            val diagnostic = (actions.single() as KeepB5SessionAction.RecordDiagnostic).event as
                DiagnosticEvent.BleStaleEventIgnored
            assertEquals(expectedKind, diagnostic.kind)
            assertEquals(old.epoch, diagnostic.epoch)
            assertEquals(old.connectionAttemptToken, diagnostic.attemptToken)
            assertNull(diagnostic.gattToken)
            assertFalse(actions.any { it is KeepB5SessionAction.StopScan })
            assertFalse(actions.any { it is KeepB5SessionAction.ScheduleReconnect })
        }
    }

    @Test
    fun acceptedGattCreationActivatesFirstWhileStaleCreationOnlyClosesExactGatt() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        supervisor.handle(KeepB5SessionEvent.ScanMatched(attempt), 0L)
        val current = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, 7L)

        val accepted = supervisor.handle(KeepB5SessionEvent.GattCreated(current), 0L)
        assertEquals(KeepB5SessionAction.ActivateGattData(current), accepted.first())
        assertTrue(accepted.contains(KeepB5SessionAction.ArmReadyTimeout(current)))

        val stale = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken - 1L, 8L)
        val rejected = supervisor.handle(KeepB5SessionEvent.GattCreated(stale), 0L)
        assertFalse(rejected.any { it is KeepB5SessionAction.ActivateGattData })
        assertTrue(rejected.contains(KeepB5SessionAction.CloseGatt(stale, disconnectFirst = true)))
    }

    @Test
    fun duplicateCreationForCurrentGattDoesNotCloseTheLiveBinding() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        supervisor.handle(KeepB5SessionEvent.ScanMatched(attempt), 0L)
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, 7L)
        supervisor.handle(KeepB5SessionEvent.GattCreated(guard), 0L)

        val duplicate = supervisor.handle(KeepB5SessionEvent.GattCreated(guard), 0L)

        assertTrue(duplicate.single() is KeepB5SessionAction.RecordDiagnostic)
        assertFalse(duplicate.any { it is KeepB5SessionAction.CloseGatt })
        assertEquals(
            KeepB5ActionResolution.Allowed,
            supervisor.resolveGuardedAction(KeepB5SessionAction.ArmReadyTimeout(guard)),
        )
    }

    @Test
    fun runningAdvancesElapsedPausedFreezesAndLifecycleRevisionRejectsOldDueBatch() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyPrivate(supervisor, activityAtMillis = 1_000L)
        val startActions = supervisor.handle(KeepB5SessionEvent.RunStarted(10L), 1_000L)
        assertTrue(startActions.contains(KeepB5SessionAction.ResetSyncUpdateAccounting(guard)))
        val runningGuard = requireNotNull(supervisor.captureSyncPollGuard(guard))
        assertEquals(
            14L,
            (supervisor.resolveSyncWrite(
                runningGuard,
                KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 4L),
            ) as KeepB5WriteResolution.Allowed).elapsedSeconds,
        )

        val pauseActions = supervisor.handle(KeepB5SessionEvent.RunPaused(42L), 2_000L)
        assertTrue(pauseActions.contains(KeepB5SessionAction.ResetSyncUpdateAccounting(guard)))
        val staleDue = supervisor.handle(
            KeepB5SessionEvent.SyncDue(
                runningGuard,
                listOf(
                    KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 18L),
                    KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
                ),
            ),
            2_000L,
        )
        assertEquals(1, staleDue.size)
        assertTrue((staleDue.single() as KeepB5SessionAction.RecordDiagnostic).event is DiagnosticEvent.BleStaleWriteIgnored)

        val pausedGuard = requireNotNull(supervisor.captureSyncPollGuard(guard))
        assertEquals(
            42L,
            (supervisor.resolveSyncWrite(
                pausedGuard,
                KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 18L),
            ) as KeepB5WriteResolution.Allowed).elapsedSeconds,
        )
    }

    @Test
    fun pausedDisconnectSchedulesReconnectAndNewPrivateGattKeepsFrozenElapsed() {
        val supervisor = KeepB5SessionSupervisor()
        val old = readyPrivate(supervisor, activityAtMillis = 1_000L)
        supervisor.handle(KeepB5SessionEvent.RunStarted(30L), 1_000L)
        supervisor.handle(KeepB5SessionEvent.RunPaused(42L), 2_000L)

        val failed = supervisor.handle(
            KeepB5SessionEvent.LinkFailed(old, BleFailureReason.REMOTE_DISCONNECTED),
            62_000L,
        )
        val retry = failed.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        assertFailureOrder(failed, old, retry, BleFailureReason.REMOTE_DISCONNECTED)

        val scan = supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 63_000L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard
        val replacement = readyPrivate(supervisor, scan, gattToken = old.gattToken + 1L, activityAtMillis = 64_000L)
        val pollGuard = requireNotNull(supervisor.captureSyncPollGuard(replacement))
        assertEquals(
            42L,
            (supervisor.resolveSyncWrite(
                pollGuard,
                KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 10L),
            ) as KeepB5WriteResolution.Allowed).elapsedSeconds,
        )
    }

    @Test
    fun privateResumeUsesCallbackArrivalTimeAndClosesStaleGattButStandardSkipsHealthCheck() {
        val healthy = KeepB5SessionSupervisor()
        val healthyGuard = readyPrivate(healthy, activityAtMillis = 1_000L)
        healthy.handle(KeepB5SessionEvent.RunPaused(20L), 1_000L)
        val healthyActions = healthy.handle(KeepB5SessionEvent.RunResumed(20L), 13_000L)
        assertFalse(healthyActions.any { it is KeepB5SessionAction.CloseGatt })
        assertNotNull(healthy.captureSyncPollGuard(healthyGuard))

        val stale = KeepB5SessionSupervisor()
        val staleGuard = readyPrivate(stale, activityAtMillis = 1_000L, consumedAtMillis = 14_000L)
        stale.handle(KeepB5SessionEvent.RunPaused(20L), 14_000L)
        val staleActions = stale.handle(KeepB5SessionEvent.RunResumed(20L), 14_001L)
        assertTrue(staleActions.contains(KeepB5SessionAction.CloseGatt(staleGuard, disconnectFirst = true)))
        assertNull(stale.captureSyncPollGuard(staleGuard))

        val standard = KeepB5SessionSupervisor()
        val standardGuard = readyStandard(standard)
        standard.handle(KeepB5SessionEvent.RunPaused(20L), 0L)
        val standardActions = standard.handle(KeepB5SessionEvent.RunResumed(20L), 60_000L)
        assertFalse(standardActions.any { it is KeepB5SessionAction.CloseGatt })
        val diagnostic = standardActions.filterIsInstance<KeepB5SessionAction.RecordDiagnostic>()
            .map { it.event }
            .filterIsInstance<DiagnosticEvent.BleResumeHealth>()
            .single()
        assertNull(diagnostic.activityAgeMillis)
        assertTrue(standard.resolveGuardedAction(KeepB5SessionAction.PublishDeviceInfo(standardGuard, DeviceInfo("B5"))) is KeepB5ActionResolution.Allowed)
    }

    @Test
    fun standardChannelNeverStartsPrivateWritesAndDuplicateHandshakeEventsAreIdempotent() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        val guard = createGatt(supervisor, attempt)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.STANDARD_HR), 0L)
        val first = supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.STANDARD_HR),
            0L,
        )
        val duplicate = supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.STANDARD_HR),
            0L,
        )
        val lifecycle = supervisor.handle(KeepB5SessionEvent.RunStarted(0L), 0L) +
            supervisor.handle(KeepB5SessionEvent.RunPaused(0L), 0L) +
            supervisor.handle(KeepB5SessionEvent.RunResumed(0L), 0L)

        assertTrue(first.contains(KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED)))
        assertNoPrivateWriteActions(first + duplicate + lifecycle)
        assertNull(supervisor.captureSyncPollGuard(guard))
    }

    @Test
    fun standardToPrivateFallbackCrossesOnceWithoutAllowingLateStandardResult() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        val guard = createGatt(supervisor, attempt)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.STANDARD_HR), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)

        val privateEnabled = supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3),
            0L,
        )
        val lateStandard = supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.STANDARD_HR),
            0L,
        )
        val duplicatePrivate = supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3),
            0L,
        )

        assertEquals(listOf(KeepB5SessionAction.SendStart(guard)), privateEnabled.withoutDiagnostics())
        assertTrue(lateStandard.single() is KeepB5SessionAction.RecordDiagnostic)
        assertTrue(duplicatePrivate.single() is KeepB5SessionAction.RecordDiagnostic)
    }

    @Test
    fun startCompletionSurvivesLifecycleChangesButOldPollAndQueuedWriteDoNot() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        val guard = createGatt(supervisor, attempt)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        supervisor.handle(KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        supervisor.handle(KeepB5SessionEvent.RunStarted(10L), 0L)
        supervisor.handle(KeepB5SessionEvent.RunPaused(11L), 0L)

        val completed = supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, accepted = true), 0L)
        assertTrue(completed.contains(KeepB5SessionAction.StartSync(guard)))
        val polled = requireNotNull(supervisor.captureSyncPollGuard(guard))
        val queued = KeepB5SessionAction.SendHeartbeat(polled)
        assertEquals(KeepB5ActionResolution.Allowed, supervisor.resolveGuardedAction(queued))

        supervisor.handle(KeepB5SessionEvent.RunResumed(11L), 0L)
        assertTrue(supervisor.resolveSyncWrite(polled, KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)) is KeepB5WriteResolution.Rejected)
        assertTrue(supervisor.resolveGuardedAction(queued) is KeepB5ActionResolution.Rejected)
    }

    @Test
    fun guardedControlActionIsAllowedOnlyWhileItsExactStateStillCurrent() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        val action = KeepB5SessionAction.BeginScan(attempt)
        assertEquals(KeepB5ActionResolution.Allowed, supervisor.resolveGuardedAction(action))

        supervisor.handle(KeepB5SessionEvent.RunStopped, 0L)
        val rejected = supervisor.resolveGuardedAction(action) as KeepB5ActionResolution.Rejected
        assertEquals(BleStaleEventKind.CONTROL_ACTION, (rejected.diagnostic as DiagnosticEvent.BleStaleEventIgnored).kind)
    }

    @Test
    fun startRejectReadyTimeoutAndPersistentSyncRejectShareExactFailureOrder() {
        val startSupervisor = KeepB5SessionSupervisor()
        val startGuard = privateStartPending(startSupervisor)
        val startActions = startSupervisor.handle(
            KeepB5SessionEvent.StartWriteCompleted(startGuard, accepted = false),
            0L,
        )
        val startRetry = startActions.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        assertFailureOrder(startActions, startGuard, startRetry, BleFailureReason.START_WRITE_REJECTED)

        val timeoutSupervisor = KeepB5SessionSupervisor()
        val timeoutGuard = privateStartPending(timeoutSupervisor)
        val timeoutActions = timeoutSupervisor.handle(KeepB5SessionEvent.ReadyTimeout(timeoutGuard), 20_000L)
        val timeoutRetry = timeoutActions.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        assertFailureOrder(timeoutActions, timeoutGuard, timeoutRetry, BleFailureReason.READY_TIMEOUT)

        val syncSupervisor = KeepB5SessionSupervisor()
        val syncGuard = readyPrivate(syncSupervisor, activityAtMillis = 0L)
        repeat(2) {
            assertTrue(completeSync(syncSupervisor, syncGuard, KeepB5SyncKind.UPDATE_WORKOUT, false)
                .withoutDiagnostics().isEmpty())
        }
        val write = syncSupervisor.resolveSyncWrite(
            requireNotNull(syncSupervisor.captureSyncPollGuard(syncGuard)),
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L),
        ) as KeepB5WriteResolution.Allowed
        val syncActions = syncSupervisor.handle(
            KeepB5SessionEvent.SyncWriteCompleted(write.submission, accepted = false),
            0L,
        )
        val syncRetry = syncActions.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        assertFailureOrder(syncActions, syncGuard, syncRetry, BleFailureReason.SYNC_WRITE_REJECTED)
    }

    @Test
    fun currentConnectFailureRetriesWithoutGattAndOldFailureIsStale() {
        val supervisor = KeepB5SessionSupervisor()
        val current = begin(supervisor)
        supervisor.handle(KeepB5SessionEvent.ScanMatched(current), 0L)
        val failed = supervisor.handle(
            KeepB5SessionEvent.ConnectFailed(current, BleFailureReason.GATT_ERROR),
            0L,
        )
        assertFalse(failed.any { it is KeepB5SessionAction.CloseGatt })
        assertTrue(failed.any { it is KeepB5SessionAction.ScheduleReconnect })

        val duplicate = supervisor.handle(
            KeepB5SessionEvent.ConnectFailed(current, BleFailureReason.GATT_ERROR),
            0L,
        )
        assertTrue(duplicate.single() is KeepB5SessionAction.RecordDiagnostic)
    }

    @Test
    fun stagesOnlyAdvanceAndEarlierOrDuplicateCallbacksCannotRepeatActions() {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        supervisor.handle(KeepB5SessionEvent.ScanMatched(attempt), 0L)
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, 1L)
        supervisor.handle(KeepB5SessionEvent.GattCreated(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        supervisor.handle(KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, accepted = true), 0L)

        val staleEvents = listOf(
            KeepB5SessionEvent.GattConnected(guard),
            KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3),
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3),
            KeepB5SessionEvent.StartWriteCompleted(guard, accepted = true),
        )
        staleEvents.forEach { event ->
            val actions = supervisor.handle(event, 0L)
            assertTrue(actions.single() is KeepB5SessionAction.RecordDiagnostic)
            assertNoPrivateWriteActions(actions)
        }
    }

    @Test
    fun everyConnectionPhaseRejectsEarlierOrDuplicateCallbackWithoutRollback() {
        val scanning = KeepB5SessionSupervisor()
        val scanningAttempt = begin(scanning)
        assertOnlyGattStale(
            scanning.handle(
                KeepB5SessionEvent.GattConnected(
                    KeepB5GattGuard(scanningAttempt.epoch, scanningAttempt.connectionAttemptToken, 1L),
                ),
                0L,
            ),
        )
        assertEquals(
            listOf(
                KeepB5SessionAction.StopScan(scanningAttempt, preserveMatchedTarget = true),
                KeepB5SessionAction.ConnectRemembered(scanningAttempt),
            ),
            scanning.handle(KeepB5SessionEvent.ScanMatched(scanningAttempt), 0L),
        )

        val connecting = KeepB5SessionSupervisor()
        val connectingAttempt = begin(connecting)
        connecting.handle(KeepB5SessionEvent.ScanMatched(connectingAttempt), 0L)
        assertOnlyTypedStale(
            connecting.handle(KeepB5SessionEvent.ScanMatched(connectingAttempt), 0L),
            BleStaleEventKind.SCAN_RESULT,
        )
        val connectingGuard = KeepB5GattGuard(connectingAttempt.epoch, connectingAttempt.connectionAttemptToken, 1L)
        assertEquals(
            KeepB5SessionAction.ActivateGattData(connectingGuard),
            connecting.handle(KeepB5SessionEvent.GattCreated(connectingGuard), 0L).first(),
        )

        val discovering = KeepB5SessionSupervisor()
        val discoveringAttempt = begin(discovering)
        val discoveringGuard = createGatt(discovering, discoveringAttempt)
        discovering.handle(KeepB5SessionEvent.GattConnected(discoveringGuard), 0L)
        assertOnlyGattStale(discovering.handle(KeepB5SessionEvent.GattConnected(discoveringGuard), 0L))
        assertTrue(
            discovering.handle(
                KeepB5SessionEvent.ServicesDiscovered(discoveringGuard, KeepB5ChannelKind.PRIVATE_B3),
                0L,
            ).isEmpty(),
        )

        val enabling = KeepB5SessionSupervisor()
        val enablingAttempt = begin(enabling)
        val enablingGuard = createGatt(enabling, enablingAttempt)
        enabling.handle(KeepB5SessionEvent.GattConnected(enablingGuard), 0L)
        enabling.handle(
            KeepB5SessionEvent.ServicesDiscovered(enablingGuard, KeepB5ChannelKind.PRIVATE_B3),
            0L,
        )
        assertOnlyGattStale(
            enabling.handle(
                KeepB5SessionEvent.ServicesDiscovered(enablingGuard, KeepB5ChannelKind.PRIVATE_B3),
                0L,
            ),
        )
        assertEquals(
            listOf(KeepB5SessionAction.SendStart(enablingGuard)),
            enabling.handle(
                KeepB5SessionEvent.NotificationsEnabled(enablingGuard, KeepB5ChannelKind.PRIVATE_B3),
                0L,
            ),
        )

        val startPending = KeepB5SessionSupervisor()
        val startPendingGuard = privateStartPending(startPending)
        assertOnlyGattStale(
            startPending.handle(
                KeepB5SessionEvent.NotificationsEnabled(startPendingGuard, KeepB5ChannelKind.PRIVATE_B3),
                0L,
            ),
        )
        assertEquals(
            listOf(KeepB5SessionAction.StartSync(startPendingGuard)),
            startPending.handle(KeepB5SessionEvent.StartWriteCompleted(startPendingGuard, true), 0L),
        )

        val awaiting = KeepB5SessionSupervisor()
        val awaitingGuard = privateStartPending(awaiting)
        awaiting.handle(KeepB5SessionEvent.StartWriteCompleted(awaitingGuard, true), 0L)
        assertOnlyGattStale(
            awaiting.handle(KeepB5SessionEvent.StartWriteCompleted(awaitingGuard, true), 0L),
        )
        val ready = awaiting.handle(
            KeepB5SessionEvent.PrivateActivity(PrivateWorkoutActivity(awaitingGuard, 1_000L)),
            1_000L,
        )
        assertTrue(ready.contains(KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED)))
    }

    @Test
    fun everyGuardedActionIsAllowedInItsStateAndRejectedAfterStop() {
        val cases = listOf(
            guardedBeginScanCase(),
            guardedConnectCase(),
            guardedArmTimeoutCase(),
            guardedSendStartCase(),
            guardedStartSyncCase(),
            guardedReconnectCase(),
            guardedPublicationCase(sample = true),
            guardedPublicationCase(sample = false),
        )

        cases.forEach { case ->
            assertEquals(case.name, KeepB5ActionResolution.Allowed, case.supervisor.resolveGuardedAction(case.action))
            case.supervisor.handle(KeepB5SessionEvent.RunStopped, 0L)
            val rejected = case.supervisor.resolveGuardedAction(case.action) as KeepB5ActionResolution.Rejected
            val diagnostic = rejected.diagnostic as DiagnosticEvent.BleStaleEventIgnored
            assertEquals(case.name, case.expectedStaleKind, diagnostic.kind)
        }
    }

    @Test
    fun syncWriteRejectsEachStaleIdentityDimensionAndLifecycleRevisionSeparately() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyPrivate(supervisor, activityAtMillis = 0L)
        val current = requireNotNull(supervisor.captureSyncPollGuard(guard))
        listOf(
            current.copy(epoch = current.epoch + 1L),
            current.copy(connectionAttemptToken = current.connectionAttemptToken + 1L),
            current.copy(gattToken = current.gattToken + 1L),
        ).forEach { staleGuard ->
            val rejected = supervisor.resolveSyncWrite(
                staleGuard,
                KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
            ) as KeepB5WriteResolution.Rejected
            val diagnostic = rejected.diagnostic as DiagnosticEvent.BleStaleEventIgnored
            assertEquals(BleStaleEventKind.SYNC_WRITE, diagnostic.kind)
            assertEquals(staleGuard.epoch, diagnostic.epoch)
            assertEquals(staleGuard.connectionAttemptToken, diagnostic.attemptToken)
            assertEquals(staleGuard.gattToken, diagnostic.gattToken)
        }

        val staleRevision = current.copy(lifecycleRevision = current.lifecycleRevision + 1L)
        val revisionRejected = supervisor.resolveSyncWrite(
            staleRevision,
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L),
        ) as KeepB5WriteResolution.Rejected
        val revisionDiagnostic = revisionRejected.diagnostic as DiagnosticEvent.BleStaleWriteIgnored
        assertEquals(DiagnosticSyncKind.UPDATE_WORKOUT, revisionDiagnostic.kind)
        assertEquals(staleRevision.lifecycleRevision, revisionDiagnostic.lifecycleRevision)
    }

    @Test
    fun replacedGattRejectsOldCallbackEventsAndAlreadyQueuedPublications() {
        val supervisor = KeepB5SessionSupervisor()
        val oldGuard = readyStandard(supervisor)
        val oldSample = TokenizedSensorSample(
            oldGuard,
            SensorSample(SampleType.HEART_RATE, 120.0, 1_000L, "keep_b5"),
            1_000L,
        )
        val queuedSample = supervisor.handle(
            KeepB5SessionEvent.SensorSampleReceived(oldSample),
            1_000L,
        ).single() as KeepB5SessionAction.PublishSample
        val queuedInfo = supervisor.handle(
            KeepB5SessionEvent.DeviceInfoReceived(oldGuard, DeviceInfo("A", 50, "a")),
            1_000L,
        ).single() as KeepB5SessionAction.PublishDeviceInfo

        val failed = supervisor.handle(
            KeepB5SessionEvent.LinkFailed(oldGuard, BleFailureReason.REMOTE_DISCONNECTED),
            2_000L,
        )
        val retry = failed.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        val scan = supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 3_000L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard
        val newGuard = readyStandard(supervisor, scan, oldGuard.gattToken + 1L)

        assertOnlyGattStale(
            supervisor.handle(KeepB5SessionEvent.SensorSampleReceived(oldSample), 4_000L),
        )
        assertOnlyGattStale(
            supervisor.handle(
                KeepB5SessionEvent.DeviceInfoReceived(oldGuard, DeviceInfo("A", 49, "old")),
                4_000L,
            ),
        )
        assertGattCallbackStale(supervisor.resolveGuardedAction(queuedSample))
        assertGattCallbackStale(supervisor.resolveGuardedAction(queuedInfo))
        assertTrue(
            supervisor.resolveGuardedAction(
                KeepB5SessionAction.PublishDeviceInfo(newGuard, DeviceInfo("B")),
            ) is KeepB5ActionResolution.Allowed,
        )
    }

    @Test
    fun completionClaimsBothKindsOnceAndRejectsUnknownRepeatedAndOldGattOperations() {
        val supervisor = KeepB5SessionSupervisor()
        val oldGuard = readyPrivate(supervisor, activityAtMillis = 0L)
        val poll = requireNotNull(supervisor.captureSyncPollGuard(oldGuard))
        val update = (supervisor.resolveSyncWrite(
            poll,
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L),
        ) as KeepB5WriteResolution.Allowed).submission
        val heartbeat = (supervisor.resolveSyncWrite(
            poll,
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ) as KeepB5WriteResolution.Allowed).submission

        val unknown = update.copy(operationId = heartbeat.operationId + 100L)
        assertOnlyTypedStale(
            supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(unknown, true), 0L),
            BleStaleEventKind.SYNC_WRITE,
        )
        listOf(update, heartbeat).forEach { submission ->
            val accepted = supervisor.handle(
                KeepB5SessionEvent.SyncWriteCompleted(submission, true),
                0L,
            )
            val diagnostic = (accepted.single() as KeepB5SessionAction.RecordDiagnostic).event as
                DiagnosticEvent.BleSyncWrite
            assertEquals(submission.kind.toDiagnosticKind(), diagnostic.kind)
            assertEquals(BleWriteResult.ACCEPTED, diagnostic.result)
            assertOnlyTypedStale(
                supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(submission, true), 0L),
                BleStaleEventKind.SYNC_WRITE,
            )
        }

        val pendingOld = (supervisor.resolveSyncWrite(
            requireNotNull(supervisor.captureSyncPollGuard(oldGuard)),
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ) as KeepB5WriteResolution.Allowed).submission
        val failed = supervisor.handle(
            KeepB5SessionEvent.LinkFailed(oldGuard, BleFailureReason.REMOTE_DISCONNECTED),
            1_000L,
        )
        val retry = failed.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        val scan = supervisor.handle(KeepB5SessionEvent.ReconnectDue(retry), 2_000L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single().guard
        val replacement = readyPrivate(
            supervisor,
            scan,
            oldGuard.gattToken + 1L,
            activityAtMillis = 3_000L,
        )
        assertOnlyTypedStale(
            supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(pendingOld, false), 3_000L),
            BleStaleEventKind.SYNC_WRITE,
        )
        assertNotNull(supervisor.captureSyncPollGuard(replacement))
    }

    @Test
    fun bothTerminalEventsEmitExactCurrentCleanupOnce() {
        listOf(KeepB5SessionEvent.RunStopped, KeepB5SessionEvent.ExplicitDisconnect).forEach { terminal ->
            val supervisor = KeepB5SessionSupervisor()
            val guard = readyPrivate(supervisor, activityAtMillis = 0L)
            val attempt = KeepB5AttemptGuard(guard.epoch, guard.connectionAttemptToken)
            val actions = supervisor.handle(terminal, 1_000L).withoutDiagnostics()
            assertEquals(
                listOf(
                    KeepB5SessionAction.CancelReconnect(guard.epoch, guard.connectionAttemptToken),
                    KeepB5SessionAction.StopScan(attempt),
                    KeepB5SessionAction.CancelPendingConnect(attempt),
                    KeepB5SessionAction.StopSync(guard.gattToken),
                    KeepB5SessionAction.CancelReadyTimeout(guard),
                    KeepB5SessionAction.PublishConnectionState(ConnectionState.DISCONNECTED),
                    KeepB5SessionAction.CloseGatt(guard, disconnectFirst = true),
                ),
                actions,
            )
            assertTrue(supervisor.handle(terminal, 2_000L).isEmpty())
            assertTrue(supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 2_000L).isEmpty())
        }
    }

    @Test
    fun resumedPrivateSyncResetsExactGattButStartPendingHasNoSyncToReset() {
        val readySupervisor = KeepB5SessionSupervisor()
        val readyGuard = readyPrivate(readySupervisor, activityAtMillis = 1_000L)
        readySupervisor.handle(KeepB5SessionEvent.RunPaused(20L), 1_000L)
        val resumed = readySupervisor.handle(KeepB5SessionEvent.RunResumed(20L), 2_000L)
        assertEquals(
            listOf(KeepB5SessionAction.ResetSyncUpdateAccounting(readyGuard)),
            resumed.filterIsInstance<KeepB5SessionAction.ResetSyncUpdateAccounting>(),
        )

        val pendingSupervisor = KeepB5SessionSupervisor()
        privateStartPending(pendingSupervisor)
        assertTrue(
            pendingSupervisor.handle(KeepB5SessionEvent.RunPaused(20L), 1_000L)
                .none { it is KeepB5SessionAction.ResetSyncUpdateAccounting },
        )
        assertTrue(
            pendingSupervisor.handle(KeepB5SessionEvent.RunResumed(20L), 2_000L)
                .none { it is KeepB5SessionAction.ResetSyncUpdateAccounting },
        )
    }

    @Test
    fun schedulerAndSupervisorKeepPreRunUpdateAtZeroAndHeartbeatAtSixSeconds() {
        var now = 0L
        val scheduler = KeepB5SyncScheduler(monotonicMillis = { now })
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyPrivate(supervisor, activityAtMillis = 0L)
        scheduler.start()

        now = 4_500L
        val firstPoll = requireNotNull(supervisor.captureSyncPollGuard(guard))
        val updateAction = supervisor.handle(
            KeepB5SessionEvent.SyncDue(firstPoll, scheduler.pollDue()),
            now,
        ).single() as KeepB5SessionAction.SendWorkoutUpdate
        assertEquals(
            0L,
            (supervisor.resolveSyncWrite(updateAction.guard, updateAction.due) as
                KeepB5WriteResolution.Allowed).elapsedSeconds,
        )

        now = 5_500L
        val intermediatePoll = requireNotNull(supervisor.captureSyncPollGuard(guard))
        supervisor.handle(KeepB5SessionEvent.SyncDue(intermediatePoll, scheduler.pollDue()), now)

        now = 6_000L
        val heartbeatPoll = requireNotNull(supervisor.captureSyncPollGuard(guard))
        val heartbeatAction = supervisor.handle(
            KeepB5SessionEvent.SyncDue(heartbeatPoll, scheduler.pollDue()),
            now,
        ).single() as KeepB5SessionAction.SendHeartbeat
        assertNull(
            (supervisor.resolveSyncWrite(
                heartbeatAction.guard,
                KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
            ) as KeepB5WriteResolution.Allowed).elapsedSeconds,
        )
    }

    @Test
    fun explicitDisconnectRejectsAlreadyQueuedPublicationsAtEffectTime() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyStandard(supervisor)
        val sample = TokenizedSensorSample(
            guard,
            SensorSample(SampleType.HEART_RATE, 128.0, 0L, "keep_b5"),
            0L,
        )
        val queuedSample = supervisor.handle(
            KeepB5SessionEvent.SensorSampleReceived(sample),
            0L,
        ).single() as KeepB5SessionAction.PublishSample
        val queuedInfo = supervisor.handle(
            KeepB5SessionEvent.DeviceInfoReceived(guard, DeviceInfo("B5")),
            0L,
        ).single() as KeepB5SessionAction.PublishDeviceInfo

        supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 0L)

        assertGattCallbackStale(supervisor.resolveGuardedAction(queuedSample))
        assertGattCallbackStale(supervisor.resolveGuardedAction(queuedInfo))
    }

    @Test
    fun staleWriteGuardsReturnTypedDiagnosticsAndDoNotRegisterOperations() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyPrivate(supervisor, activityAtMillis = 0L)
        val current = requireNotNull(supervisor.captureSyncPollGuard(guard))
        val staleGatt = current.copy(gattToken = current.gattToken + 1L)
        val staleRevision = current.copy(lifecycleRevision = current.lifecycleRevision - 1L)

        val gattRejected = supervisor.resolveSyncWrite(
            staleGatt,
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ) as KeepB5WriteResolution.Rejected
        assertEquals(BleStaleEventKind.SYNC_WRITE, (gattRejected.diagnostic as DiagnosticEvent.BleStaleEventIgnored).kind)
        val revisionRejected = supervisor.resolveSyncWrite(
            staleRevision,
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L),
        ) as KeepB5WriteResolution.Rejected
        assertTrue(revisionRejected.diagnostic is DiagnosticEvent.BleStaleWriteIgnored)

        val allowed = supervisor.resolveSyncWrite(
            current,
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ) as KeepB5WriteResolution.Allowed
        val accepted = supervisor.handle(
            KeepB5SessionEvent.SyncWriteCompleted(allowed.submission, accepted = true),
            0L,
        )
        val diagnostic = accepted.single { it is KeepB5SessionAction.RecordDiagnostic } as KeepB5SessionAction.RecordDiagnostic
        assertEquals(BleWriteResult.ACCEPTED, (diagnostic.event as DiagnosticEvent.BleSyncWrite).result)
        assertEquals(DiagnosticSyncKind.HEART_BEAT, diagnostic.event.kind)
        assertTrue(supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(allowed.submission, true), 0L).single() is KeepB5SessionAction.RecordDiagnostic)
    }

    @Test
    fun pendingPrivateActivityIsConsumedAfterStartAndCompletionClaimsOperationAcrossRevision() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = privateStartPending(supervisor)
        val early = supervisor.handle(
            KeepB5SessionEvent.PrivateActivity(PrivateWorkoutActivity(guard, receivedAtMillis = 1_000L)),
            2_000L,
        )
        assertTrue(early.isEmpty())
        val started = supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, true), 2_000L)
        assertTrue(started.contains(KeepB5SessionAction.StartSync(guard)))
        assertTrue(started.contains(KeepB5SessionAction.CancelReadyTimeout(guard)))
        assertTrue(started.contains(KeepB5SessionAction.PublishConnectionState(ConnectionState.CONNECTED)))

        val pollGuard = requireNotNull(supervisor.captureSyncPollGuard(guard))
        val submitted = (supervisor.resolveSyncWrite(
            pollGuard,
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L),
        ) as KeepB5WriteResolution.Allowed).submission
        supervisor.handle(KeepB5SessionEvent.RunStarted(5L), 2_000L)
        val failure = supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(submitted, false), 2_000L)
        assertTrue(failure.withoutDiagnostics().isEmpty())
        assertEquals(BleWriteResult.REJECTED,
            ((failure.single() as KeepB5SessionAction.RecordDiagnostic).event as DiagnosticEvent.BleSyncWrite).result)
        assertOnlyTypedStale(
            supervisor.handle(KeepB5SessionEvent.SyncWriteCompleted(submitted, false), 2_000L),
            BleStaleEventKind.SYNC_WRITE,
        )
        assertTrue(completeSync(supervisor, guard, KeepB5SyncKind.UPDATE_WORKOUT, false).withoutDiagnostics().isEmpty())
        val persistentFailure = completeSync(supervisor, guard, KeepB5SyncKind.UPDATE_WORKOUT, false)
        val retry = persistentFailure.filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single().guard
        assertFailureOrder(persistentFailure, guard, retry, BleFailureReason.SYNC_WRITE_REJECTED)
    }

    @Test
    fun runStoppedThenExplicitDisconnectDoesNotCloseOrAdvanceAgain() {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyStandard(supervisor)
        val stopped = supervisor.handle(KeepB5SessionEvent.RunStopped, 0L)
        assertEquals(1, stopped.count { it == KeepB5SessionAction.CloseGatt(guard, disconnectFirst = true) })
        val disconnected = supervisor.handle(KeepB5SessionEvent.ExplicitDisconnect, 0L)
        assertTrue(disconnected.isEmpty())
    }

    private fun begin(supervisor: KeepB5SessionSupervisor): KeepB5AttemptGuard = supervisor
        .handle(KeepB5SessionEvent.ExplicitConnect, 0L)
        .filterIsInstance<KeepB5SessionAction.BeginScan>()
        .single()
        .guard

    private fun createGatt(
        supervisor: KeepB5SessionSupervisor,
        attempt: KeepB5AttemptGuard,
        gattToken: Long = 1L,
    ): KeepB5GattGuard {
        supervisor.handle(KeepB5SessionEvent.ScanMatched(attempt), 0L)
        val guard = KeepB5GattGuard(attempt.epoch, attempt.connectionAttemptToken, gattToken)
        supervisor.handle(KeepB5SessionEvent.GattCreated(guard), 0L)
        return guard
    }

    private fun privateStartPending(supervisor: KeepB5SessionSupervisor): KeepB5GattGuard {
        val attempt = begin(supervisor)
        val guard = createGatt(supervisor, attempt)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        supervisor.handle(KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3), 0L)
        return guard
    }

    private fun readyPrivate(
        supervisor: KeepB5SessionSupervisor,
        attempt: KeepB5AttemptGuard = begin(supervisor),
        gattToken: Long = 1L,
        activityAtMillis: Long,
        consumedAtMillis: Long = activityAtMillis,
    ): KeepB5GattGuard {
        val guard = createGatt(supervisor, attempt, gattToken)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), consumedAtMillis)
        supervisor.handle(
            KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.PRIVATE_B3),
            consumedAtMillis,
        )
        supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.PRIVATE_B3),
            consumedAtMillis,
        )
        supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, true), consumedAtMillis)
        supervisor.handle(
            KeepB5SessionEvent.PrivateActivity(PrivateWorkoutActivity(guard, activityAtMillis)),
            consumedAtMillis,
        )
        return guard
    }

    private fun readyStandard(
        supervisor: KeepB5SessionSupervisor,
        attempt: KeepB5AttemptGuard = begin(supervisor),
        gattToken: Long = 1L,
    ): KeepB5GattGuard {
        val guard = createGatt(supervisor, attempt, gattToken)
        supervisor.handle(KeepB5SessionEvent.GattConnected(guard), 0L)
        supervisor.handle(KeepB5SessionEvent.ServicesDiscovered(guard, KeepB5ChannelKind.STANDARD_HR), 0L)
        supervisor.handle(
            KeepB5SessionEvent.NotificationsEnabled(guard, KeepB5ChannelKind.STANDARD_HR),
            0L,
        )
        return guard
    }

    private data class GuardedCase(
        val name: String,
        val supervisor: KeepB5SessionSupervisor,
        val action: KeepB5SessionAction,
        val expectedStaleKind: BleStaleEventKind,
    )

    private fun guardedBeginScanCase(): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val action = supervisor.handle(KeepB5SessionEvent.ExplicitConnect, 0L)
            .filterIsInstance<KeepB5SessionAction.BeginScan>().single()
        return GuardedCase("BeginScan", supervisor, action, BleStaleEventKind.CONTROL_ACTION)
    }

    private fun guardedConnectCase(): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        val action = supervisor.handle(KeepB5SessionEvent.ScanMatched(attempt), 0L)
            .filterIsInstance<KeepB5SessionAction.ConnectRemembered>().single()
        return GuardedCase("ConnectRemembered", supervisor, action, BleStaleEventKind.CONTROL_ACTION)
    }

    private fun guardedArmTimeoutCase(): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val attempt = begin(supervisor)
        val guard = createGatt(supervisor, attempt)
        return GuardedCase(
            "ArmReadyTimeout",
            supervisor,
            KeepB5SessionAction.ArmReadyTimeout(guard),
            BleStaleEventKind.CONTROL_ACTION,
        )
    }

    private fun guardedSendStartCase(): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val guard = privateStartPending(supervisor)
        return GuardedCase(
            "SendStart",
            supervisor,
            KeepB5SessionAction.SendStart(guard),
            BleStaleEventKind.CONTROL_ACTION,
        )
    }

    private fun guardedStartSyncCase(): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val guard = privateStartPending(supervisor)
        val action = supervisor.handle(KeepB5SessionEvent.StartWriteCompleted(guard, true), 0L)
            .filterIsInstance<KeepB5SessionAction.StartSync>().single()
        return GuardedCase("StartSync", supervisor, action, BleStaleEventKind.CONTROL_ACTION)
    }

    private fun guardedReconnectCase(): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyStandard(supervisor)
        val action = supervisor.handle(
            KeepB5SessionEvent.LinkFailed(guard, BleFailureReason.REMOTE_DISCONNECTED),
            0L,
        ).filterIsInstance<KeepB5SessionAction.ScheduleReconnect>().single()
        return GuardedCase("ScheduleReconnect", supervisor, action, BleStaleEventKind.CONTROL_ACTION)
    }

    private fun guardedPublicationCase(sample: Boolean): GuardedCase {
        val supervisor = KeepB5SessionSupervisor()
        val guard = readyStandard(supervisor)
        val action = if (sample) {
            supervisor.handle(
                KeepB5SessionEvent.SensorSampleReceived(
                    TokenizedSensorSample(
                        guard,
                        SensorSample(SampleType.HEART_RATE, 120.0, 0L, "keep_b5"),
                        0L,
                    ),
                ),
                0L,
            ).single()
        } else {
            supervisor.handle(
                KeepB5SessionEvent.DeviceInfoReceived(guard, DeviceInfo("B5")),
                0L,
            ).single()
        }
        return GuardedCase(
            if (sample) "PublishSample" else "PublishDeviceInfo",
            supervisor,
            action,
            BleStaleEventKind.GATT_CALLBACK,
        )
    }

    private fun assertOnlyGattStale(actions: List<KeepB5SessionAction>) =
        assertOnlyTypedStale(actions, BleStaleEventKind.GATT_CALLBACK)

    private fun assertOnlyTypedStale(
        actions: List<KeepB5SessionAction>,
        expectedKind: BleStaleEventKind,
    ) {
        val diagnostic = (actions.single() as KeepB5SessionAction.RecordDiagnostic).event as
            DiagnosticEvent.BleStaleEventIgnored
        assertEquals(expectedKind, diagnostic.kind)
    }

    private fun KeepB5SyncKind.toDiagnosticKind() = when (this) {
        KeepB5SyncKind.UPDATE_WORKOUT -> DiagnosticSyncKind.UPDATE_WORKOUT
        KeepB5SyncKind.HEART_BEAT -> DiagnosticSyncKind.HEART_BEAT
    }

    private fun assertFailureOrder(
        actions: List<KeepB5SessionAction>,
        oldGuard: KeepB5GattGuard,
        retryGuard: KeepB5AttemptGuard,
        reason: BleFailureReason,
    ) {
        assertEquals(
            listOf(
                KeepB5SessionAction.StopSync(oldGuard.gattToken),
                KeepB5SessionAction.CancelReadyTimeout(oldGuard),
                KeepB5SessionAction.CloseGatt(oldGuard, disconnectFirst = true),
                KeepB5SessionAction.PublishConnectionState(ConnectionState.ERROR),
                KeepB5SessionAction.ScheduleReconnect(retryGuard, reason),
            ),
            actions.withoutDiagnostics(),
        )
    }

    private fun assertGattCallbackStale(resolution: KeepB5ActionResolution) {
        val rejected = resolution as KeepB5ActionResolution.Rejected
        assertEquals(
            BleStaleEventKind.GATT_CALLBACK,
            (rejected.diagnostic as DiagnosticEvent.BleStaleEventIgnored).kind,
        )
    }

    private fun assertNoPrivateWriteActions(actions: List<KeepB5SessionAction>) {
        assertFalse(actions.any {
            it is KeepB5SessionAction.SendStart ||
                it is KeepB5SessionAction.StartSync ||
                it is KeepB5SessionAction.SendWorkoutUpdate ||
                it is KeepB5SessionAction.SendHeartbeat
        })
    }

    private fun List<KeepB5SessionAction>.withoutDiagnostics() =
        filterNot { it is KeepB5SessionAction.RecordDiagnostic }
}
