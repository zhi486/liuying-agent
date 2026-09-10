package com.healthyagent.running.ui.running

import com.healthyagent.running.core.math.MetricsCalculator
import com.healthyagent.running.coordinator.MetricsSnapshot
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.MetricsFrame
import java.util.Locale

/**
 * 跑步页展示就绪状态。
 *
 * 所有格式化都在 UI 边界之外完成，Composable 只负责布局和颜色语义。
 */
data class RunningDisplayState(
    val heartRateText: String = "--",
    val paceText: String = "--",
    val cadenceText: String = "--",
    val strideLengthText: String = "--",
    val distanceText: String = "0.00",
    val elapsedText: String = "00:00:00",
    val zoneLabel: String = "--",
    val hrZone: HrZone? = null,
    val isDataFresh: Boolean? = null,
) {
    companion object {
        fun from(frame: MetricsFrame): RunningDisplayState = RunningDisplayState(
            heartRateText = (frame.heartRateSmoothed ?: frame.heartRate)?.toString() ?: "--",
            paceText = frame.currentPaceSecPerKm
                ?.let(MetricsCalculator::formatPace)
                ?: "--",
            cadenceText = frame.cadence?.toString() ?: "--",
            strideLengthText = frame.strideLengthCm
                ?.let { String.format(Locale.US, "%.0f", it) }
                ?: "--",
            distanceText = String.format(Locale.US, "%.2f", frame.distanceMeters / 1000.0),
            elapsedText = formatElapsed(frame.elapsedSeconds),
            zoneLabel = frame.hrZone.label,
            hrZone = frame.hrZone,
            isDataFresh = frame.isDataFresh,
        )

        fun from(metrics: MetricsSnapshot): RunningDisplayState = RunningDisplayState(
            heartRateText = metrics.heartRate?.toString() ?: "--",
            paceText = metrics.paceSecPerKm?.let(MetricsCalculator::formatPace) ?: "--",
            cadenceText = metrics.cadence?.toString() ?: "--",
            strideLengthText = metrics.strideLengthCm
                ?.let { String.format(Locale.US, "%.0f", it) }
                ?: "--",
            distanceText = String.format(Locale.US, "%.2f", metrics.distanceMeters / 1000.0),
            elapsedText = formatElapsed(metrics.elapsedSeconds.toLong()),
            zoneLabel = metrics.hrZone?.uppercase() ?: "--",
            hrZone = metrics.hrZone?.uppercase()?.let { value ->
                HrZone.entries.firstOrNull { it.name == value }
            },
            isDataFresh = metrics.isDataFresh,
        )
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
