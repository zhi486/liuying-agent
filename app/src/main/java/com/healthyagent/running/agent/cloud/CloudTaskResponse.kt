package com.healthyagent.running.agent.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class CloudTaskFailure(val code: String) : IllegalStateException("云任务操作失败：$code") {
    init { require(code in setOf("invalid_request", "unauthenticated", "not_found", "conflict", "busy", "inactive", "request_expired", "unavailable", "invalid_response", "network_unavailable", "network_timeout", "dns_unavailable", "tls_failed", "credential_unavailable", "storage_unavailable")) }
}

@Serializable
data class CloudTaskResponse(
    val id: String, val status: String, val reason: String?,
    val acceptedAtMillis: Long, val deadlineAtMillis: Long, val updatedAtMillis: Long,
    val resultState: String, val result: JsonObject?,
) {
    val terminal: Boolean get() = status != "queued" && status != "running"
    override fun toString() = "CloudTaskResponse(已脱敏)"

    fun validate(): CloudTaskResponse {
        require(CLOUD_UUID.matches(id))
        require(acceptedAtMillis in 0..9007199254710991L && deadlineAtMillis == acceptedAtMillis + 30000 &&
            updatedAtMillis in acceptedAtMillis..9007199254740991L)
        require(validStatus(status, reason))
        val allowedStates = when {
            !terminal -> setOf("none")
            reason == "consent_revoked" -> setOf("withdrawn")
            reason == "device_revoked" -> setOf("none", "withdrawn")
            status == "cancelled" || status == "timed_out" -> setOf("none", "available", "acknowledged", "expired", "withdrawn")
            else -> setOf("available", "acknowledged", "expired", "withdrawn")
        }
        require(resultState in allowedStates)
        if (resultState == "available") {
            val body = requireNotNull(result)
            val completed = status == "completed"
            require(body.keys == setOf("status", if (completed) "answer" else "reason", "actions") +
                (if (completed && body.containsKey("sources")) setOf("sources") else emptySet()) +
                (if (body.containsKey("cost")) setOf("cost") else emptySet()))
            if (body.containsKey("sources")) CloudKnowledge.sources(body.getValue("sources"))
            if (body.containsKey("cost")) CloudCostEstimate.validate(body.getValue("cost"))
            require(body.text("status") == status)
            if (completed) {
                val answer = body.text("answer")
                require(answer.length in 1..4000 && answer.any { it !in JS_WHITESPACE })
            } else require(body.text("reason") == reason && reason != "device_revoked" && reason != "consent_revoked")
            val actions = body["actions"] as? JsonArray ?: error("响应无效")
            require(actions.size <= 9)
            var step = 0
            var tools = 0
            actions.forEach { item ->
                val action = item as? JsonObject ?: error("响应无效")
                val model = action.text("kind") == "model_requested"
                require(action.keys == if (model) setOf("kind", "step") else setOf("kind", "step", "tool"))
                val value = action["step"] as? JsonPrimitive ?: error("响应无效")
                require(!value.isString)
                if (model) { require(value.intOrNull == step + 1 && step < 3); step++ }
                else {
                    require(action.text("kind") == "tool_completed" && step in 1..2 && value.intOrNull == step && ++tools <= 6)
                    require(action.text("tool") in setOf("runs.list", "runs.summary"))
                }
            }
        } else require(result == null)
        return this
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = false; isLenient = false }
        internal val CLOUD_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private const val JS_WHITESPACE = "\t\n\u000b\u000c\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
        internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("响应无效")
        private fun validStatus(status: String, reason: String?): Boolean = when (status) {
            "queued", "running", "completed" -> reason == null
            "blocked" -> reason in setOf("consent_required", "scope_denied", "duplicate_tool", "tool_limit", "step_limit", "budget_denied", "invalid_tool")
            "failed" -> reason in setOf("invalid_task", "invalid_model_output", "budget_unavailable", "model_unavailable", "usage_invalid", "usage_limit")
            "cancelled" -> reason in setOf("cancelled", "device_revoked", "consent_revoked")
            "timed_out" -> reason == "deadline"
            else -> false
        }
        fun parse(body: String): CloudTaskResponse = try {
            val envelope = json.parseToJsonElement(body).jsonObject
            require(envelope.keys == setOf("task"))
            val task = envelope.getValue("task").jsonObject
            require(task.keys == setOf("id", "status", "reason", "acceptedAtMillis", "deadlineAtMillis", "updatedAtMillis", "resultState", "result"))
            listOf("acceptedAtMillis", "deadlineAtMillis", "updatedAtMillis").forEach {
                val value = task.getValue(it).jsonPrimitive
                require(!value.isString && value.longOrNull != null)
            }
            json.decodeFromJsonElement<CloudTaskResponse>(task).validate()
        } catch (_: Exception) { throw CloudTaskFailure("invalid_response") }
    }
}
