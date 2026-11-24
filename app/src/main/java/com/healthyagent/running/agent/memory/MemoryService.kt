package com.healthyagent.running.agent.memory

import com.healthyagent.running.bridge.BridgeCodec
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.coordinator.CommandOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** 显式本机记忆管理，不自动提取任务、上传或调用模型。 */
class MemoryService(private val dao: MemoryDao, private val ownerId: String = "local", private val now: () -> Long = System::currentTimeMillis) {
    init { require(ownerId.isNotBlank()) }

    suspend fun document(): MemoryDocument = dao.document(ownerId, now())

    suspend fun handle(name: String, body: JsonObject): CommandOutcome = try {
        val data = when (val command = MemoryCommands.decode(name, body)) {
            is MemoryCommand.Create -> BridgeCodec.json.encodeToJsonElement(MemoryDetail(dao.create(ownerId, command.memoryId, command.content, now())))
            is MemoryCommand.ListMemories -> BridgeCodec.json.encodeToJsonElement(dao.page(ownerId, command.offset, command.limit))
            is MemoryCommand.Document -> BridgeCodec.json.encodeToJsonElement(document().page(command.offset, command.limit))
            is MemoryCommand.Preview -> BridgeCodec.json.encodeToJsonElement(dao.selected(ownerId, command.memoryRefs))
            is MemoryCommand.History -> dao.history(ownerId, command.memoryId, command.offset, command.limit)?.let { BridgeCodec.json.encodeToJsonElement(it) }
            is MemoryCommand.Restore -> dao.restore(ownerId, command.memoryId, command.version, command.targetVersion, now())?.let { BridgeCodec.json.encodeToJsonElement(MemoryDetail(it)) }
            is MemoryCommand.Update -> dao.update(ownerId, command.memoryId, command.version, command.content, now())?.let { BridgeCodec.json.encodeToJsonElement(MemoryDetail(it)) }
            is MemoryCommand.Delete -> dao.delete(ownerId, command.memoryId, command.version, now())?.let { BridgeCodec.json.encodeToJsonElement(it) }
        }
        if (data == null) CommandOutcome.Rejected(BridgeErrorCode.RESOURCE_UNAVAILABLE, "未找到记忆，请刷新")
        else CommandOutcome.AcceptedWithData(data.jsonObject)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: MemoryConflict) {
        CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "记忆已变化，请刷新后重新操作")
    } catch (_: IllegalArgumentException) {
        CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "记忆参数无效")
    } catch (_: Exception) {
        CommandOutcome.Rejected(BridgeErrorCode.INTERNAL_ERROR, "本机记忆操作失败，请重试")
    }
}
