package com.healthyagent.running.data.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.RunSession
import java.time.Instant
import java.time.ZoneId

internal sealed interface RunHealthRecordMapping {
    data class Valid(
        val records: List<Record>,
        val routeWritten: Boolean,
    ) : RunHealthRecordMapping

    data class Invalid(
        val reason: HealthConnectSyncResult.InvalidRunReason,
    ) : RunHealthRecordMapping
}

/** 将本地完成会话转换成 Health Connect 1.1.0 记录，不读取任何 Android 状态。 */
internal class RunHealthRecordMapper(
    private val device: Device,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun map(session: RunSession, includeRoute: Boolean): RunHealthRecordMapping {
        val endTimeMillis = session.endTimeMillis
            ?: return RunHealthRecordMapping.Invalid(
                HealthConnectSyncResult.InvalidRunReason.NOT_FINISHED,
            )
        if (session.startTimeMillis <= 0L || endTimeMillis <= session.startTimeMillis) {
            return RunHealthRecordMapping.Invalid(
                HealthConnectSyncResult.InvalidRunReason.INVALID_TIME_RANGE,
            )
        }
        if (!session.totalDistanceMeters.isFinite() ||
            session.totalDistanceMeters !in 0.0..MAX_DISTANCE_METERS
        ) {
            return RunHealthRecordMapping.Invalid(
                HealthConnectSyncResult.InvalidRunReason.INVALID_DISTANCE,
            )
        }

        val startTime = Instant.ofEpochMilli(session.startTimeMillis)
        val endTime = Instant.ofEpochMilli(endTimeMillis)
        val route = if (includeRoute) {
            buildRoute(
                routePoints = session.routePoints,
                startTimeMillis = session.startTimeMillis,
                endTimeMillis = endTimeMillis,
            )
        } else {
            null
        }
        val routeWritten = route != null
        val sessionVersion = if (routeWritten) ROUTE_RECORD_VERSION else BASE_RECORD_VERSION

        return try {
            val exerciseRecord = ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                metadata = Metadata.activelyRecorded(
                    device = device,
                    clientRecordId = "liuying.exercise.${session.id}",
                    clientRecordVersion = sessionVersion,
                ),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = "流萤跑步",
                exerciseRoute = route,
            )
            val distanceRecord = DistanceRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                distance = Length.meters(session.totalDistanceMeters),
                metadata = Metadata.activelyRecorded(
                    device = device,
                    clientRecordId = "liuying.distance.${session.id}",
                    clientRecordVersion = BASE_RECORD_VERSION,
                ),
            )
            RunHealthRecordMapping.Valid(
                records = listOf(exerciseRecord, distanceRecord),
                routeWritten = routeWritten,
            )
        } catch (_: IllegalArgumentException) {
            RunHealthRecordMapping.Invalid(
                HealthConnectSyncResult.InvalidRunReason.INVALID_RECORD_DATA,
            )
        }
    }

    private fun buildRoute(
        routePoints: List<RoutePoint>,
        startTimeMillis: Long,
        endTimeMillis: Long,
    ): ExerciseRoute? {
        val locations = routePoints.asSequence()
            .filter { point -> point.isValidFor(startTimeMillis, endTimeMillis) }
            .sortedBy(RoutePoint::timestampMillis)
            .distinctBy(RoutePoint::timestampMillis)
            .map { point ->
                ExerciseRoute.Location(
                    time = Instant.ofEpochMilli(point.timestampMillis),
                    latitude = point.latitude,
                    longitude = point.longitude,
                    horizontalAccuracy = Length.meters(point.accuracyMeters),
                    altitude = point.altitudeMeters
                        ?.takeIf(Double::isFinite)
                        ?.let(Length::meters),
                )
            }
            .toList()

        return locations.takeIf { it.size >= MIN_ROUTE_POINT_COUNT }?.let(::ExerciseRoute)
    }

    private fun RoutePoint.isValidFor(startTimeMillis: Long, endTimeMillis: Long): Boolean {
        return timestampMillis in startTimeMillis until endTimeMillis &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters >= 0.0
    }

    private companion object {
        const val BASE_RECORD_VERSION = 1L
        const val ROUTE_RECORD_VERSION = 2L
        const val MAX_DISTANCE_METERS = 1_000_000.0
        const val MIN_ROUTE_POINT_COUNT = 2
    }
}
