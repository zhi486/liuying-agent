package com.healthyagent.running.agent

import com.healthyagent.running.agent.local.AgentTaskDao
import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** 本阶段只落盘，不提供云端 worker 或后台自动重发。 */
class AgentTaskService(
    private val dao: AgentTaskDao,
    private val ownerId: String = "local",
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    init { require(ownerId.isNotBlank()) }

    suspend fun handle(name: String, body: JsonObject): CommandOutcome = try {
        val data = when (val command = AgentCommands.decode(name, body)) {
            is AgentCommand.Submit -> BridgeCodec.json.encodeToJsonElement(
                dao.saveLocal(ownerId, command.clientRequestId, command.prompt, newId(), now()),
            )
            is AgentCommand.ListTasks -> BridgeCodec.json.encodeToJsonElement(dao.page(ownerId, command.offset, command.limit))
            is AgentCommand.Detail -> {
                val detail = dao.detail(ownerId, command.taskId)
                if (detail == null) null else BridgeCodec.json.encodeToJsonElement(detail)
            }
            is AgentCommand.Cancel -> {
                val detail = dao.cancel(ownerId, command.taskId, now())
                if (detail == null) null else BridgeCodec.json.encodeToJsonElement(detail)
            }
        }
        if (data == null) CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "未找到任务")
        else CommandOutcome.AcceptedWithData(data.jsonObject)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AgentRequestConflict) {
        CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "同一任务请求标识不能对应不同内容")
    } catch (_: IllegalArgumentException) {
        CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "任务参数无效")
    } catch (_: Exception) {
        CommandOutcome.Rejected(BridgeErrorCode.INTERNAL_ERROR, "本地任务操作失败，请重试")
    }
}
