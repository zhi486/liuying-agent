package com.healthyagent.running.data.sensor

import app.cash.turbine.test
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.DeviceInfo
import com.healthyagent.running.core.model.SampleType
import com.healthyagent.running.core.model.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeSensorDataSourceTest {
    @Test
    fun `预连接只准备手环且不启动手机或运动`() = runTest {
        val phone = FakeSensorSource("手机 GPS")
        val wearable = FakeSensorSource("Keep B5")
        val source = CompositeSensorDataSource(phone, wearable)
        source.prepareConnection()
        assertEquals(0, phone.prepareCalls)
        assertEquals(1, wearable.prepareCalls)
        assertEquals(ConnectionState.DISCONNECTED, phone.connectionState.value)
        assertEquals(ConnectionState.CONNECTED, wearable.connectionState.value)
        assertEquals(emptyList<String>(), wearable.lifecycleEvents)
        CompositeSensorDataSource(phone, null).prepareConnection()
        assertEquals(ConnectionState.DISCONNECTED, phone.connectionState.value)
    }

    @Test
    fun `显示指标只传给手环且允许没有手环`() {
        val phone = FakeSensorSource("手机 GPS")
        val wearable = FakeSensorSource("Keep B5")
        CompositeSensorDataSource(phone, wearable).onRunMetricsUpdated(153.5, 816.2)
        CompositeSensorDataSource(phone, null).onRunMetricsUpdated(186.0, null)
        assertEquals(emptyList<Pair<Double, Double?>>(), phone.displayMetrics)
        assertEquals(listOf(153.5 to 816.2), wearable.displayMetrics)
    }

    @Test
    fun `手机和穿戴设备样本合并且手机状态为主状态`() = runTest {
        val phone = FakeSensorSource("手机 GPS")
        val wearable = FakeSensorSource("Keep B5", batteryPercent = 82)
        val composite = CompositeSensorDataSource(phone, wearable)

        composite.connect()

        assertEquals(ConnectionState.CONNECTED, composite.connectionState.value)
        assertEquals(ConnectionState.CONNECTED, composite.wearableConnectionState?.value)
        assertEquals("手机 GPS", composite.deviceInfo.name)
        assertEquals("手机 GPS", composite.primaryDeviceInfo.name)
        assertEquals("Keep B5", composite.wearableDeviceInfo?.name)
        assertEquals(82, composite.wearableDeviceInfo?.batteryPercent)

        composite.samples.test {
            phone.emit(SampleType.DISTANCE, 100.0)
            assertEquals(SampleType.DISTANCE, awaitItem().type)
            wearable.emit(SampleType.HEART_RATE, 145.0)
            assertEquals(SampleType.HEART_RATE, awaitItem().type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `穿戴设备连接失败不影响手机主链路`() = runTest {
        val phone = FakeSensorSource("手机 GPS")
        val wearable = FakeSensorSource("Keep B5", failConnect = true)
        val composite = CompositeSensorDataSource(phone, wearable)

        composite.connect()

        assertEquals(ConnectionState.CONNECTED, composite.connectionState.value)
        assertEquals("手机 GPS", composite.deviceInfo.name)
    }

    @Test
    fun `跑步开始暂停和恢复只应透传给穿戴设备而不能误操作手机`() {
        val phone = FakeSensorSource("手机 GPS")
        val wearable = FakeSensorSource("Keep B5")
        val composite = CompositeSensorDataSource(phone, wearable)

        composite.onRunStarted(12L)
        composite.onRunPaused(27L)
        composite.onRunResumed(27L)

        assertEquals(emptyList<String>(), phone.lifecycleEvents)
        assertEquals(
            listOf("started:12", "paused:27", "resumed:27"),
            wearable.lifecycleEvents,
        )
    }

    @Test
    fun `跑步停止只应透传给穿戴设备而不能停止手机主链路`() {
        val phone = FakeSensorSource("手机 GPS")
        val wearable = FakeSensorSource("Keep B5")
        val composite = CompositeSensorDataSource(phone, wearable)

        composite.onRunStopped()

        assertEquals(emptyList<String>(), phone.lifecycleEvents)
        assertEquals(listOf("stopped"), wearable.lifecycleEvents)
    }
}

private class FakeSensorSource(
    name: String,
    batteryPercent: Int? = null,
    private val failConnect: Boolean = false,
) : SensorDataSource, RunLifecycleAwareSensor {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val sampleFlow = MutableSharedFlow<SensorSample>(extraBufferCapacity = 4)
    val lifecycleEvents = mutableListOf<String>()
    val displayMetrics = mutableListOf<Pair<Double, Double?>>()
    var prepareCalls = 0
    override val connectionState: StateFlow<ConnectionState> = state
    override val samples: Flow<SensorSample> = sampleFlow
    override val deviceInfo = DeviceInfo(name, batteryPercent)

    override suspend fun connect() {
        if (failConnect) {
            state.value = ConnectionState.ERROR
            error("测试连接失败")
        }
        state.value = ConnectionState.CONNECTED
    }

    override suspend fun prepareConnection() {
        prepareCalls++
        state.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        state.value = ConnectionState.DISCONNECTED
    }

    override fun onRunStarted(elapsedSeconds: Long) {
        lifecycleEvents += "started:$elapsedSeconds"
    }

    override fun onRunPaused(elapsedSeconds: Long) {
        lifecycleEvents += "paused:$elapsedSeconds"
    }

    override fun onRunResumed(elapsedSeconds: Long) {
        lifecycleEvents += "resumed:$elapsedSeconds"
    }

    override fun onRunStopped() {
        lifecycleEvents += "stopped"
    }

    override fun onRunMetricsUpdated(distanceMeters: Double, paceSecPerKm: Double?) {
        displayMetrics += distanceMeters to paceSecPerKm
    }

    suspend fun emit(type: SampleType, value: Double) {
        sampleFlow.emit(SensorSample(type, value, 1_000L, deviceInfo.name))
    }
}
