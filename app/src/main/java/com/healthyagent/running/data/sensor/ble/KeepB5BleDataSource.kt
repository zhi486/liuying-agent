package com.healthyagent.running.data.sensor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SensorSample
import com.healthyagent.running.data.sensor.SensorDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Keep B5 手环 BLE 数据源
 *
 * 实现 SensorDataSource 接口，职责：
 * 1. 扫描附近的 Keep B5 设备（按名称前缀匹配）
 * 2. 通过 BluetoothGatt 连接并订阅数据通知
 * 3. 自动解析标准 BLE 心率/电池服务
 * 4. 支持断线自动重连（指数退避，最多 5 次）
 *
 * 架构：GATT 回调 ≠ suspend 操作，通过 SharedFlow 桥接，
 * callbackFlow 将 SharedFlow 事件转为 Flow<SensorSample> 供 Pipeline 消费。
 */
@SuppressLint("MissingPermission") // 权限在 connect() 中运行时检查
class KeepB5BleDataSource(
    private val context: Context,
) : SensorDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter.bluetoothLeScanner

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val gattCallback = KeepB5GattCallback()

    override val deviceInfo: DeviceInfo
        get() = DeviceInfo(
            name = connectedDevice?.name ?: "Keep B5",
            batteryPercent = gattCallback.batteryPercent,
            firmwareVersion = gattCallback.firmwareVersion,
        )

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var reconnectCount = 0
    private var isUserDisconnect = false

    // ── 连接 ──

    override suspend fun connect() {
        // 检查权限
        if (!hasBlePermissions()) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e("BLE: 缺少蓝牙权限")
            return
        }

        // 检查蓝牙是否开启
        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e("BLE: 蓝牙未开启")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        isUserDisconnect = false
        reconnectCount = 0

        // 监听 GATT 连接事件
        scope.launch {
            gattCallback.connectionEvents.collect { event ->
                when (event) {
                    is ConnectionEvent.Connected -> {
                        // 连接成功，等待服务发现
                    }
                    is ConnectionEvent.Ready -> {
                        reconnectCount = 0
                        _connectionState.value = ConnectionState.CONNECTED
                        Timber.i("BLE: 数据通道就绪 ✓")
                    }
                    is ConnectionEvent.Disconnected -> {
                        if (!isUserDisconnect) {
                            _connectionState.value = ConnectionState.CONNECTING
                            attemptReconnect()
                        }
                    }
                    is ConnectionEvent.Error -> {
                        Timber.w("BLE: 错误 — ${event.message}")
                        if (_connectionState.value != ConnectionState.CONNECTED) {
                            _connectionState.value = ConnectionState.ERROR
                        }
                    }
                }
            }
        }

        // 开始扫描
        scanAndConnect()
    }

    override suspend fun disconnect() {
        isUserDisconnect = true
        _connectionState.value = ConnectionState.DISCONNECTED
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Timber.w(e, "BLE: 断开连接异常")
        }
        bluetoothGatt = null
        connectedDevice = null
    }

    // ── 数据流 ──

    override val samples: Flow<SensorSample> = callbackFlow {
        val job = scope.launch {
            gattCallback.samples.collect { sample ->
                trySend(sample)
            }
        }
        awaitClose {
            job.cancel()
        }
    }

    // ── BLE 扫描 ──

    private fun scanAndConnect() {
        if (scanner == null) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e("BLE: 扫描器不可用（蓝牙关闭？）")
            return
        }

        Timber.i("BLE: 开始扫描 Keep B5 设备...")

        // 扫描过滤：心率服务 + 已知设备名前缀
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(KeepB5GattCallback.HR_SERVICE))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: return

                if (matchesKeepB5(name)) {
                    Timber.i("BLE: 发现 Keep B5 设备: $name (${device.address})")
                    scanner.stopScan(this)
                    connectToDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE: 扫描失败 errorCode=$errorCode")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e(e, "BLE: 扫描权限不足")
        }

        // 扫描超时
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
            if (_connectionState.value == ConnectionState.CONNECTING) {
                Timber.w("BLE: 扫描超时 — 未发现 Keep B5")
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    // ── GATT 连接 ──

    private fun connectToDevice(device: BluetoothDevice) {
        Timber.i("BLE: 正在连接 ${device.name} (${device.address})...")
        connectedDevice = device

        try {
            bluetoothGatt?.close()
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.ERROR
            Timber.e(e, "BLE: 连接权限不足")
        }
    }

    // ── 重连 ──

    private fun attemptReconnect() {
        if (reconnectCount >= MAX_RECONNECT_ATTEMPTS) {
            Timber.w("BLE: 重连已达上限 ($MAX_RECONNECT_ATTEMPTS 次)")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        reconnectCount++
        val delayMs = INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectCount - 1))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        Timber.i("BLE: 第 $reconnectCount 次重连，${delayMs}ms 后重试...")

        scope.launch {
            delay(delayMs)
            if (_connectionState.value != ConnectionState.CONNECTED && !isUserDisconnect) {
                connectedDevice?.let { connectToDevice(it) }
                    ?: scanAndConnect() // 如果设备引用丢失，重新扫描
            }
        }
    }

    // ── 工具 ──

    /** 检查设备名是否匹配已知 Keep B5 前缀 */
    private fun matchesKeepB5(name: String): Boolean {
        return KeepB5GattCallback.KNOWN_DEVICE_PREFIXES.any { prefix ->
            name.startsWith(prefix, ignoreCase = true)
        }
    }

    /** 检查运行时 BLE 权限 */
    private fun hasBlePermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 资源清理 */
    fun close() {
        scope.cancel()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
