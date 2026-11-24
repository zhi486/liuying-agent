package com.healthyagent.running.agent.mcp

import com.healthyagent.running.agent.cloud.CloudTaskFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只供原生确认界面使用；不把网页布尔值或模型意图当作批准。 */
class McpTaskConfirmation {
    class Request internal constructor(val invocation: McpTaskInvocation) {
        internal val result = CompletableDeferred<Boolean>()
        override fun toString() = "McpTaskConfirmation(参数已隐藏)"
    }
    private val lock = Any()
    private val pending = MutableStateFlow<Request?>(null)
    val requesting = pending.asStateFlow()

    internal suspend fun ask(invocation: McpTaskInvocation): Boolean {
        val request = Request(invocation)
        synchronized(lock) {
            if (pending.value != null) throw CloudTaskFailure("busy")
            pending.value = request
        }
        return try { request.result.await() } finally {
            synchronized(lock) { if (pending.value === request) pending.value = null }
        }
    }

    fun respond(request: Request, approved: Boolean) = synchronized(lock) {
        if (pending.value === request) request.result.complete(approved) else false
    }
}
