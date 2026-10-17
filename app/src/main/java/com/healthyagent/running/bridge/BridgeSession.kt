package com.healthyagent.running.bridge

import com.healthyagent.running.coordinator.AppSnapshot
import com.healthyagent.running.coordinator.CommandOutcome
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 单个 WebView 文档的握手、命令去重和快照发送状态。 */
class BridgeSession(
    private val buildId: String,
    private val snapshotProvider: () -> AppSnapshot,
    private val commandHandler: suspend (name: String, body: JsonObject) -> CommandOutcome,
    private val send: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val stateLock = Any()
    private var activeHelloId: String? = null
    private var documentEpoch = 0L
    private val completedCommands = LinkedHashMap<String, CompletedCommand>()
    private val inFlightCommands = mutableMapOf<String, InFlightCommand>()

    suspend fun onMessage(raw: String) {
        val envelope = when (val result = BridgeCodec.decode(raw)) {
            is BridgeDecodeResult.Accepted -> result.envelope
            is BridgeDecodeResult.Rejected -> return
        }

        when (envelope.type) {
            BridgeMessageType.HELLO -> acceptHello(envelope)
            BridgeMessageType.COMMAND -> acceptCommand(envelope)
            else -> Unit
        }
    }

    fun pushSnapshot(snapshot: AppSnapshot = snapshotProvider()) {
        val epoch = synchronized(stateLock) {
            if (activeHelloId == null) return
            documentEpoch
        }
        sendEnvelopeForEpoch(
            epoch = epoch,
            type = BridgeMessageType.SNAPSHOT,
            payload = BridgeCodec.json.encodeToJsonElement(AppSnapshot.serializer(), snapshot).jsonObject,
        )
    }

    fun reset() {
        synchronized(stateLock) {
            activeHelloId = null
            documentEpoch++
        }
    }

    private fun acceptHello(envelope: BridgeEnvelope) {
        val epoch = synchronized(stateLock) {
            activeHelloId = envelope.id
            ++documentEpoch
        }
        val payload = buildJsonObject {
            put("helloId", envelope.id)
            put("buildId", buildId)
            put(
                "capabilities",
                BridgeCodec.json.parseToJsonElement(
                    BridgeCodec.json.encodeToString(
                        listOf("snapshot.v1", "history.pagination", "settings.v1", "heart-rate-alerts.v1", "data-quality.v1", "deepseek-companion.v1", "agent.tasks.v1", "memory.v1", "agent.cloud.tasks.v1"),
                    ),
                ),
            )
            put(
                "snapshot",
                BridgeCodec.json.encodeToJsonElement(
                    AppSnapshot.serializer(),
                    snapshotProvider(),
                ),
            )
        }
        sendEnvelopeForEpoch(epoch, BridgeMessageType.READY, payload)
    }

    private suspend fun acceptCommand(envelope: BridgeEnvelope) {
        val helloId = envelope.payload.getValue("helloId").jsonPrimitive.content
        val name = envelope.payload.getValue("name").jsonPrimitive.content
        val body = envelope.payload.getValue("body").jsonObject
        val fingerprint = CommandFingerprint(name, body)
        val reservation = synchronized(stateLock) {
            val epoch = documentEpoch
            completedCommands[envelope.id]?.let { completed ->
                return@synchronized if (completed.fingerprint == fingerprint) {
                    CommandReservation.Completed(completed.result, epoch)
                } else {
                    CommandReservation.Completed(
                        encodeResult(
                            CommandOutcome.Rejected(
                                BridgeErrorCode.INVALID_MESSAGE,
                                "同一命令 ID 不能对应不同内容",
                            ),
                            envelope.id,
                        ),
                        epoch,
                    )
                }
            }
            inFlightCommands[envelope.id]?.let { inFlight ->
                return@synchronized if (inFlight.fingerprint == fingerprint) {
                    CommandReservation.Follower(inFlight.result, epoch)
                } else {
                    CommandReservation.Completed(
                        encodeResult(
                            CommandOutcome.Rejected(
                                BridgeErrorCode.INVALID_MESSAGE,
                                "同一命令 ID 不能对应不同内容",
                            ),
                            envelope.id,
                        ),
                        epoch,
                    )
                }
            }

            if (helloId != activeHelloId) {
                val result = encodeResult(
                    CommandOutcome.Rejected(
                        BridgeErrorCode.COMMAND_NOT_ALLOWED,
                        "Bridge 尚未完成握手或页面已经失效",
                    ),
                    envelope.id,
                )
                rememberCompletedCommand(envelope.id, fingerprint, result)
                CommandReservation.Completed(result, epoch)
            } else {
                val result = CompletableDeferred<String>()
                inFlightCommands[envelope.id] = InFlightCommand(fingerprint, result)
                CommandReservation.Leader(result, epoch)
            }
        }

        when (reservation) {
            is CommandReservation.Completed -> sendForEpoch(reservation.epoch, reservation.result)
            is CommandReservation.Follower -> {
                val result = reservation.result.await()
                sendForEpoch(reservation.epoch, result)
            }
            is CommandReservation.Leader -> executeCommand(
                envelope = envelope,
                name = name,
                body = body,
                fingerprint = fingerprint,
                reservation = reservation,
            )
        }
    }

    private suspend fun executeCommand(
        envelope: BridgeEnvelope,
        name: String,
        body: JsonObject,
        fingerprint: CommandFingerprint,
        reservation: CommandReservation.Leader,
    ) {
        val outcome = try {
            commandHandler(name, body)
        } catch (cancellation: CancellationException) {
            synchronized(stateLock) {
                inFlightCommands.remove(envelope.id)
                reservation.result.completeExceptionally(cancellation)
            }
            throw cancellation
        } catch (_: Exception) {
            CommandOutcome.Rejected(BridgeErrorCode.INTERNAL_ERROR, "原生命令执行失败")
        }
        val encodedResult = encodeResult(outcome, envelope.id)
        synchronized(stateLock) {
            inFlightCommands.remove(envelope.id)
            rememberCompletedCommand(envelope.id, fingerprint, encodedResult)
            reservation.result.complete(encodedResult)
        }
        sendForEpoch(reservation.epoch, encodedResult)
    }

    private fun encodeResult(outcome: CommandOutcome, commandId: String): String {
        val payload = when (outcome) {
            CommandOutcome.Accepted -> buildJsonObject {
                put("status", "accepted")
                put("data", buildJsonObject { put("commandId", commandId) })
            }
            is CommandOutcome.AcceptedWithData -> buildJsonObject {
                put("status", "accepted")
                put(
                    "data",
                    buildJsonObject {
                        put("commandId", commandId)
                        outcome.data.forEach { (key, value) ->
                            if (key != "commandId") put(key, value)
                        }
                    },
                )
            }
            is CommandOutcome.Rejected -> buildJsonObject {
                put("status", "rejected")
                put(
                    "error",
                    buildJsonObject {
                        put("code", outcome.code.name)
                        put("message", outcome.message)
                    },
                )
            }
        }
        return encodeEnvelope(BridgeMessageType.RESULT, payload, id = commandId)
    }

    private fun sendEnvelopeForEpoch(epoch: Long, type: BridgeMessageType, payload: JsonObject) {
        sendForEpoch(epoch, encodeEnvelope(type, payload))
    }

    private fun sendForEpoch(epoch: Long, message: String) {
        val isCurrentDocument = synchronized(stateLock) {
            epoch == documentEpoch
        }
        if (isCurrentDocument) send(message)
    }

    private fun encodeEnvelope(
        type: BridgeMessageType,
        payload: JsonObject,
        id: String = newId(),
    ): String =
        BridgeCodec.encode(
            BridgeEnvelope(
                contractVersion = 1,
                type = type,
                id = id,
                sentAt = now().toString(),
                payload = payload,
            ),
        )

    private fun rememberCompletedCommand(
        commandId: String,
        fingerprint: CommandFingerprint,
        result: String,
    ) {
        completedCommands[commandId] = CompletedCommand(fingerprint, result)
        while (completedCommands.size > MAX_COMPLETED_COMMANDS) {
            completedCommands.remove(completedCommands.keys.first())
        }
    }

    private data class CommandFingerprint(val name: String, val body: JsonObject)

    private data class CompletedCommand(
        val fingerprint: CommandFingerprint,
        val result: String,
    )

    private data class InFlightCommand(
        val fingerprint: CommandFingerprint,
        val result: CompletableDeferred<String>,
    )

    private sealed interface CommandReservation {
        val epoch: Long

        data class Completed(val result: String, override val epoch: Long) : CommandReservation
        data class Follower(
            val result: CompletableDeferred<String>,
            override val epoch: Long,
        ) : CommandReservation
        data class Leader(
            val result: CompletableDeferred<String>,
            override val epoch: Long,
        ) : CommandReservation
    }

    companion object {
        private const val MAX_COMPLETED_COMMANDS = 128
    }
}
