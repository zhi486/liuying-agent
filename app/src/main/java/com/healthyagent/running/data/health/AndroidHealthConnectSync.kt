package com.healthyagent.running.data.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import com.healthyagent.running.core.model.RunSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface HealthConnectGateway {
    fun sdkStatus(): Int

    suspend fun grantedPermissions(): Set<String>

    suspend fun insertRecords(records: List<Record>)
}

internal class AndroidHealthConnectGateway(
    private val context: Context,
) : HealthConnectGateway {
    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    override fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    override suspend fun grantedPermissions(): Set<String> {
        return client.permissionController.getGrantedPermissions()
    }

    override suspend fun insertRecords(records: List<Record>) {
        client.insertRecords(records)
    }
}

/** Health Connect 的 Android 实现；所有平台异常都被收敛为明确结果。 */
class AndroidHealthConnectSync internal constructor(
    private val gateway: HealthConnectGateway,
    private val mapper: RunHealthRecordMapper = RunHealthRecordMapper(Device(Device.TYPE_PHONE)),
) : HealthConnectSync {
    constructor(context: Context) : this(
        gateway = AndroidHealthConnectGateway(context.applicationContext),
        mapper = RunHealthRecordMapper(
            device = Device(
                type = Device.TYPE_PHONE,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
            ),
        ),
    )

    override val requestedPermissions: Set<String> = HealthConnectPermissions.REQUESTED

    private val mutablePermissionState = MutableStateFlow(
        HealthConnectPermissionState(availability = availability()),
    )
    override val permissionState: StateFlow<HealthConnectPermissionState> =
        mutablePermissionState.asStateFlow()

    override fun availability(): HealthConnectAvailability {
        return healthConnectAvailabilityFromSdkStatus(gateway.sdkStatus())
    }

    override suspend fun refreshPermissions(): HealthConnectPermissionState {
        val availability = availability()
        if (availability != HealthConnectAvailability.AVAILABLE) {
            return HealthConnectPermissionState(
                availability = availability,
                checked = true,
            ).also { mutablePermissionState.value = it }
        }

        return try {
            HealthConnectPermissionState(
                availability = availability,
                grantedPermissions = gateway.grantedPermissions(),
                checked = true,
            ).also { mutablePermissionState.value = it }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            HealthConnectPermissionState(
                availability = availability,
                checked = true,
                errorMessage = "无法读取 Health Connect 权限",
            ).also { mutablePermissionState.value = it }
        }
    }

    override fun updateGrantedPermissions(grantedPermissions: Set<String>) {
        mutablePermissionState.value = HealthConnectPermissionState(
            availability = availability(),
            grantedPermissions = grantedPermissions,
            checked = true,
        )
    }

    override suspend fun syncRun(session: RunSession): HealthConnectSyncResult {
        when (availability()) {
            HealthConnectAvailability.UNAVAILABLE -> return HealthConnectSyncResult.Unavailable
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> {
                return HealthConnectSyncResult.ProviderUpdateRequired
            }
            HealthConnectAvailability.AVAILABLE -> Unit
        }

        val grantedPermissions = try {
            gateway.grantedPermissions().also(::updateGrantedPermissions)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            return HealthConnectSyncResult.Failed(
                stage = HealthConnectSyncResult.FailureStage.CHECK_PERMISSIONS,
                causeType = error.javaClass.simpleName,
            )
        }
        val missingBasePermissions = HealthConnectPermissions.BASE_WRITE - grantedPermissions
        if (missingBasePermissions.isNotEmpty()) {
            return HealthConnectSyncResult.PermissionMissing(missingBasePermissions)
        }

        val mapping = mapper.map(
            session = session,
            includeRoute = HealthConnectPermissions.ROUTE_WRITE in grantedPermissions,
        )
        if (mapping is RunHealthRecordMapping.Invalid) {
            return HealthConnectSyncResult.InvalidRun(mapping.reason)
        }
        mapping as RunHealthRecordMapping.Valid

        return try {
            gateway.insertRecords(mapping.records)
            HealthConnectSyncResult.Synced(
                routeWritten = mapping.routeWritten,
                writtenRecordCount = mapping.records.size,
            )
        } catch (error: SecurityException) {
            HealthConnectSyncResult.PermissionMissing(HealthConnectPermissions.BASE_WRITE)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            HealthConnectSyncResult.Failed(
                stage = HealthConnectSyncResult.FailureStage.WRITE_RECORDS,
                causeType = error.javaClass.simpleName,
            )
        }
    }
}

internal fun healthConnectAvailabilityFromSdkStatus(status: Int): HealthConnectAvailability {
    return when (status) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED
        }
        else -> HealthConnectAvailability.UNAVAILABLE
    }
}

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
