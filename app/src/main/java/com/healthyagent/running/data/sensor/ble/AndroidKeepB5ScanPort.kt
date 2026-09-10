package com.healthyagent.running.data.sensor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.healthyagent.running.diagnostics.BleFailureReason

internal class AndroidKeepB5ConnectTarget(
    val device: BluetoothDevice,
) : KeepB5ConnectTarget

@SuppressLint("MissingPermission")
internal class AndroidKeepB5ScanPort internal constructor(
    private val availabilityProvider: () -> ScanAvailability,
    private val onCallbackRegistered: (ScanCallback) -> Unit,
    private val platformStart: (ScanCallback) -> Boolean,
    private val platformStop: (ScanCallback) -> Unit,
) : KeepB5ScanPort {
    internal constructor(scanner: BluetoothLeScanner?) : this(
        availabilityProvider = {
            if (scanner == null) ScanAvailability.ADAPTER_DISABLED else ScanAvailability.READY
        },
        onCallbackRegistered = {},
        platformStart = scannerStart { scanner },
        platformStop = scannerStop { scanner },
    )

    constructor(context: Context) : this(
        availabilityProvider = {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            }
            val hasPermissions = permissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (!hasPermissions) {
                ScanAvailability.PERMISSION_MISSING
            } else {
                val manager = context.applicationContext
                    .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                if (manager?.adapter?.isEnabled == true && manager.adapter?.bluetoothLeScanner != null) {
                    ScanAvailability.READY
                } else {
                    ScanAvailability.ADAPTER_DISABLED
                }
            }
        },
        onCallbackRegistered = {},
        platformStart = scannerStart { scanner(context) },
        platformStop = scannerStop { scanner(context) },
    )

    private val lock = Any()
    private var activeOperation: ScanOperation? = null

    override fun availability(): ScanAvailability = availabilityProvider()

    override fun start(listener: KeepB5ScanPort.Listener): Boolean {
        lateinit var operation: ScanOperation
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                operation.dispatchIfActive {
                    val device = result.device
                    dispatchScanResult(
                        advertisedName = result.scanRecord?.deviceName,
                        safeDeviceName = { device.name },
                        target = AndroidKeepB5ConnectTarget(device),
                        listener = listener,
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                operation.dispatchIfActive {
                    listener.onFailure(BleFailureReason.SCAN_FAILED)
                }
            }
        }
        operation = ScanOperation(callback, platformStart, platformStop)
        val replaced = synchronized(lock) {
            val previous = activeOperation
            activeOperation = operation
            previous
        }
        replaced?.cancelAndStop()

        val accepted = try {
            onCallbackRegistered(callback)
            operation.start()
        } catch (error: Throwable) {
            synchronized(lock) {
                if (activeOperation === operation) activeOperation = null
            }
            operation.cancelAndStop()
            throw error
        }
        if (!accepted) {
            synchronized(lock) {
                if (activeOperation === operation) activeOperation = null
            }
        }
        return accepted
    }

    override fun stop() {
        val operation = synchronized(lock) {
            val current = activeOperation
            activeOperation = null
            current
        } ?: return
        operation.cancelAndStop()
    }

    internal fun dispatchScanResult(
        advertisedName: String?,
        safeDeviceName: () -> String?,
        target: KeepB5ConnectTarget,
        listener: KeepB5ScanPort.Listener,
    ): Boolean {
        if (!KeepB5DeviceMatcher.matches(advertisedName, safeDeviceName)) return false
        listener.onMatch(target)
        return true
    }

    private class ScanOperation(
        private val callback: ScanCallback,
        private val platformStart: (ScanCallback) -> Boolean,
        private val platformStop: (ScanCallback) -> Unit,
    ) {
        private val lifecycleLock = Any()
        private var cancelled = false
        private var platformStarted = false
        private var stopClaimed = false

        fun start(): Boolean = synchronized(lifecycleLock) {
            if (cancelled) return false
            val accepted = try {
                platformStart(callback)
            } catch (error: Throwable) {
                cancelled = true
                throw error
            }
            if (!accepted) {
                cancelled = true
                return false
            }
            platformStarted = true
            if (!cancelled) return true
            stopClaimed = true
            platformStop(callback)
            false
        }

        fun cancelAndStop() = synchronized(lifecycleLock) {
            cancelled = true
            if (!platformStarted || stopClaimed) return
            stopClaimed = true
            platformStop(callback)
        }

        fun dispatchIfActive(block: () -> Unit): Boolean = synchronized(lifecycleLock) {
            if (cancelled) return false
            block()
            true
        }
    }

    private companion object {
        fun scanner(context: Context): BluetoothLeScanner? {
            val manager = context.applicationContext
                .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.bluetoothLeScanner
        }

        fun scannerStart(
            scannerProvider: () -> BluetoothLeScanner?,
        ): (ScanCallback) -> Boolean = { callback ->
            val scanner = scannerProvider()
            if (scanner == null) {
                false
            } else {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(emptyList(), settings, callback)
                true
            }
        }

        fun scannerStop(
            scannerProvider: () -> BluetoothLeScanner?,
        ): (ScanCallback) -> Unit = { callback ->
            scannerProvider()?.stopScan(callback)
        }
    }
}
