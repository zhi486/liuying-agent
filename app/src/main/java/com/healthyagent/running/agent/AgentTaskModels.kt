package com.healthyagent.running.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentTaskSummary(
    val id: String,
    val title: String,
    val status: String,
    val reason: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
data class AgentMessage(val sequence: Int, val role: String, val text: String, val createdAtMillis: Long)

@Serializable
data class AgentAction(val sequence: Int, val kind: String, val createdAtMillis: Long)

@Serializable
data class AgentTaskDetail(val task: AgentTaskSummary, val messages: List<AgentMessage>, val actions: List<AgentAction>)

@Serializable
data class AgentTaskPage(val offset: Int, val limit: Int, val totalItems: Int, val items: List<AgentTaskSummary>)

class AgentRequestConflict : IllegalStateException("同一任务请求标识不能对应不同内容")
