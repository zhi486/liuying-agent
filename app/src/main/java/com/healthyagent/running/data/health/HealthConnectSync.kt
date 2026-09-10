package com.healthyagent.running.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.healthyagent.running.core.model.RunSession
import kotlinx.coroutines.flow.StateFlow

/** Health Connect 当前在设备上的可用状态。 */
enum class HealthConnectAvailability {
    AVAILABLE,
    PROVIDER_UPDATE_REQUIRED,
    UNAVAILABLE,
}

/** 权限检查的最新快照；它只描述 Health Connect，不参与跑步开跑门禁。 */
data class HealthConnectPermissionState(
    val availability: HealthConnectAvailability,
    val grantedPermissions: Set<String> = emptySet(),
    val checked: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasBaseWritePermissions: Boolean
        get() = grantedPermissions.containsAll(HealthConnectPermissions.BASE_WRITE)

    val hasAllRequestedPermissions: Boolean
        get() = grantedPermissions.containsAll(HealthConnectPermissions.REQUESTED)
}

/** 流萤写入 Health Connect 时使用的最小权限集合。 */
object HealthConnectPermissions {
    val BASE_WRITE: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
    )

    const val ROUTE_WRITE: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    val REQUESTED: Set<String> = BASE_WRITE + ROUTE_WRITE
}

/** 跑后同步的显式结果；调用方不需要用异常判断是否影响本地保存。 */
sealed interface HealthConnectSyncResult {
    data class Synced(
        val routeWritten: Boolean,
        val writtenRecordCount: Int,
    ) : HealthConnectSyncResult

    data object Unavailable : HealthConnectSyncResult

    data object ProviderUpdateRequired : HealthConnectSyncResult

    data class PermissionMissing(
        val permissions: Set<String>,
    ) : HealthConnectSyncResult

    data class InvalidRun(
        val reason: InvalidRunReason,
    ) : HealthConnectSyncResult

    data class Failed(
        val stage: FailureStage,
        val causeType: String,
    ) : HealthConnectSyncResult

    enum class InvalidRunReason {
        NOT_FINISHED,
        INVALID_TIME_RANGE,
        INVALID_DISTANCE,
        INVALID_RECORD_DATA,
    }

    enum class FailureStage {
        CHECK_PERMISSIONS,
        WRITE_RECORDS,
    }
}

/** 可由 DI 注入；同步失败永远通过 [HealthConnectSyncResult] 返回。 */
interface HealthConnectSync {
    val requestedPermissions: Set<String>
    val permissionState: StateFlow<HealthConnectPermissionState>

    fun availability(): HealthConnectAvailability

    suspend fun refreshPermissions(): HealthConnectPermissionState

    fun updateGrantedPermissions(grantedPermissions: Set<String>)

    suspend fun syncRun(session: RunSession): HealthConnectSyncResult
}
