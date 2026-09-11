package com.healthyagent.running.data.sensor

/** 运动就绪与预连接分开；此观察不控制 BLE 传输。 */
enum class WearableLinkPhase { IDLE, PREPARING, CONNECTING, READY, RECOVERING }

data class WearableLinkFault(
    val id: Long,
    val episodeId: Long,
    val startedAtMillis: Long,
)

/** 保留短暂 Ready 的修订号，避免状态流合并事件后丢失故障资格。 */
data class WearableLinkObservation(
    val generation: Long = 0,
    val phase: WearableLinkPhase = WearableLinkPhase.IDLE,
    val readyRevision: Long = 0,
    val readySinceMillis: Long? = null,
    val fault: WearableLinkFault? = null,
)
