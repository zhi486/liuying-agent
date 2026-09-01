package com.healthyagent.running.core.session

import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.Split
import java.util.UUID

/**
 * 跑步会话状态机 — 管理当前跑步会话的创建、逐帧累积、分段检测和结束
 *
 * 纯 Kotlin，零 Android 依赖，JVM 可直接单测。
 * 不负责持久化（持久化由 Repository 层处理）。
 */
class RunSessionManager {

    /** 当前会话对象（null 表示未开始） */
    var currentSession: RunSession? = null
        private set
    private val splitBuffer = mutableListOf<MetricsFrame>()
    private var lastSplitKm = 0
    private var totalHrSum = 0L
    private var totalCadenceSum = 0L
    private var frameCount = 0
    private var _currentState = RunState.IDLE

    /** 当前会话状态 */
    val currentState: RunState get() = _currentState

    /** 当前会话 ID（null 表示未开始） */
    val currentSessionId: String? get() = currentSession?.id

    /** 已完成的分段列表 */
    val completedSplits: List<Split> get() = currentSession?.splits ?: emptyList()

    /**
     * 开始新会话
     */
    fun startSession(config: RunConfig): RunSession {
        reset()
        val session = RunSession(
            id = UUID.randomUUID().toString(),
            startTimeMillis = System.currentTimeMillis(),
            runState = RunState.RUNNING,
        )
        currentSession = session
        _currentState = RunState.RUNNING
        return session
    }

    /**
     * 处理一帧数据，返回新产生的 Split（跨公里时），否则返回 null
     */
    fun onFrame(frame: MetricsFrame): Split? {
        if (_currentState != RunState.RUNNING) return null

        val session = currentSession ?: return null
        currentSession = session.copy(
            totalDistanceMeters = frame.distanceMeters,
            totalDurationSec = frame.elapsedSeconds,
        )

        // 累积指标用于计算平均
        frame.heartRateSmoothed?.let { totalHrSum += it }
        frame.cadence?.let { totalCadenceSum += it }
        frameCount++

        // 追加到分段缓冲区
        splitBuffer.add(frame)

        // 检测跨公里边界
        val currentKm = (frame.distanceMeters / 1000.0).toInt()
        if (currentKm <= lastSplitKm) return null

        val split = buildSplit(currentKm)
        lastSplitKm = currentKm
        splitBuffer.clear()

        // 追加到会话的分段列表
        val updatedSplits = currentSession!!.splits + split
        currentSession = currentSession!!.copy(splits = updatedSplits)
        return split
    }

    /** 暂停 */
    fun pause() {
        if (_currentState == RunState.RUNNING) {
            _currentState = RunState.PAUSED
            currentSession = currentSession?.copy(runState = RunState.PAUSED)
        }
    }

    /** 恢复 */
    fun resume() {
        if (_currentState == RunState.PAUSED) {
            _currentState = RunState.RUNNING
            currentSession = currentSession?.copy(runState = RunState.RUNNING)
        }
    }

    /**
     * 结束会话，返回完整的 RunSession（含所有 split 和平均指标）
     */
    fun finishSession(): RunSession? {
        _currentState = RunState.FINISHED
        val session = currentSession ?: return null

        val finalized = session.copy(
            endTimeMillis = System.currentTimeMillis(),
            avgHeartRate = if (frameCount > 0) (totalHrSum / frameCount).toInt() else null,
            avgCadence = if (frameCount > 0) (totalCadenceSum / frameCount).toInt() else null,
            avgPaceSecPerKm = if (session.totalDistanceMeters > 0 && session.totalDurationSec > 0) {
                session.totalDurationSec / (session.totalDistanceMeters / 1000.0)
            } else null,
            runState = RunState.FINISHED,
        )
        currentSession = finalized
        return finalized
    }

    /** 重置（为新会话做准备） */
    fun reset() {
        currentSession = null
        splitBuffer.clear()
        lastSplitKm = 0
        totalHrSum = 0
        totalCadenceSum = 0
        frameCount = 0
        _currentState = RunState.IDLE
    }

    /**
     * 从分段缓冲区的帧中构建一个 Split
     */
    private fun buildSplit(km: Int): Split {
        val frames = splitBuffer.toList()
        val durationSec = if (frames.size >= 2) {
            (frames.last().timestampMillis - frames.first().timestampMillis) / 1000
        } else 0L

        val hrValues = frames.mapNotNull { it.heartRateSmoothed }
        val cadenceValues = frames.mapNotNull { it.cadence }

        val splitDistance = km * 1000.0
        val splitPace = if (durationSec > 0) durationSec.toDouble() else 0.0

        return Split(
            index = km,
            distanceMeters = splitDistance,
            durationSec = durationSec,
            avgHeartRate = if (hrValues.isNotEmpty()) hrValues.average().toInt() else null,
            avgCadence = if (cadenceValues.isNotEmpty()) cadenceValues.average().toInt() else null,
            avgPaceSecPerKm = splitPace,
            hrZoneDistribution = null,
        )
    }
}
