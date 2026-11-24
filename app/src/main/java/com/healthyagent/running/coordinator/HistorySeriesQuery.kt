package com.healthyagent.running.coordinator

import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** 每页最多三百个五秒窗口，避免长跑记录超过 Bridge 消息上限。 */
internal suspend fun readHistorySeries(
    sessionId: String, offset: Int, limit: Int, load: suspend (String) -> RunSession?,
): CommandOutcome {
    if (sessionId.isBlank() || sessionId.length > 128 || sessionId != sessionId.trim() || offset < 0 || limit !in 1..300) {
        return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "序列查询参数无效")
    }
    return try {
        val run = load(sessionId)
            ?: return CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "未找到这次跑步记录")
        if (run.id != sessionId || run.runState != RunState.FINISHED) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "这次跑步尚未完成或记录不匹配")
        }
        if (offset > run.metricSamples.size) {
            return CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "序列分页超出范围")
        }
        CommandOutcome.AcceptedWithData(buildJsonObject {
            put("sessionId", sessionId)
            put("offset", offset)
            put("totalItems", run.metricSamples.size)
            put("samples", buildJsonArray {
                run.metricSamples.drop(offset).take(limit).forEach { sample ->
                    add(buildJsonObject {
                        put("startElapsedSeconds", sample.startElapsedSeconds)
                        put("endElapsedSeconds", sample.endElapsedSeconds)
                        put("segmentId", sample.segmentId)
                        put("averagePaceSecPerKm", sample.averagePaceSecPerKm?.let(::JsonPrimitive) ?: JsonNull)
                        put("averageHeartRate", sample.averageHeartRate?.let(::JsonPrimitive) ?: JsonNull)
                        put("paceValidSeconds", sample.paceValidSeconds)
                        put("heartRateValidSeconds", sample.heartRateValidSeconds)
                    })
                }
            })
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "读取本地曲线失败，请重试")
    }
}
