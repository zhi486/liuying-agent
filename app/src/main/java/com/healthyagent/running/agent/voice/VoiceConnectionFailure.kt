package com.healthyagent.running.agent.voice

import java.io.IOException

/** 只保留握手状态码，不向网页传递响应正文、凭据或底层异常信息。 */
class VoiceConnectionFailure(val httpStatus: Int?) : IOException("语音连接失败") {
    val userMessage: String
        get() {
            val explanation = when (httpStatus) {
                401 -> "语音连接认证失败（401），请核对在线 Agent 设备配置"
                403 -> "语音连接被拒绝（403），请核对设备授权与服务访问设置"
                404 -> "未找到语音接口（404），请核对服务地址及后端版本"
                429 -> "语音服务请求过多（429），请稍后再试"
                in 500..599 -> "语音服务暂不可用（$httpStatus），请稍后再试"
                null -> "语音连接未建立或已中断，请检查网络与 VPN"
                else -> "语音连接未被接受（$httpStatus），请检查服务配置"
            }
            return "$explanation；不会自动重发"
        }
}
