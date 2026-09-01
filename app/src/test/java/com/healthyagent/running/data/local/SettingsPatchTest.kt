package com.healthyagent.running.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPatchTest {

    @Test
    fun `最大心率必须处于合理范围`() {
        assertEquals(
            SettingsPatchError.INVALID_MAX_HEART_RATE,
            SettingsPatch(maxHeartRate = 20).validate().single(),
        )
    }

    @Test
    fun `空 patch 被拒绝`() {
        assertEquals(listOf(SettingsPatchError.EMPTY_PATCH), SettingsPatch().validate())
    }

    @Test
    fun `目标字段可显式清空`() {
        assertTrue(
            SettingsPatch(
                targetDistanceKm = NullableSettingUpdate.Clear,
            ).validate().isEmpty(),
        )
    }

    @Test
    fun `同时报告全部非法字段`() {
        assertEquals(
            setOf(
                SettingsPatchError.INVALID_TARGET_PACE,
                SettingsPatchError.INVALID_TARGET_DISTANCE,
                SettingsPatchError.INVALID_TTS_INTERVAL,
                SettingsPatchError.INVALID_MOCK_SCENARIO,
                SettingsPatchError.API_KEY_TOO_LONG,
                SettingsPatchError.INVALID_REFERENCE_ID,
            ),
            SettingsPatch(
                targetPaceSecPerKm = NullableSettingUpdate.Set(60.0),
                targetDistanceKm = NullableSettingUpdate.Set(500.0),
                ttsMinIntervalSec = 301,
                mockScenario = "UNKNOWN",
                fishApiKey = "x".repeat(513),
                fishReferenceId = "",
            ).validate().toSet(),
        )
    }
}
