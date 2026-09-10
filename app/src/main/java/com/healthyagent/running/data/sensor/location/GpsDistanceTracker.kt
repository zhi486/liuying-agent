package com.healthyagent.running.data.sensor.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 不依赖 Android 的 GPS 距离累计器。
 *
 * 只有通过精度、时间顺序和合理速度检查的定位点，才会成为下一次计算的基准点。
 * 因此单个 GPS 跳点被丢弃后，不会继续污染后续距离。
 */
class GpsDistanceTracker(
    private val maxAccuracyMeters: Double = DEFAULT_MAX_ACCURACY_METERS,
    private val maxPlausibleSpeedMetersPerSecond: Double = DEFAULT_MAX_PLAUSIBLE_SPEED_MPS,
) {
    private var previousFix: GpsFix? = null
    private var totalDistanceMeters = 0.0

    @Synchronized
    fun update(fix: GpsFix): GpsDistanceUpdate? {
        if (!fix.isUsable(maxAccuracyMeters, maxPlausibleSpeedMetersPerSecond)) {
            return null
        }

        val previous = previousFix
        if (previous == null) {
            previousFix = fix
            return GpsDistanceUpdate(
                totalDistanceMeters = 0.0,
                speedMetersPerSecond = 0.0,
                segmentDistanceMeters = 0.0,
            )
        }

        if (fix.timestampMillis <= previous.timestampMillis) {
            return null
        }

        val elapsedSeconds = (fix.timestampMillis - previous.timestampMillis) / 1_000.0
        val segmentDistanceMeters = haversineDistanceMeters(previous, fix)
        val calculatedSpeedMetersPerSecond = segmentDistanceMeters / elapsedSeconds

        if (!calculatedSpeedMetersPerSecond.isFinite() ||
            calculatedSpeedMetersPerSecond > maxPlausibleSpeedMetersPerSecond
        ) {
            return null
        }

        totalDistanceMeters += segmentDistanceMeters
        previousFix = fix

        return GpsDistanceUpdate(
            totalDistanceMeters = totalDistanceMeters,
            speedMetersPerSecond = calculatedSpeedMetersPerSecond,
            segmentDistanceMeters = segmentDistanceMeters,
        )
    }

    @Synchronized
    fun reset() {
        previousFix = null
        totalDistanceMeters = 0.0
    }

    private fun GpsFix.isUsable(maxAccuracy: Double, maxSpeed: Double): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return false
        if (!accuracyMeters.isFinite() || accuracyMeters < 0.0 || accuracyMeters > maxAccuracy) {
            return false
        }

        return reportedSpeedMetersPerSecond?.let { speed ->
            speed.isFinite() && speed >= 0.0 && speed <= maxSpeed
        } ?: true
    }

    private fun haversineDistanceMeters(from: GpsFix, to: GpsFix): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = toLatitude - fromLatitude
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)

        val haversine = sin(latitudeDelta / 2).let { it * it } +
            cos(fromLatitude) * cos(toLatitude) *
            sin(longitudeDelta / 2).let { it * it }
        val angularDistance = 2 * atan2(sqrt(haversine), sqrt(1 - haversine))

        return EARTH_RADIUS_METERS * angularDistance
    }

    companion object {
        const val DEFAULT_MAX_ACCURACY_METERS = 40.0
        const val DEFAULT_MAX_PLAUSIBLE_SPEED_MPS = 12.0

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timestampMillis: Long,
    val reportedSpeedMetersPerSecond: Double? = null,
)

data class GpsDistanceUpdate(
    val totalDistanceMeters: Double,
    val speedMetersPerSecond: Double,
    val segmentDistanceMeters: Double,
)
