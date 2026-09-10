package com.healthyagent.running.data.sensor.ble

internal object KeepB5DeviceMatcher {
    val knownPrefixes: List<String> = listOf("Keep B5", "KeepB5", "Keep_B5", "KEEP")

    fun matches(advertisedName: String?, safeDeviceName: () -> String?): Boolean {
        val name = advertisedName ?: try {
            safeDeviceName()
        } catch (_: SecurityException) {
            null
        }
        return name != null && knownPrefixes.any { prefix ->
            name.startsWith(prefix, ignoreCase = true)
        }
    }
}
