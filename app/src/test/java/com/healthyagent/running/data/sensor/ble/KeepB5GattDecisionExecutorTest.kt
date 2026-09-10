package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.diagnostics.BleFailureReason
import com.healthyagent.running.diagnostics.BleOperation
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.testing.FakeDiagnosticLogger
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5GattDecisionExecutorTest {
    @Test
    fun `initialize publishes one empty session snapshot and closed gate publishes nothing`() {
        val active = fixture()
        active.executor.initialize()
        active.executor.initialize()
        assertEquals(
            listOf(RawGattSignal.DeviceInfoUpdated(DeviceInfo("Keep B5", null, null))),
            active.signals,
        )

        val closed = fixture(activate = false)
        closed.gate.close()
        closed.executor.initialize()
        assertTrue(closed.signals.isEmpty())
    }

    @Test
    fun `discover submission exhaustively errors and only rejected or exception records typed diagnostic`() {
        listOf(
            GattIoSubmission.UNAVAILABLE to 0,
            GattIoSubmission.REJECTED to 1,
        ).forEach { (result, expectedDiagnostics) ->
            val fixture = fixture(discover = { result })
            fixture.executor.execute(GattCallbackDecision.DiscoverServices)
            assertEquals(listOf(RawGattSignal.Error(BleFailureReason.GATT_ERROR)), fixture.signals)
            assertEquals(expectedDiagnostics, fixture.logger.events.size)
        }

        val failure = IllegalStateException("discover")
        val fixture = fixture(discover = { throw failure })
        fixture.executor.execute(GattCallbackDecision.DiscoverServices)
        assertEquals(listOf(RawGattSignal.Error(BleFailureReason.GATT_ERROR)), fixture.signals)
        assertOperation(fixture.logger, BleOperation.DISCOVER_SERVICES)
    }

    @Test
    fun `standard enable failure falls back private once and private failure terminates once`() {
        val results = ArrayDeque(listOf(GattIoSubmission.REJECTED, GattIoSubmission.ACCEPTED))
        val fixture = fixture(enable = { results.removeFirst() })
        val standard = GattCallbackDecision.EnableNotifications(
            KeepB5ChannelKind.STANDARD_HR,
            KeepB5ChannelKind.PRIVATE_B3,
        )
        fixture.executor.execute(standard)
        fixture.executor.execute(standard)
        assertEquals(
            listOf(
                RawGattSignal.ServicesDiscovered(KeepB5ChannelKind.STANDARD_HR),
                RawGattSignal.ServicesDiscovered(KeepB5ChannelKind.PRIVATE_B3),
            ),
            fixture.signals,
        )
        assertEquals(
            listOf(KeepB5ChannelKind.STANDARD_HR, KeepB5ChannelKind.PRIVATE_B3),
            fixture.io.enabledChannels,
        )
        assertOperation(fixture.logger, BleOperation.ENABLE_NOTIFICATIONS)

        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.PRIVATE_B3, false))
        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.PRIVATE_B3, false))
        assertEquals(1, fixture.signals.count { it == RawGattSignal.Error(BleFailureReason.GATT_ERROR) })
    }

    @Test
    fun `standard accepted waits for matching descriptor then emits ready and starts metadata only once`() {
        val fixture = fixture(readBattery = { GattIoSubmission.ACCEPTED })
        fixture.executor.execute(
            GattCallbackDecision.EnableNotifications(
                KeepB5ChannelKind.STANDARD_HR,
                KeepB5ChannelKind.PRIVATE_B3,
            ),
        )
        assertEquals(0, fixture.io.readBatteryCalls)
        assertEquals(1, fixture.io.enableCalls)

        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.STANDARD_HR, true))
        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.STANDARD_HR, false))
        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.STANDARD_HR, true))

        assertEquals(1, fixture.io.readBatteryCalls)
        assertEquals(1, fixture.io.enableCalls)
        assertEquals(
            listOf(
                RawGattSignal.ServicesDiscovered(KeepB5ChannelKind.STANDARD_HR),
                RawGattSignal.NotificationsEnabled(KeepB5ChannelKind.STANDARD_HR),
            ),
            fixture.signals,
        )
    }

    @Test
    fun `late standard results after private fallback cannot read metadata or reverse channel`() {
        val fixture = fixture(enable = { channel ->
            if (channel == KeepB5ChannelKind.STANDARD_HR) GattIoSubmission.UNAVAILABLE else GattIoSubmission.ACCEPTED
        })
        fixture.executor.execute(
            GattCallbackDecision.EnableNotifications(
                KeepB5ChannelKind.STANDARD_HR,
                KeepB5ChannelKind.PRIVATE_B3,
            ),
        )
        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.STANDARD_HR, true))
        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.STANDARD_HR, false))
        fixture.executor.execute(GattCallbackDecision.NotificationDescriptorResult(KeepB5ChannelKind.PRIVATE_B3, true))

        assertEquals(0, fixture.io.readBatteryCalls)
        assertEquals(2, fixture.io.enableCalls)
        assertEquals(
            RawGattSignal.NotificationsEnabled(KeepB5ChannelKind.PRIVATE_B3),
            fixture.signals.last(),
        )
    }

    @Test
    fun `metadata submission failures continue once and updates emit complete isolated snapshots`() {
        val fixture = fixture(
            readBattery = { GattIoSubmission.REJECTED },
            readFirmware = { GattIoSubmission.REJECTED },
        )
        fixture.executor.execute(GattCallbackDecision.ReadDeviceMetadata)
        assertEquals(1, fixture.io.readBatteryCalls)
        assertEquals(1, fixture.io.readFirmwareCalls)
        assertEquals(
            listOf(BleOperation.READ_BATTERY, BleOperation.READ_FIRMWARE),
            fixture.logger.events.map { (it as DiagnosticEvent.BleOperationFailed).operation },
        )

        fixture.executor.execute(GattCallbackDecision.UpdateBattery(88, 12_345L, 1_700_000_000_000L))
        fixture.executor.execute(GattCallbackDecision.UpdateFirmware("2.0"))
        fixture.executor.execute(GattCallbackDecision.UpdateBattery(null, 13_000L, 1_700_000_001_000L))
        assertEquals(
            listOf(
                RawGattSignal.DeviceInfoUpdated(DeviceInfo("Keep B5", 88, null)),
                RawGattSignal.BatteryLevel(88, 12_345L, 1_700_000_000_000L),
                RawGattSignal.DeviceInfoUpdated(DeviceInfo("Keep B5", 88, "2.0")),
                RawGattSignal.DeviceInfoUpdated(DeviceInfo("Keep B5", null, "2.0")),
            ),
            fixture.signals,
        )

        val other = fixture()
        other.executor.execute(GattCallbackDecision.UpdateFirmware("B"))
        assertEquals(
            listOf(RawGattSignal.DeviceInfoUpdated(DeviceInfo("Keep B5", null, "B"))),
            other.signals,
        )
    }

    @Test
    fun `every IO is gated and closing during the permitted call suppresses follow-up work`() {
        val closed = fixture(activate = false)
        closed.gate.close()
        closed.executor.execute(GattCallbackDecision.DiscoverServices)
        closed.executor.execute(GattCallbackDecision.EnableNotifications(KeepB5ChannelKind.PRIVATE_B3))
        closed.executor.execute(GattCallbackDecision.ReadDeviceMetadata)
        closed.executor.execute(GattCallbackDecision.ReadFirmware)
        assertEquals(0, closed.io.totalCalls)
        assertTrue(closed.signals.isEmpty())

        lateinit var during: Fixture
        during = fixture(discover = {
            during.gate.close()
            GattIoSubmission.REJECTED
        })
        during.executor.execute(GattCallbackDecision.DiscoverServices)
        assertEquals(1, during.io.discoverCalls)
        assertTrue(during.signals.isEmpty())
        assertTrue(during.logger.events.isEmpty())
        during.executor.execute(GattCallbackDecision.ReadFirmware)
        assertEquals(1, during.io.totalCalls)
    }

    @Test
    fun `cancellation and errors are not swallowed`() {
        val cancellation = CancellationException("cancel")
        val cancelled = fixture(discover = { throw cancellation })
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                cancelled.executor.execute(GattCallbackDecision.DiscoverServices)
            },
        )

        val fatal = AssertionError("fatal")
        val errored = fixture(discover = { throw fatal })
        assertSame(
            fatal,
            assertThrows(AssertionError::class.java) {
                errored.executor.execute(GattCallbackDecision.DiscoverServices)
            },
        )
    }

    private fun fixture(
        activate: Boolean = true,
        discover: () -> GattIoSubmission = { GattIoSubmission.ACCEPTED },
        enable: (KeepB5ChannelKind) -> GattIoSubmission = { GattIoSubmission.ACCEPTED },
        readBattery: () -> GattIoSubmission = { GattIoSubmission.ACCEPTED },
        readFirmware: () -> GattIoSubmission = { GattIoSubmission.ACCEPTED },
    ): Fixture {
        val gate = KeepB5GattRegistrationGate()
        if (activate) gate.activate()
        val io = FakeIo(discover, enable, readBattery, readFirmware)
        val signals = mutableListOf<RawGattSignal>()
        val logger = FakeDiagnosticLogger()
        return Fixture(
            gate,
            io,
            signals,
            logger,
            KeepB5GattDecisionExecutor(io, "Keep B5", signals::add, logger, gate),
        )
    }

    private fun assertOperation(logger: FakeDiagnosticLogger, operation: BleOperation) {
        val event = logger.events.single() as DiagnosticEvent.BleOperationFailed
        assertEquals(operation, event.operation)
        assertEquals(BleFailureReason.GATT_ERROR, event.reason)
    }

    private data class Fixture(
        val gate: KeepB5GattRegistrationGate,
        val io: FakeIo,
        val signals: MutableList<RawGattSignal>,
        val logger: FakeDiagnosticLogger,
        val executor: KeepB5GattDecisionExecutor,
    )

    private class FakeIo(
        private val discover: () -> GattIoSubmission,
        private val enable: (KeepB5ChannelKind) -> GattIoSubmission,
        private val readBattery: () -> GattIoSubmission,
        private val readFirmware: () -> GattIoSubmission,
    ) : KeepB5GattIoPort {
        var discoverCalls = 0
        var enableCalls = 0
        var readBatteryCalls = 0
        var readFirmwareCalls = 0
        val enabledChannels = mutableListOf<KeepB5ChannelKind>()
        val totalCalls get() = discoverCalls + enableCalls + readBatteryCalls + readFirmwareCalls

        override fun discoverServices(): GattIoSubmission {
            discoverCalls++
            return discover()
        }

        override fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission {
            enableCalls++
            enabledChannels += channel
            return enable(channel)
        }

        override fun readBattery(): GattIoSubmission {
            readBatteryCalls++
            return readBattery.invoke()
        }

        override fun readFirmware(): GattIoSubmission {
            readFirmwareCalls++
            return readFirmware.invoke()
        }
    }
}
