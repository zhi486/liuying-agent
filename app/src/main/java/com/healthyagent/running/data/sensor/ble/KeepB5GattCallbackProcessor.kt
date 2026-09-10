package com.healthyagent.running.data.sensor.ble

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class KeepB5GattCallbackProcessor(
    private val mapper: KeepB5GattCallbackMapper,
    private val executor: KeepB5GattDecisionExecutor,
    private val registrationGate: KeepB5GattRegistrationGate,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) {
    private val closed = AtomicBoolean(false)
    private val callbacks = Channel<RawGattCallbackEvent>(Channel.UNLIMITED)
    private val actor: Job = scope.launch(dispatcher) {
        if (!registrationGate.awaitActivation() || !registrationGate.isActive()) return@launch
        executor.initialize()
        if (!registrationGate.isActive()) return@launch
        for (callback in callbacks) {
            if (!registrationGate.isActive()) break
            val decisions = mapper.mapCallback(callback)
            for (decision in decisions) {
                if (!registrationGate.isActive()) break
                executor.execute(decision)
            }
        }
    }

    fun offer(event: RawGattCallbackEvent): Boolean {
        if (closed.get()) return false
        return callbacks.trySend(event).isSuccess
    }

    fun shutdown(): Job {
        if (closed.compareAndSet(false, true)) {
            registrationGate.close()
            callbacks.close()
            actor.cancel()
        }
        return actor
    }
}
