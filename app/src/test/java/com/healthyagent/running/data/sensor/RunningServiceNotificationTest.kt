package com.healthyagent.running.data.sensor

import com.healthyagent.running.coordinator.AppSnapshot
import com.healthyagent.running.coordinator.MetricsSnapshot
import com.healthyagent.running.coordinator.SessionSnapshot
import com.healthyagent.running.core.model.RunConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RunningServiceNotificationTest {
    @Test
    fun `暂停会话恢复时首条通知直接显示暂停状态`() {
        val snapshot = AppSnapshot.initial(RunConfig(), 0L).copy(
            session = SessionSnapshot(id = "run-1", state = "paused"),
            metrics = MetricsSnapshot(distanceMeters = 1_250.0, elapsedSeconds = 500),
        )

        assertEquals("已暂停 · 1.25 km · 08:20", snapshot.runningNotificationContent())
    }

    @Test
    fun `空闲快照启动前台服务时显示准备状态`() {
        val snapshot = AppSnapshot.initial(RunConfig(), 0L)

        assertEquals("正在准备跑步数据", snapshot.runningNotificationContent())
    }
}
