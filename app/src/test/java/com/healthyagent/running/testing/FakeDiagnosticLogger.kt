package com.healthyagent.running.testing

import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CompletableDeferred

class FakeDiagnosticLogger(
    private val flushGate: CompletableDeferred<Unit>? = null,
) : DiagnosticLogger {
    private val lock = Any()
    private val storedEvents = mutableListOf<DiagnosticEvent>()
    private val storedTrace = mutableListOf<String>()
    var flushCalls: Int = 0
        private set
    var awaitIdleCalls: Int = 0
        private set

    val events: List<DiagnosticEvent>
        get() = synchronized(lock) { storedEvents.toList() }
    val trace: List<String>
        get() = synchronized(lock) { storedTrace.toList() }

    override fun record(event: DiagnosticEvent) {
        synchronized(lock) {
            storedEvents += event
            storedTrace += "record"
        }
    }

    override suspend fun awaitIdle() {
        synchronized(lock) {
            storedTrace += "awaitIdle"
            awaitIdleCalls += 1
        }
    }

    override suspend fun flush() {
        synchronized(lock) {
            storedTrace += "flush"
            flushCalls += 1
        }
        flushGate?.await()
    }
}
