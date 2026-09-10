package com.healthyagent.running.data.sensor.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.RoutePoint
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.data.sensor.SensorDataSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** 使用系统 LocationManager 采集手机 GPS 距离和速度。 */
class PhoneGpsDataSource(
    private val context: Context,
    private val tracker: GpsDistanceTracker = GpsDistanceTracker(),
) : SensorDataSource {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<SensorSample>(
        extraBufferCapacity = SAMPLE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: Flow<SensorSample> = _samples.asSharedFlow()
    private val _routePoints = MutableSharedFlow<RoutePoint>(
        extraBufferCapacity = SAMPLE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val routePoints: Flow<RoutePoint> = _routePoints.asSharedFlow()

    override val deviceInfo = DeviceInfo(name = "手机 GPS")

    private val registeredProviders = mutableSetOf<String>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val update = tracker.update(
                GpsFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.toDouble(),
                    timestampMillis = location.time,
                    reportedSpeedMetersPerSecond = if (location.hasSpeed()) {
                        location.speed.toDouble()
                    } else {
                        null
                    },
                )
            ) ?: return

            _samples.tryEmit(
                SensorSample(
                    type = SampleType.DISTANCE,
                    value = update.totalDistanceMeters,
                    timestampMillis = location.time,
                    source = SAMPLE_SOURCE,
                )
            )
            _samples.tryEmit(
                SensorSample(
                    type = SampleType.SPEED,
                    value = update.speedMetersPerSecond,
                    timestampMillis = location.time,
                    source = SAMPLE_SOURCE,
                )
            )
            _routePoints.tryEmit(
                RoutePoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestampMillis = location.time,
                    accuracyMeters = location.accuracy.toDouble(),
                    altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                ),
            )
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            if (registeredProviders.all { !locationManager.isProviderEnabled(it) }) {
                _connectionState.value = ConnectionState.ERROR
                Timber.w("GPS: 所有定位提供方均已关闭")
            }
        }

        @Deprecated("Android 12 以下版本仍可能回调")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission") // connect() 已先检查精确位置权限
    override suspend fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED
        ) {
            return
        }

        if (!hasFineLocationPermission()) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e("GPS: 缺少精确位置权限")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        tracker.reset()
        registeredProviders.clear()

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                locationManager.allProviders.contains(provider) &&
                    locationManager.isProviderEnabled(provider)
            }

        if (providers.isEmpty()) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e("GPS: GPS 与网络定位均不可用")
            return
        }

        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_UPDATE_INTERVAL_MS,
                    LOCATION_UPDATE_DISTANCE_METERS,
                    locationListener,
                    Looper.getMainLooper(),
                )
                registeredProviders += provider
            }
            _connectionState.value = ConnectionState.CONNECTED
            Timber.i("GPS: 已监听 ${registeredProviders.joinToString()} 定位更新")
        } catch (error: SecurityException) {
            stopLocationUpdates()
            _connectionState.value = ConnectionState.ERROR
            Timber.e(error, "GPS: 定位权限不足")
        } catch (error: IllegalArgumentException) {
            stopLocationUpdates()
            _connectionState.value = ConnectionState.ERROR
            Timber.e(error, "GPS: 定位提供方不可用")
        }
    }

    override suspend fun disconnect() {
        stopLocationUpdates()
        tracker.reset()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (error: SecurityException) {
            Timber.w(error, "GPS: 停止定位更新时权限已被撤销")
        }
        registeredProviders.clear()
    }

    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 0f
        private const val SAMPLE_BUFFER_CAPACITY = 32
        private const val SAMPLE_SOURCE = "phone_gps"
    }
}
