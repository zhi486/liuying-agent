package com.healthyagent.running.ui.web

import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.data.local.MotionPreference
import com.healthyagent.running.data.local.NullableSettingUpdate
import com.healthyagent.running.data.local.SettingsPatch
import com.healthyagent.running.data.local.ThemePreference
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** 将已通过 BridgeCodec 校验的设置命令转换为领域补丁。 */
internal fun JsonObject.toSettingsPatch(): SettingsPatch = SettingsPatch(
    targetPaceSecPerKm = nullableDoubleUpdate("targetPaceSecPerKm"),
    targetDistanceKm = nullableDoubleUpdate("targetDistanceKm"),
    maxHeartRate = optionalInt("maxHeartRate"),
    ttsEnabled = optionalBoolean("ttsEnabled"),
    ttsMinIntervalSec = optionalInt("ttsMinIntervalSec"),
    llmEnabled = optionalBoolean("llmEnabled"),
    mockScenario = optionalString("mockScenario"),
    coachTone = optionalString("coachTone")?.uppercase()?.let(CoachTone::valueOf),
    fishApiKey = optionalString("fishApiKey"),
    fishReferenceId = optionalString("fishReferenceId"),
    llmApiKey = optionalString("llmApiKey"),
    themePreference = optionalString("themePreference")?.uppercase()?.let(ThemePreference::valueOf),
    motionPreference = optionalString("motionPreference")?.uppercase()?.let(MotionPreference::valueOf),
)

private fun JsonObject.nullableDoubleUpdate(key: String): NullableSettingUpdate<Double> {
    val value = this[key] ?: return NullableSettingUpdate.Unchanged
    return if (value is JsonNull) {
        NullableSettingUpdate.Clear
    } else {
        NullableSettingUpdate.Set(value.jsonPrimitive.double)
    }
}

private fun JsonObject.optionalInt(key: String): Int? = this[key]?.jsonPrimitive?.int

private fun JsonObject.optionalBoolean(key: String): Boolean? = this[key]?.jsonPrimitive?.boolean

private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.content
