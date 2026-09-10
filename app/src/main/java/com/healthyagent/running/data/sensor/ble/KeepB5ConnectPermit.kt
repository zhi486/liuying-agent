package com.healthyagent.running.data.sensor.ble

import java.util.concurrent.atomic.AtomicReference

internal class KeepB5ConnectPermit {
    private val state = AtomicReference(State(Phase.PENDING, cancellationRequested = false))

    fun tryStart(block: () -> Unit): Boolean {
        while (true) {
            val current = state.get()
            if (current.phase != Phase.PENDING) return false
            if (state.compareAndSet(current, State(Phase.STARTED, cancellationRequested = false))) break
        }

        try {
            block()
        } finally {
            complete()
        }
        return true
    }

    fun cancel() {
        while (true) {
            val current = state.get()
            val cancelled = when (current.phase) {
                Phase.PENDING -> State(Phase.CANCELLED, cancellationRequested = true)
                Phase.STARTED -> State(Phase.CANCEL_REQUESTED, cancellationRequested = true)
                Phase.COMPLETED -> current.copy(cancellationRequested = true)
                Phase.CANCEL_REQUESTED,
                Phase.CANCELLED,
                -> return
            }
            if (state.compareAndSet(current, cancelled)) return
        }
    }

    fun isCancellationRequested(): Boolean = state.get().cancellationRequested

    private fun complete() {
        while (true) {
            val current = state.get()
            if (current.phase == Phase.COMPLETED) return
            val completed = State(Phase.COMPLETED, current.cancellationRequested)
            if (state.compareAndSet(current, completed)) return
        }
    }

    private data class State(
        val phase: Phase,
        val cancellationRequested: Boolean,
    )

    private enum class Phase {
        PENDING,
        STARTED,
        CANCEL_REQUESTED,
        CANCELLED,
        COMPLETED,
    }
}
