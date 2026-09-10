package com.healthyagent.running.data.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.RunSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class RunHealthRecordMapperTest {
    private val mapper = RunHealthRecordMapper(
        device = Device(Device.TYPE_PHONE, manufacturer = "Test", model = "Phone"),
        zoneId = ZoneOffset.UTC,
    )

    @Test
    fun `基础映射生成跑步和距离并使用稳定客户端记录 ID`() {
        val mapping = mapper.map(finishedSession(), includeRoute = false)

        assertTrue(mapping is RunHealthRecordMapping.Valid)
        mapping as RunHealthRecordMapping.Valid
        assertFalse(mapping.routeWritten)
        assertEquals(2, mapping.records.size)

        val exercise = mapping.records[0] as ExerciseSessionRecord
        val distance = mapping.records[1] as DistanceRecord
        assertTrue(exercise.exerciseRouteResult is ExerciseRouteResult.NoData)
        assertEquals("liuying.exercise.run-1", exercise.metadata.clientRecordId)
        assertEquals(1L, exercise.metadata.clientRecordVersion)
        assertEquals("liuying.distance.run-1", distance.metadata.clientRecordId)
        assertEquals(1L, distance.metadata.clientRecordVersion)
        assertEquals(1_234.5, distance.distance.inMeters, 0.0)
    }

    @Test
    fun `有路线权限时只保留会话范围内且合法的唯一时间点`() {
        val session = finishedSession().copy(
            routePoints = listOf(
                routePoint(timestampMillis = 1_500L, latitude = 31.2),
                routePoint(timestampMillis = 1_200L, latitude = 31.1),
                routePoint(timestampMillis = 1_200L, latitude = 31.9),
                routePoint(timestampMillis = 2_000L, latitude = 31.3),
                routePoint(timestampMillis = 1_700L, latitude = 100.0),
            ),
        )

        val mapping = mapper.map(session, includeRoute = true) as RunHealthRecordMapping.Valid

        assertTrue(mapping.routeWritten)
        val exercise = mapping.records[0] as ExerciseSessionRecord
        val route = (exercise.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route
        assertEquals(listOf(1_200L, 1_500L), route.map { it.time.toEpochMilli() })
        assertEquals(listOf(31.1, 31.2), route.map { it.latitude })
        assertEquals(2L, exercise.metadata.clientRecordVersion)
    }

    @Test
    fun `没有路线权限时不会把路线放入会话记录`() {
        val session = finishedSession().copy(
            routePoints = listOf(
                routePoint(timestampMillis = 1_200L),
                routePoint(timestampMillis = 1_500L),
            ),
        )

        val mapping = mapper.map(session, includeRoute = false) as RunHealthRecordMapping.Valid

        assertFalse(mapping.routeWritten)
        val exercise = mapping.records[0] as ExerciseSessionRecord
        assertTrue(exercise.exerciseRouteResult is ExerciseRouteResult.NoData)
    }

    @Test
    fun `少于两个合法路线点时安全降级为无路线`() {
        val session = finishedSession().copy(
            routePoints = listOf(routePoint(timestampMillis = 1_200L)),
        )

        val mapping = mapper.map(session, includeRoute = true) as RunHealthRecordMapping.Valid

        assertFalse(mapping.routeWritten)
    }

    @Test
    fun `未完成与非法距离返回明确映射错误`() {
        val unfinished = mapper.map(
            finishedSession().copy(endTimeMillis = null),
            includeRoute = true,
        )
        val invalidDistance = mapper.map(
            finishedSession().copy(totalDistanceMeters = Double.NaN),
            includeRoute = true,
        )

        assertEquals(
            HealthConnectSyncResult.InvalidRunReason.NOT_FINISHED,
            (unfinished as RunHealthRecordMapping.Invalid).reason,
        )
        assertEquals(
            HealthConnectSyncResult.InvalidRunReason.INVALID_DISTANCE,
            (invalidDistance as RunHealthRecordMapping.Invalid).reason,
        )
    }

    private fun finishedSession(): RunSession {
        return RunSession(
            id = "run-1",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            totalDistanceMeters = 1_234.5,
            totalDurationSec = 1L,
        )
    }

    private fun routePoint(
        timestampMillis: Long,
        latitude: Double = 31.0,
    ): RoutePoint {
        return RoutePoint(
            latitude = latitude,
            longitude = 121.0,
            timestampMillis = timestampMillis,
            accuracyMeters = 4.0,
            altitudeMeters = 10.0,
        )
    }
}
