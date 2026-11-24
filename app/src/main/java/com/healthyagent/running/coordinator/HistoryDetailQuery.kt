package com.healthyagent.running.coordinator

import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 只读已完成记录；不向 WebView 返回精确轨迹。 */
internal suspend fun readHistoryDetail(
    sessionId: String,
    load: suspend (String) -> RunSession?,
): CommandOutcome {
    if (sessionId.isBlank() || sessionId.length > 128 || sessionId != sessionId.trim()) {
        return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "记录标识无效")
    }
    return try {
        val run = load(sessionId)
            ?: return CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "未找到这次跑步记录")
        if (run.runState != RunState.FINISHED || run.id != sessionId) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "这次跑步尚未完成或记录不匹配")
        }
        CommandOutcome.AcceptedWithData(buildJsonObject {
            put("record", buildJsonObject {
                put("id", run.id)
                put("startedAt", Instant.ofEpochMilli(run.startTimeMillis).toString())
                put("endedAt", run.endTimeMillis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                put("distanceMeters", run.totalDistanceMeters)
                put("durationSeconds", run.totalDurationSec)
                put("averagePaceSecPerKm", run.avgPaceSecPerKm?.let(::JsonPrimitive) ?: JsonNull)
                put("averageHeartRate", run.avgHeartRate?.let(::JsonPrimitive) ?: JsonNull)
                put("averageCadence", run.avgCadence?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("splits", buildJsonArray {
                run.splits.sortedBy { it.index }.forEach { split ->
                    add(buildJsonObject {
                        put("index", split.index)
                        put("endDistanceMeters", split.distanceMeters)
                        put("durationSeconds", split.durationSec)
                        put("averagePaceSecPerKm", split.avgPaceSecPerKm)
                        put("averageHeartRate", split.avgHeartRate?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "读取本地总结失败，请重试")
    }
}
