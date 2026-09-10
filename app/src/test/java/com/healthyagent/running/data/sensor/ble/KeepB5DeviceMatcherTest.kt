package com.healthyagent.running.data.sensor.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepB5DeviceMatcherTest {
    @Test
    fun `matches every known prefix ignoring case and allowing suffixes`() {
        listOf(
            "keep b5-pro",
            "KEEPB5 1234",
            "keep_b5-runner",
            "Keep device",
        ).forEach { name ->
            assertTrue(KeepB5DeviceMatcher.matches(name) { null })
        }
    }

    @Test
    fun `rejects missing empty and unrelated names`() {
        assertFalse(KeepB5DeviceMatcher.matches(null) { null })
        assertFalse(KeepB5DeviceMatcher.matches("") { "Keep B5" })
        assertFalse(KeepB5DeviceMatcher.matches("B5") { "Keep B5" })
    }

    @Test
    fun `non-null advertised name wins without reading device name`() {
        var safeNameReads = 0

        val matched = KeepB5DeviceMatcher.matches("unrelated") {
            safeNameReads++
            "Keep B5"
        }

        assertFalse(matched)
        assertTrue(safeNameReads == 0)
    }

    @Test
    fun `null advertised name falls back to safe device name`() {
        assertTrue(KeepB5DeviceMatcher.matches(null) { "KEEP-runner" })
    }

    @Test
    fun `security exception while reading device name behaves as missing name`() {
        assertFalse(
            KeepB5DeviceMatcher.matches(null) {
                throw SecurityException("denied")
            },
        )
    }
}
