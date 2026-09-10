package com.healthyagent.running.data.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.healthyagent.running.diagnostics.DiagnosticLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal fun interface AndroidKeepB5GattConnectionFactory {
    fun connect(
        target: KeepB5ConnectTarget,
        callback: KeepB5GattCallbackIngress,
    ): AndroidKeepB5GattPlatform?
}

/** JVM-fakeable boundary below one Android GATT session. */
internal interface AndroidKeepB5GattPlatform : KeepB5GattIoPort {
    fun writePrivate(value: ByteArray, writeType: Int): GattIoSubmission
    fun disconnect()
    fun close()
}

internal fun interface AndroidGattMetadataReadPlatform {
    fun readCharacteristic(): Boolean
}

internal fun submitAndroidGattMetadataRead(
    properties: Int,
    platform: AndroidGattMetadataReadPlatform,
): GattIoSubmission {
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
        return GattIoSubmission.UNAVAILABLE
    }
    return if (platform.readCharacteristic()) {
        GattIoSubmission.ACCEPTED
    } else {
        GattIoSubmission.REJECTED
    }
}

internal class AndroidKeepB5GattConnector(
    private val monotonicMillis: () -> Long,
    private val wallClockMillis: () -> Long,
    private val processorScope: CoroutineScope,
    private val processorDispatcher: CoroutineDispatcher,
    private val diagnosticLogger: DiagnosticLogger,
    private val deviceName: (KeepB5ConnectTarget) -> String,
    private val connectionFactory: AndroidKeepB5GattConnectionFactory,
) : KeepB5GattConnector {
    constructor(
        context: Context,
        monotonicMillis: () -> Long,
        wallClockMillis: () -> Long,
        processorScope: CoroutineScope,
        processorDispatcher: CoroutineDispatcher,
        diagnosticLogger: DiagnosticLogger,
    ) : this(
        monotonicMillis = monotonicMillis,
        wallClockMillis = wallClockMillis,
        processorScope = processorScope,
        processorDispatcher = processorDispatcher,
        diagnosticLogger = diagnosticLogger,
        deviceName = { target -> safeDeviceName(target) },
        connectionFactory = AndroidKeepB5GattConnectionFactory { target, ingress ->
            connectAndroid(context.applicationContext, target, ingress)
        },
    )

    override fun connect(
        target: KeepB5ConnectTarget,
        registrationGate: KeepB5GattRegistrationGate,
        listener: KeepB5GattSessionListener,
    ): KeepB5GattSession? {
        val session = AndroidKeepB5GattSession(
            deviceName = deviceName(target),
            registrationGate = registrationGate,
            listener = listener,
            processorScope = processorScope,
            processorDispatcher = processorDispatcher,
            diagnosticLogger = diagnosticLogger,
            monotonicMillis = monotonicMillis,
            wallClockMillis = wallClockMillis,
        )
        val platform = try {
            connectionFactory.connect(target, session.callbackIngress)
        } catch (error: Throwable) {
            session.shutdownCallbacks()
            throw error
        }
        if (platform == null) {
            session.shutdownCallbacks()
            return null
        }
        session.bindPlatform(platform)
        return session
    }

    private companion object {
        @SuppressLint("MissingPermission")
        fun connectAndroid(
            context: Context,
            target: KeepB5ConnectTarget,
            ingress: KeepB5GattCallbackIngress,
        ): AndroidKeepB5GattPlatform? {
            val androidTarget = target as? AndroidKeepB5ConnectTarget ?: return null
            val callback = KeepB5GattCallback(ingress)
            val gatt = androidTarget.device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            ) ?: return null
            return BluetoothGattPlatform(gatt)
        }

        @SuppressLint("MissingPermission")
        fun safeDeviceName(target: KeepB5ConnectTarget): String {
            val androidTarget = target as? AndroidKeepB5ConnectTarget ?: return "Keep B5"
            return try {
                androidTarget.device.name?.takeIf(String::isNotBlank) ?: "Keep B5"
            } catch (_: SecurityException) {
                "Keep B5"
            }
        }
    }
}

internal class AndroidKeepB5GattSession(
    deviceName: String,
    private val registrationGate: KeepB5GattRegistrationGate,
    listener: KeepB5GattSessionListener,
    processorScope: CoroutineScope,
    processorDispatcher: CoroutineDispatcher,
    diagnosticLogger: DiagnosticLogger,
    monotonicMillis: () -> Long = { 0L },
    wallClockMillis: () -> Long = { 0L },
) : KeepB5GattSession {
    private val platform = AtomicReference<AndroidKeepB5GattPlatform?>(null)
    private val io = object : KeepB5GattIoPort {
        override fun discoverServices(): GattIoSubmission =
            platform.get()?.discoverServices() ?: GattIoSubmission.UNAVAILABLE

        override fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission =
            platform.get()?.enableNotifications(channel) ?: GattIoSubmission.UNAVAILABLE

        override fun readBattery(): GattIoSubmission =
            platform.get()?.readBattery() ?: GattIoSubmission.UNAVAILABLE

        override fun readFirmware(): GattIoSubmission =
            platform.get()?.readFirmware() ?: GattIoSubmission.UNAVAILABLE
    }
    private val executor = KeepB5GattDecisionExecutor(
        io = io,
        deviceName = deviceName,
        signalSink = { signal -> listener.onSignal(this, signal) },
        diagnosticLogger = diagnosticLogger,
        registrationGate = registrationGate,
    )
    private val processor = KeepB5GattCallbackProcessor(
        mapper = KeepB5GattCallbackMapper(),
        executor = executor,
        registrationGate = registrationGate,
        scope = processorScope,
        dispatcher = processorDispatcher,
    )
    val callbackIngress = KeepB5GattCallbackIngress(
        offer = processor::offer,
        monotonicMillis = monotonicMillis,
        wallClockMillis = wallClockMillis,
    )
    private val shutdownLock = Any()
    private var callbackJob: Job? = null
    private var nextRequestId = 1

    fun bindPlatform(value: AndroidKeepB5GattPlatform) {
        check(platform.compareAndSet(null, value)) { "Android GATT session is already bound" }
    }

    @Synchronized
    override fun sendStart(epochSeconds: Long): Boolean = write(
        KeepB5B3Protocol.buildStartWorkoutRequest(nextRequestId(), epochSeconds),
    )

    @Synchronized
    override fun sendWorkoutUpdate(elapsedSeconds: Long, displayMetrics: KeepB5DisplayMetrics): Boolean = write(
        KeepB5B3Protocol.buildWorkoutStatusRequest(nextRequestId(), elapsedSeconds, displayMetrics),
    )

    @Synchronized
    override fun sendHeartbeat(): Boolean = write(
        KeepB5B3Protocol.buildTrainingHeartbeatRequest(nextRequestId()),
    )

    override fun shutdownCallbacks(): Job = synchronized(shutdownLock) {
        callbackJob?.let { return it }
        registrationGate.close()
        processor.shutdown().also { callbackJob = it }
    }

    override fun disconnect() {
        platform.get()?.disconnect()
    }

    override fun close() {
        platform.get()?.close()
    }

    private fun write(frame: ByteArray): Boolean {
        if (!registrationGate.tryAcquireExecution()) return false
        return when (
            platform.get()?.writePrivate(
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) ?: GattIoSubmission.UNAVAILABLE
        ) {
            GattIoSubmission.ACCEPTED -> true
            GattIoSubmission.REJECTED,
            GattIoSubmission.UNAVAILABLE,
            -> false
        }
    }

    private fun nextRequestId(): Int {
        val current = nextRequestId
        nextRequestId = if (current == 0xffff) 1 else current + 1
        return current
    }
}

@SuppressLint("MissingPermission")
private class BluetoothGattPlatform(
    private val gatt: BluetoothGatt,
) : AndroidKeepB5GattPlatform {
    override fun discoverServices(): GattIoSubmission = gatt.discoverServices().toSubmission()

    override fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission {
        val characteristic = when (channel) {
            KeepB5ChannelKind.STANDARD_HR -> characteristic(HR_SERVICE, HR_MEASUREMENT)
            KeepB5ChannelKind.PRIVATE_B3 -> characteristic(PRIVATE_SERVICE, PRIVATE_NOTIFY)
        } ?: return GattIoSubmission.UNAVAILABLE
        val descriptorValue = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> return GattIoSubmission.UNAVAILABLE
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHAR_CONFIG)
            ?: return GattIoSubmission.UNAVAILABLE
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return GattIoSubmission.REJECTED
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, descriptorValue).toSubmission()
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = descriptorValue.copyOf()
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor).toSubmission()
        }
    }

    override fun readBattery(): GattIoSubmission =
        read(BATTERY_SERVICE, BATTERY_LEVEL)

    override fun readFirmware(): GattIoSubmission =
        read(DEVICE_INFO_SERVICE, FIRMWARE_REVISION)

    override fun writePrivate(value: ByteArray, writeType: Int): GattIoSubmission {
        val characteristic = characteristic(PRIVATE_SERVICE, PRIVATE_COMMAND)
            ?: return GattIoSubmission.UNAVAILABLE
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
            return GattIoSubmission.UNAVAILABLE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value.copyOf(), writeType).toSubmission()
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = value.copyOf()
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic).toSubmission()
        }
    }

    override fun disconnect() {
        gatt.disconnect()
    }

    override fun close() {
        gatt.close()
    }

    private fun read(serviceUuid: UUID, characteristicUuid: UUID): GattIoSubmission {
        val characteristic = characteristic(serviceUuid, characteristicUuid)
            ?: return GattIoSubmission.UNAVAILABLE
        return submitAndroidGattMetadataRead(
            properties = characteristic.properties,
            platform = AndroidGattMetadataReadPlatform {
                gatt.readCharacteristic(characteristic)
            },
        )
    }

    private fun characteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic? =
        gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)

    private fun Boolean.toSubmission(): GattIoSubmission = if (this) {
        GattIoSubmission.ACCEPTED
    } else {
        GattIoSubmission.REJECTED
    }

    private fun Int.toSubmission(): GattIoSubmission = if (this == BluetoothStatusCodes.SUCCESS) {
        GattIoSubmission.ACCEPTED
    } else {
        GattIoSubmission.REJECTED
    }

    private companion object {
        val HR_SERVICE: UUID = uuid16("180d")
        val HR_MEASUREMENT: UUID = uuid16("2a37")
        val BATTERY_SERVICE: UUID = uuid16("180f")
        val BATTERY_LEVEL: UUID = uuid16("2a19")
        val DEVICE_INFO_SERVICE: UUID = uuid16("180a")
        val FIRMWARE_REVISION: UUID = uuid16("2a26")
        val CLIENT_CHAR_CONFIG: UUID = uuid16("2902")
        val PRIVATE_SERVICE: UUID = uuid16("190a")
        val PRIVATE_COMMAND: UUID = uuid16("0001")
        val PRIVATE_NOTIFY: UUID = uuid16("0002")

        fun uuid16(value: String): UUID =
            UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
    }
}
