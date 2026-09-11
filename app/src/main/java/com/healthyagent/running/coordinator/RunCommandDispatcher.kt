package com.healthyagent.running.coordinator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.data.sensor.RunningService

/** 界面和 Bridge 只负责派发命令，不直接持有跑步任务。 */
interface RunCommandDispatcher {
    fun connectSensor(): CommandOutcome
    fun disconnectSensor(): CommandOutcome
    fun startRun(): CommandOutcome
    fun pauseRun(): CommandOutcome
    fun resumeRun(): CommandOutcome
    fun stopRun(): CommandOutcome
}

class AndroidRunCommandDispatcher(
    context: Context,
    private val coordinator: AppCoordinator,
) : RunCommandDispatcher {
    private val appContext = context.applicationContext

    override fun connectSensor(): CommandOutcome {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED }) {
            return CommandOutcome.Rejected(BridgeErrorCode.PERMISSION_REQUIRED, "连接手环前需要授予蓝牙扫描权限")
        }
        return coordinator.connectSensor()
    }

    override fun disconnectSensor(): CommandOutcome = coordinator.disconnectSensor()

    override fun startRun(): CommandOutcome {
        if (coordinator.deepseekConfigurationRequest.value != null) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "请先关闭 DeepSeek 密钥输入窗口")
        }
        if (coordinator.isRestoring) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "正在恢复上次跑步，请稍候")
        }
        if (coordinator.snapshot.value.session.state in setOf("running", "paused", "preparing")) {
            return CommandOutcome.Rejected(BridgeErrorCode.SESSION_CONFLICT, "已有跑步正在进行")
        }
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return CommandOutcome.Rejected(
                BridgeErrorCode.PERMISSION_REQUIRED,
                "开始跑步前需要授予定位权限",
            )
        }
        RunningService.start(appContext)
        return CommandOutcome.Accepted
    }

    override fun pauseRun(): CommandOutcome = dispatchForState(
        allowedState = "running",
        errorMessage = "当前不能暂停",
        dispatch = { RunningService.pause(appContext) },
    )

    override fun resumeRun(): CommandOutcome = dispatchForState(
        allowedState = "paused",
        errorMessage = "当前不能继续",
        dispatch = { RunningService.resume(appContext) },
    )

    override fun stopRun(): CommandOutcome {
        if (coordinator.snapshot.value.session.state !in setOf("running", "paused")) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, "当前没有可结束的跑步")
        }
        RunningService.stop(appContext)
        return CommandOutcome.Accepted
    }

    private fun dispatchForState(
        allowedState: String,
        errorMessage: String,
        dispatch: () -> Unit,
    ): CommandOutcome {
        if (coordinator.snapshot.value.session.state != allowedState) {
            return CommandOutcome.Rejected(BridgeErrorCode.COMMAND_NOT_ALLOWED, errorMessage)
        }
        dispatch()
        return CommandOutcome.Accepted
    }
}
