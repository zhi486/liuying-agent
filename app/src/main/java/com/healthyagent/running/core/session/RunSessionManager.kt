package com.healthyagent.running.core.session

import com.healthyagent.running.core.model.MetricsFrame
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.core.model.RoutePoint
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
    private var heartRateFrameCount = 0
    private var cadenceFrameCount = 0
    private var lastSplitElapsedSeconds = 0L
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

    /** 从本地检查点恢复未结束会话。 */
    fun restoreSession(session: RunSession) {
        require(session.runState == RunState.RUNNING || session.runState == RunState.PAUSED) {
            "只能恢复进行中或已暂停的会话"
        }

        reset()
        currentSession = session
        _currentState = session.runState
        lastSplitKm = session.splits.maxOfOrNull { it.index } ?: 0
        lastSplitElapsedSeconds = session.splits.sumOf { it.durationSec }

        // 管道每秒生成一帧。检查点只保存聚合值，恢复时以已用时近似帧数，
        // 让结束后的全程均值不会只统计恢复后的部分。
        val estimatedFrameCount = session.totalDurationSec.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        heartRateFrameCount = if (session.avgHeartRate == null) 0 else estimatedFrameCount
        cadenceFrameCount = if (session.avgCadence == null) 0 else estimatedFrameCount
        totalHrSum = (session.avgHeartRate?.toLong() ?: 0L) * heartRateFrameCount
        totalCadenceSum = (session.avgCadence?.toLong() ?: 0L) * cadenceFrameCount
    }

    /**
     * 处理一帧数据，返回新产生的 Split（跨公里时），否则返回 null
     */
    fun onFrame(frame: MetricsFrame): Split? {
        if (_currentState != RunState.RUNNING) return null

        val session = currentSession ?: return null
        // 累积指标用于计算平均
        frame.heartRateSmoothed?.let {
            totalHrSum += it
            heartRateFrameCount++
        }
        frame.cadence?.let {
            totalCadenceSum += it
            cadenceFrameCount++
        }
        currentSession = session.copy(
            totalDistanceMeters = frame.distanceMeters,
            totalDurationSec = maxOf(session.totalDurationSec, frame.elapsedSeconds),
            avgHeartRate = if (heartRateFrameCount > 0) {
                (totalHrSum / heartRateFrameCount).toInt()
            } else {
                null
            },
            avgCadence = if (cadenceFrameCount > 0) {
                (totalCadenceSum / cadenceFrameCount).toInt()
            } else {
                null
            },
        )

        // 追加到分段缓冲区
        splitBuffer.add(frame)

        // 检测跨公里边界
        val currentKm = (frame.distanceMeters / 1000.0).toInt()
        if (currentKm <= lastSplitKm) return null

        val split = buildSplit(currentKm, frame.elapsedSeconds)
        lastSplitKm = currentKm
        lastSplitElapsedSeconds = maxOf(lastSplitElapsedSeconds, frame.elapsedSeconds)
        splitBuffer.clear()

        // 追加到会话的分段列表
        val updatedSplits = currentSession!!.splits + split
        currentSession = currentSession!!.copy(splits = updatedSplits)
        return split
    }

    fun onRoutePoint(point: RoutePoint) {
        if (_currentState != RunState.RUNNING) return
        currentSession = currentSession?.let { session ->
            session.copy(routePoints = session.routePoints + point)
        }
    }

    /**
     * 在没有指标变化时，仅推进当前跑步的活动用时。
     *
     * 调用方应传入已经排除暂停区间的 elapsed；该方法不会让用时倒退，也不会在
     * 暂停或结束状态下偷偷累计。
     */
    fun advanceElapsedTo(elapsedSeconds: Long) {
        if (_currentState != RunState.RUNNING) return
        currentSession = currentSession?.let { session ->
            session.copy(totalDurationSec = maxOf(session.totalDurationSec, elapsedSeconds))
        }
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
            avgHeartRate = if (heartRateFrameCount > 0) {
                (totalHrSum / heartRateFrameCount).toInt()
            } else {
                null
            },
            avgCadence = if (cadenceFrameCount > 0) {
                (totalCadenceSum / cadenceFrameCount).toInt()
            } else {
                null
            },
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
        heartRateFrameCount = 0
        cadenceFrameCount = 0
        lastSplitElapsedSeconds = 0L
        _currentState = RunState.IDLE
    }

    /**
     * 从分段缓冲区的帧中构建一个 Split
     */
    private fun buildSplit(km: Int, boundaryElapsedSeconds: Long): Split {
        val frames = splitBuffer.toList()
        val durationSec = (boundaryElapsedSeconds - lastSplitElapsedSeconds).coerceAtLeast(0L)

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
