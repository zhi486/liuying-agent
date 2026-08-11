package com.healthyagent.running.core.model

/**
 * 跑步会话状态机
 */
enum class RunState {
    IDLE,       // 未开始
    PREPARING,  // 准备中（等待传感器就绪）
    RUNNING,    // 跑步中
    PAUSED,     // 已暂停
    FINISHED,   // 已结束
    ERROR       // 异常
}
