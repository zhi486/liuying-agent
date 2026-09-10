package com.healthyagent.running.data.sensor.ble

import com.healthyagent.running.diagnostics.BleFailureReason

internal enum class ScanAvailability { READY, PERMISSION_MISSING, ADAPTER_DISABLED }

internal interface KeepB5ScanPort {
    fun availability(): ScanAvailability
    fun start(listener: Listener): Boolean
    fun stop()

    interface Listener {
        fun onMatch(target: KeepB5ConnectTarget)
        fun onFailure(reason: BleFailureReason)
    }
}

internal interface KeepB5ConnectTarget
