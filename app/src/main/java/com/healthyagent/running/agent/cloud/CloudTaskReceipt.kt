package com.healthyagent.running.agent.cloud

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 云端清除状态与本机已收取正文分离；ack/expired 不删除已保存正文。 */
@Entity(tableName = "agent_cloud_receipts",
    foreignKeys = [ForeignKey(entity = CloudTaskIntent::class, parentColumns = ["clientRequestId"], childColumns = ["clientRequestId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["cloudTaskId"], unique = true)])
data class CloudTaskReceipt(
    @PrimaryKey val clientRequestId: String,
    val cloudTaskId: String, val status: String, val reason: String?,
    val acceptedAtMillis: Long, val deadlineAtMillis: Long, val updatedAtMillis: Long,
    val resultState: String, val resultJson: String?, val ackPending: Boolean, val confirmedControl: String?,
) {
    override fun toString() = "CloudTaskReceipt(已脱敏)"

    companion object {
        /** 时间戳不是版本号：同毫秒状态推进也要接纳，清除和终态不能被迟到响应回退。 */
        fun merge(intent: CloudTaskIntent, previous: CloudTaskReceipt?, incoming: CloudTaskResponse): CloudTaskReceipt {
            incoming.validate()
            require(previous == null || (previous.clientRequestId == intent.clientRequestId && previous.cloudTaskId == incoming.id &&
                previous.acceptedAtMillis == incoming.acceptedAtMillis && previous.deadlineAtMillis == incoming.deadlineAtMillis)) { "云任务关联不一致" }
            require(previous == null || previous.status in setOf("queued", "running") || !incoming.terminal ||
                (previous.status == incoming.status && previous.reason == incoming.reason)) { "云任务终态冲突" }
            val stale = previous != null && incoming.updatedAtMillis < previous.updatedAtMillis
            val keepStatus = previous != null && (stale || previous.status !in setOf("queued", "running") || (previous.status == "running" && incoming.status == "queued"))
            val status = if (keepStatus) previous!!.status else incoming.status
            val reason = if (keepStatus) previous!!.reason else incoming.reason
            val closed = setOf("acknowledged", "expired", "withdrawn")
            val state = when {
                previous?.resultState == "withdrawn" || incoming.resultState == "withdrawn" -> "withdrawn"
                previous?.resultState in closed -> previous!!.resultState
                stale -> previous!!.resultState
                previous?.resultState == "available" && incoming.resultState == "none" -> "available"
                else -> incoming.resultState
            }
            var result = previous?.resultJson
            if (intent.pendingControl == "withdraw" || state == "withdrawn") result = null
            else if (!stale && state == "available" && incoming.result != null && status == incoming.status && reason == incoming.reason) {
                val value = incoming.result.toString()
                require(result == null || CloudTaskResponse.json.parseToJsonElement(result) == incoming.result) { "云结果内容冲突" }
                result = value
            }
            val confirmed = when {
                previous?.confirmedControl == "withdraw" -> "withdraw"
                intent.pendingControl == "withdraw" && incoming.resultState == "withdrawn" -> "withdraw"
                intent.pendingControl == "cancel" && incoming.terminal -> "cancel"
                else -> previous?.confirmedControl
            }
            return CloudTaskReceipt(intent.clientRequestId, incoming.id, status, reason,
                incoming.acceptedAtMillis, incoming.deadlineAtMillis, maxOf(incoming.updatedAtMillis, previous?.updatedAtMillis ?: 0),
                state, result, result != null && state == "available" && intent.pendingControl != "withdraw", confirmed)
        }
    }
}
