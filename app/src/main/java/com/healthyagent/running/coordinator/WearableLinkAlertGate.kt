package com.healthyagent.running.coordinator

import com.healthyagent.running.data.sensor.WearableLinkFault
import com.healthyagent.running.data.sensor.WearableLinkObservation
import com.healthyagent.running.data.sensor.WearableLinkPhase

/** 接纳即消耗本 episode，不依据播放成功与否补发；时间由调用方注入。 */
internal class WearableLinkAlertGate {
    private var generation = -1L
    private var baselineReadyRevision = 0L
    private var submittedEpisodeId: Long? = null

    fun reset(baseline: WearableLinkObservation) {
        generation = baseline.generation
        baselineReadyRevision = if (baseline.phase == WearableLinkPhase.READY) {
            (baseline.readyRevision - 1).coerceAtLeast(0)
        } else baseline.readyRevision
        submittedEpisodeId = null
    }

    fun evaluate(observation: WearableLinkObservation, nowMillis: Long): WearableLinkFault? {
        if (observation.generation < generation) return null
        if (observation.generation > generation) {
            generation = observation.generation
            baselineReadyRevision = 0
            submittedEpisodeId = null
        }
        val fault = observation.fault ?: return null
        if (observation.phase != WearableLinkPhase.RECOVERING ||
            observation.readyRevision <= baselineReadyRevision ||
            nowMillis - fault.startedAtMillis < 10_000L ||
            submittedEpisodeId == fault.episodeId
        ) return null
        submittedEpisodeId = fault.episodeId
        return fault
    }
}
