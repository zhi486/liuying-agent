package com.healthyagent.running.diagnostics

interface DiagnosticLogger {
    fun record(event: DiagnosticEvent)
    suspend fun awaitIdle()
    suspend fun flush()
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun record(event: DiagnosticEvent) = Unit
    override suspend fun awaitIdle() = Unit
    override suspend fun flush() = Unit
}
