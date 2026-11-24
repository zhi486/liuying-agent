package com.healthyagent.running.agent.cloud

import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.json.*

/** 只使用固定分类，不携带异常正文、地址、身份或聊天内容。 */
internal object CloudTaskDiagnostics {
    /** 只分类已编码的本机结果；不代表云端已接收，也不输出任意返回字段。 */
    fun memoryToolCode(observation: JsonObject): String {
        fun text(key: String) = (observation[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (text("status")) {
            "found" -> "found"
            "saved" -> "saved"
            "rejected" -> when (text("reason")) {
                "consent_required" -> "rejected_consent_required"
                "scope_denied" -> "rejected_scope_denied"
                "task_inactive" -> "rejected_task_inactive"
                "invalid_tool" -> "rejected_invalid_tool"
                "evidence_required" -> "rejected_evidence_required"
                "conflict" -> "rejected_conflict"
                "sensitive_content" -> "rejected_sensitive_content"
                else -> "unconfirmed_invalid_response"
            }
            "unconfirmed" -> if (text("reason") == "unavailable") "unconfirmed_unavailable" else "unconfirmed_invalid_response"
            else -> "unconfirmed_invalid_response"
        }
    }

    fun networkCode(error: IOException): String = when (error) {
        is UnknownHostException -> "dns_unavailable"
        is SSLException -> "tls_failed"
        is InterruptedIOException -> "network_timeout"
        else -> "network_unavailable"
    }

    fun message(code: String): String = if (code == "memory_changed")
        "引用的记忆已修改、删除或失效，本次没有提交。请重新核对问题和上下文；不会自动重发。"
    else when (code) {
        "dns_unavailable" -> "无法解析在线服务地址，请检查网络或 VPN。"
        "tls_failed" -> "安全连接失败，请检查设备时间、网络或 VPN。"
        "network_timeout" -> "连接或读取在线服务超时。"
        "network_unavailable" -> "在线服务连接中断或不可达。"
        "unauthenticated", "credential_unavailable" -> "在线 Agent 身份配置无效或不可用，请检查 Agent 设置。"
        "invalid_response" -> "收到的云端响应格式不符合约定，需检查服务版本或网络返回内容。"
        "storage_unavailable" -> "本机保存或读取任务失败，尚未确认结果。"
        "not_found" -> "云端未找到原任务，不能据此确认之前是否执行。"
        "invalid_request" -> "云端拒绝了请求格式，请检查客户端与服务版本。"
        "busy" -> "云端正在处理其他任务，请稍后查询。"
        "conflict", "inactive", "request_expired" -> "原任务冲突、失效或已过期，请检查任务状态。"
        "unavailable" -> "云端服务暂不可用。"
        "poll_exhausted", "deadline" -> "本轮查询已结束，尚未拿到最终结果。"
        else -> "在线操作异常，尚未确认云端状态。"
    } + "可恢复查询原任务；不会自动重发问题。"
}
