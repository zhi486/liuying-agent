package com.healthyagent.running.data.sensor.ble

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.diagnostics.BleSyncKind
import com.healthyagent.running.diagnostics.BleWriteResult
import com.healthyagent.running.diagnostics.DiagnosticBleLink
import com.healthyagent.running.diagnostics.DiagnosticEvent
import com.healthyagent.running.diagnostics.DiagnosticLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 显式开启的手环模拟显示测试：不启动 GPS、语音或跑步记录；屏幕需用户核对。 */
@RunWith(AndroidJUnit4::class)
class KeepB5DisplayLiveTest {
    @Test
    fun previewDistanceAndPaceWithoutSavingRun() {
        assumeTrue(
            "B5 显示预览必须显式开启，并先释放官方 Keep 的手环连接",
            InstrumentationRegistry.getArguments().getString("allowB5DisplayPreview") == "true",
        )
        runBlocking {
            val repository = GlobalContext.get().get<RunRepository>()
            assertNull("有未结束跑步，不能启动模拟预览", repository.getActiveSession())
            val events = CopyOnWriteArrayList<DiagnosticEvent>()
            val timeline = CopyOnWriteArrayList<String>()
            val startedAtMillis = SystemClock.elapsedRealtime()
            fun trace(message: String) {
                timeline += "${SystemClock.elapsedRealtime() - startedAtMillis} 毫秒：$message"
            }
            fun report(message: String) {
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("stream", "\n$message\n")
                })
            }
            val logger = object : DiagnosticLogger {
                override fun record(event: DiagnosticEvent) {
                    events += event
                    // 只保留类型化状态，不输出地址、位置、心率或凭据。
                    when (event) {
                        is DiagnosticEvent.BleLifecycleChanged,
                        is DiagnosticEvent.BleLinkChanged,
                        is DiagnosticEvent.BleReconnect,
                        is DiagnosticEvent.BleResumeHealth,
                        is DiagnosticEvent.BleOperationFailed,
                        -> trace(event.toString())
                        else -> Unit
                    }
                }
                override suspend fun awaitIdle() = Unit
                override suspend fun flush() = Unit
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val transport = AndroidKeepB5BleTransport(InstrumentationRegistry.getInstrumentation().targetContext, logger)
            val startRequests = AtomicInteger()
            val observedTransport = object : BleTransport by transport {
                override fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean {
                    val attempt = startRequests.incrementAndGet()
                    val accepted = transport.sendStart(guard, epochSeconds)
                    trace("第 $attempt 次 START 提交，GATT=${guard.gattToken}，接受=$accepted")
                    return accepted
                }
            }
            val source = KeepB5BleDataSource(
                observedTransport, scope, SystemClock::elapsedRealtime, { System.currentTimeMillis() / 1_000L },
                BleReconnectBackoff(1_000L, 30_000L), logger,
            )
            fun acceptedUpdates() = events.filterIsInstance<DiagnosticEvent.BleSyncWrite>()
                .count { it.kind == BleSyncKind.UPDATE_WORKOUT && it.result == BleWriteResult.ACCEPTED }
            suspend fun stage(label: String, distance: Double, pace: Double?) {
                source.onRunMetricsUpdated(distance, pace)
                trace("开始阶段：$label")
                report("B5 模拟预览：$label（20 秒）")
                val before = acceptedUpdates()
                delay(20_000L)
                val writes = acceptedUpdates() - before
                val state = source.connectionState.value
                trace("阶段结束：$label；接受写入=$writes；连接状态=$state")
                report("阶段结果：接受写入=$writes；连接状态=$state；START 次数=${startRequests.get()}")
                assertTrue("当前阶段没有持续接受指标写入", writes >= 10)
                assertTrue("阶段末未处于已连接状态：$state", state == ConnectionState.CONNECTED)
            }
            try {
                source.onRunStarted(0L)
                withTimeout(45_000L) { source.connectionState.first { it == ConnectionState.CONNECTED } }
                assertTrue("未建立 B5 私有显示通道", events.filterIsInstance<DiagnosticEvent.BleLinkChanged>()
                    .any { it.to == DiagnosticBleLink.READY_PRIVATE_B3 })
                stage("距离约 0.15 公里，配速 13′36″", 153.0, 816.0)
                stage("距离约 0.18 公里，配速横杠", 186.0, null)
                stage("距离 1.25 公里，配速 6′00″", 1_250.0, 360.0)
                source.onRunPaused(60L)
                stage("暂停：距离仍为 1.25 公里、配速横杠、用时冻结", 9_999.0, 200.0)
                source.onRunResumed(60L)
                stage("恢复：距离 1.37 公里，配速 5′30″", 1_370.0, 330.0)
                assertTrue("预览存在被拒绝的写入", events.filterIsInstance<DiagnosticEvent.BleSyncWrite>()
                    .none { it.result == BleWriteResult.REJECTED })
                assertTrue("预览期间重复发送了 START，不能判定连接稳定", startRequests.get() == 1)
            } finally {
                trace("测试清理开始；清理前连接状态=${source.connectionState.value}")
                source.onRunStopped()
                try {
                    withTimeout(10_000L) { transport.shutdown().join() }
                } finally {
                    scope.cancel()
                    report("B5 安全诊断时间线：\n${timeline.joinToString("\n")}")
                }
            }
            assertNull("预览不应创建跑步记录", repository.getActiveSession())
        }
    }
}
