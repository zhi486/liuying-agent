package com.healthyagent.running.data.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.RunSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AndroidHealthConnectSyncTest {
    @Test
    fun `SDK 状态映射覆盖可用 更新与不可用`() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            healthConnectAvailabilityFromSdkStatus(HealthConnectClient.SDK_AVAILABLE),
        )
        assertEquals(
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED,
            healthConnectAvailabilityFromSdkStatus(
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
            ),
        )
        assertEquals(
            HealthConnectAvailability.UNAVAILABLE,
            healthConnectAvailabilityFromSdkStatus(HealthConnectClient.SDK_UNAVAILABLE),
        )
        assertEquals(
            HealthConnectAvailability.UNAVAILABLE,
            healthConnectAvailabilityFromSdkStatus(Int.MAX_VALUE),
        )
    }

    @Test
    fun `SDK 不可用时不会查询权限或写记录`() = runTest {
        val gateway = FakeHealthConnectGateway(status = HealthConnectClient.SDK_UNAVAILABLE)
        val sync = AndroidHealthConnectSync(gateway)

        val result = sync.syncRun(finishedSession())

        assertEquals(HealthConnectSyncResult.Unavailable, result)
        assertEquals(0, gateway.permissionReadCount)
        assertEquals(0, gateway.insertedBatches.size)
    }

    @Test
    fun `provider 需要更新时返回独立结果`() = runTest {
        val gateway = FakeHealthConnectGateway(
            status = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
        )
        val sync = AndroidHealthConnectSync(gateway)

        assertEquals(
            HealthConnectSyncResult.ProviderUpdateRequired,
            sync.syncRun(finishedSession()),
        )
    }

    @Test
    fun `缺少基础写权限时不写入并返回缺失集合`() = runTest {
        val gateway = FakeHealthConnectGateway(
            granted = setOf(HealthConnectPermissions.ROUTE_WRITE),
        )
        val sync = AndroidHealthConnectSync(gateway)

        val result = sync.syncRun(finishedSession())

        assertTrue(result is HealthConnectSyncResult.PermissionMissing)
        result as HealthConnectSyncResult.PermissionMissing
        assertEquals(HealthConnectPermissions.BASE_WRITE, result.permissions)
        assertTrue(gateway.insertedBatches.isEmpty())
    }

    @Test
    fun `缺少路线权限时仍写入跑步和距离`() = runTest {
        val gateway = FakeHealthConnectGateway(
            granted = HealthConnectPermissions.BASE_WRITE,
        )
        val sync = AndroidHealthConnectSync(gateway)

        val result = sync.syncRun(finishedSession())

        assertEquals(HealthConnectSyncResult.Synced(false, 2), result)
        val exercise = gateway.insertedBatches.single().first() as ExerciseSessionRecord
        assertTrue(exercise.exerciseRouteResult is ExerciseRouteResult.NoData)
    }

    @Test
    fun `路线权限和合法路线齐全时把路线写入会话`() = runTest {
        val gateway = FakeHealthConnectGateway(
            granted = HealthConnectPermissions.REQUESTED,
        )
        val sync = AndroidHealthConnectSync(gateway)

        val result = sync.syncRun(finishedSession())

        assertEquals(HealthConnectSyncResult.Synced(true, 2), result)
        val exercise = gateway.insertedBatches.single().first() as ExerciseSessionRecord
        assertTrue(exercise.exerciseRouteResult is ExerciseRouteResult.Data)
    }

    @Test
    fun `权限读取和写入异常都被转换为失败结果`() = runTest {
        val permissionGateway = FakeHealthConnectGateway(
            granted = HealthConnectPermissions.REQUESTED,
            permissionError = IOException("offline"),
        )
        val writeGateway = FakeHealthConnectGateway(
            granted = HealthConnectPermissions.REQUESTED,
            insertError = IOException("disk"),
        )

        val permissionResult = AndroidHealthConnectSync(permissionGateway).syncRun(finishedSession())
        val writeResult = AndroidHealthConnectSync(writeGateway).syncRun(finishedSession())

        assertEquals(
            HealthConnectSyncResult.FailureStage.CHECK_PERMISSIONS,
            (permissionResult as HealthConnectSyncResult.Failed).stage,
        )
        assertEquals("IOException", permissionResult.causeType)
        assertEquals(
            HealthConnectSyncResult.FailureStage.WRITE_RECORDS,
            (writeResult as HealthConnectSyncResult.Failed).stage,
        )
        assertEquals("IOException", writeResult.causeType)
    }

    @Test
    fun `权限 contract 结果可更新管理器状态`() {
        val sync = AndroidHealthConnectSync(FakeHealthConnectGateway())

        sync.updateGrantedPermissions(HealthConnectPermissions.BASE_WRITE)

        assertTrue(sync.permissionState.value.checked)
        assertTrue(sync.permissionState.value.hasBaseWritePermissions)
        assertFalse(sync.permissionState.value.hasAllRequestedPermissions)
    }

    private fun finishedSession(): RunSession {
        return RunSession(
            id = "run-1",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            totalDistanceMeters = 500.0,
            totalDurationSec = 1L,
            routePoints = listOf(
                RoutePoint(31.0, 121.0, 1_200L, 4.0),
                RoutePoint(31.1, 121.1, 1_500L, 4.0),
            ),
        )
    }
}

private class FakeHealthConnectGateway(
    var status: Int = HealthConnectClient.SDK_AVAILABLE,
    var granted: Set<String> = emptySet(),
    var permissionError: Throwable? = null,
    var insertError: Throwable? = null,
) : HealthConnectGateway {
    var permissionReadCount: Int = 0
    val insertedBatches = mutableListOf<List<Record>>()

    override fun sdkStatus(): Int = status

    override suspend fun grantedPermissions(): Set<String> {
        permissionReadCount += 1
        permissionError?.let { throw it }
        return granted
    }

    override suspend fun insertRecords(records: List<Record>) {
        insertError?.let { throw it }
        insertedBatches += records
    }
}
