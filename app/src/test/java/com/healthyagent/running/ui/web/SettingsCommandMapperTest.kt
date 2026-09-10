package com.healthyagent.running.ui.web

import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.data.local.MotionPreference
import com.healthyagent.running.data.local.NullableSettingUpdate
import com.healthyagent.running.data.local.ThemePreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCommandMapperTest {

    @Test
    fun `完整设置命令映射为领域补丁`() {
        val body = Json.parseToJsonElement(
            """{
                "targetPaceSecPerKm":390,
                "targetDistanceKm":8,
                "maxHeartRate":172,
                "ttsEnabled":false,
                "ttsMinIntervalSec":30,
                "coachTone":"gentle",
                "themePreference":"morning",
                "motionPreference":"reduced"
            }""",
        ).jsonObject

        val patch = body.toSettingsPatch()

        assertEquals(NullableSettingUpdate.Set(390.0), patch.targetPaceSecPerKm)
        assertEquals(NullableSettingUpdate.Set(8.0), patch.targetDistanceKm)
        assertEquals(172, patch.maxHeartRate)
        assertEquals(false, patch.ttsEnabled)
        assertEquals(30, patch.ttsMinIntervalSec)
        assertEquals(CoachTone.GENTLE, patch.coachTone)
        assertEquals(ThemePreference.MORNING, patch.themePreference)
        assertEquals(MotionPreference.REDUCED, patch.motionPreference)
    }

    @Test
    fun `目标字段 null 显式清空且缺失字段保持不变`() {
        val patch = Json.parseToJsonElement(
            """{"targetPaceSecPerKm":null}""",
        ).jsonObject.toSettingsPatch()

        assertEquals(NullableSettingUpdate.Clear, patch.targetPaceSecPerKm)
        assertEquals(NullableSettingUpdate.Unchanged, patch.targetDistanceKm)
    }
}
