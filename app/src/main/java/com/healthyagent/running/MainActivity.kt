package com.healthyagent.running

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.healthyagent.running.ui.navigation.AppNavHost
import com.healthyagent.running.ui.theme.HealthyAgentTheme
import com.healthyagent.running.ui.web.WebPreviewHost
import com.healthyagent.running.agent.AgentTaskService
import com.healthyagent.running.agent.cloud.CloudAgentService
import com.healthyagent.running.agent.cloud.CloudAgentConfiguration
import com.healthyagent.running.ui.settings.CloudAgentConfigDialog
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.ui.settings.DeepseekKeyDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.coordinator.CoordinatorEvent
import com.healthyagent.running.coordinator.PermissionsSnapshot
import com.healthyagent.running.coordinator.RunCommandDispatcher
import com.healthyagent.running.data.health.HealthConnectAvailability
import com.healthyagent.running.data.health.HealthConnectSync
import com.healthyagent.running.data.sensor.RunningService
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val coordinator: AppCoordinator by inject()
    private val commandDispatcher: RunCommandDispatcher by inject()
    private val agentTaskService: AgentTaskService by inject()
    private val memoryService: MemoryService by inject()
    private val cloudAgentService: CloudAgentService by inject()
    private val cloudAgentConfiguration: CloudAgentConfiguration by inject()
    private val voiceInputService: com.healthyagent.running.agent.voice.VoiceInputService by inject()
    private var voicePermissionSession: String? = null
    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voicePermissionSession?.let { voiceInputService.permissionResult(it, granted) }
        voicePermissionSession = null
    }
    private var cloudDialog: CloudAgentConfigDialog? = null
    private val healthConnectSync: HealthConnectSync by inject()
    private var healthPermissionRequestLaunched = false
    private var runningPermissionRequestAttempted = false
    private var deepseekDialog: DeepseekKeyDialog? = null
    private var deepseekDialogToken: Long? = null

    companion object {
        /** 首次启动一起申请手机 GPS、可选 BLE 与通知权限。 */
        val RUNNING_PERMISSIONS = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                voiceInputService.permissionRequested.collect { id ->
                    if (id != null) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            voiceInputService.permissionResult(id, true)
                        else if (voicePermissionSession == null) {
                            voicePermissionSession = id
                            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else if (voicePermissionSession != id) voiceInputService.permissionResult(id, false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                cloudAgentService.configuring.collect { requested ->
                    if (requested && cloudDialog == null) {
                        deepseekDialog?.dismiss()
                        cloudDialog = CloudAgentConfigDialog(this@MainActivity, lifecycleScope, cloudAgentConfiguration) {
                            cloudAgentService.closeConfiguration()
                            cloudDialog = null
                        }.also { it.show() }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                coordinator.deepseekConfigurationRequest.collect { token ->
                    if (token == null) {
                        deepseekDialog?.dismiss()
                        deepseekDialog = null
                        deepseekDialogToken = null
                    } else if (deepseekDialogToken != token) {
                        cloudDialog?.dismiss()
                        deepseekDialog?.dismiss()
                        deepseekDialogToken = token
                        val prompt = DeepseekKeyDialog(
                            activity = this@MainActivity,
                            scope = lifecycleScope,
                            isCurrent = { coordinator.deepseekConfigurationRequest.value == token &&
                                !coordinator.isRestoring && coordinator.snapshot.value.session.state !in setOf("running", "paused", "preparing") },
                            onSave = { input -> withContext(Dispatchers.IO) { coordinator.saveDeepseekKey(token, input) } },
                            onClosed = {
                                coordinator.cancelDeepseekConfiguration(token)
                                if (deepseekDialogToken == token) {
                                    deepseekDialog = null
                                    deepseekDialogToken = null
                                }
                            },
                        )
                        deepseekDialog = prompt
                        prompt.show()
                    }
                }
            }
        }
        if (BuildConfig.WEB_UI_ENABLED) {
            configurePreviewSystemBars()
        } else {
            enableEdgeToEdge()
        }

        setContent {
            HealthyAgentTheme {
                var runningPermissionFlowCompleted by remember {
                    mutableStateOf(hasCoreRunningPermission())
                }

                // BLE 权限请求
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    runningPermissionRequestAttempted = true
                    publishPermissionSnapshot(requested = true)
                    runningPermissionFlowCompleted = true
                    recoverActiveSessionIfNeeded()
                    val allGranted = grants.values.all { it }
                    if (!allGranted) {
                        Toast.makeText(
                            this@MainActivity,
                            "部分跑步权限未授权，GPS 或手环数据可能不可用",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                // Health Connect 使用官方 contract；结果只更新同步管理器，不阻止本地跑步。
                val healthPermissionLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract(),
                ) { grantedPermissions ->
                    healthConnectSync.updateGrantedPermissions(grantedPermissions)
                }

                // Web UI 的权限请求和业务错误由当前 Activity 承接。
                if (BuildConfig.WEB_UI_ENABLED) {
                    LaunchedEffect(coordinator) {
                        coordinator.events.collect { event ->
                            when (event) {
                                CoordinatorEvent.RequestRunningPermissions -> {
                                    if (hasCoreRunningPermission()) {
                                        publishPermissionSnapshot(requested = true)
                                    } else {
                                        runningPermissionRequestAttempted = true
                                        permissionLauncher.launch(RUNNING_PERMISSIONS)
                                    }
                                }
                                is CoordinatorEvent.ShowError -> Toast.makeText(
                                    this@MainActivity,
                                    event.message,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }

                // 首次进入时同步状态并请求跑步权限。
                if (!hasCoreRunningPermission()) {
                    LaunchedEffect(Unit) {
                        publishPermissionSnapshot(requested = false)
                        runningPermissionRequestAttempted = true
                        permissionLauncher.launch(RUNNING_PERMISSIONS)
                    }
                } else {
                    LaunchedEffect(Unit) {
                        publishPermissionSnapshot(requested = false)
                        runningPermissionFlowCompleted = true
                    }
                }

                // 等系统跑步权限弹窗结束后再请求，避免两个 ActivityResult 流程相互覆盖。
                LaunchedEffect(runningPermissionFlowCompleted, healthConnectSync) {
                    if (!runningPermissionFlowCompleted || healthPermissionRequestLaunched) {
                        return@LaunchedEffect
                    }
                    val permissionState = healthConnectSync.refreshPermissions()
                    if (permissionState.availability == HealthConnectAvailability.AVAILABLE &&
                        permissionState.checked &&
                        permissionState.errorMessage == null &&
                        !permissionState.hasAllRequestedPermissions
                    ) {
                        healthPermissionRequestLaunched = true
                        healthPermissionLauncher.launch(healthConnectSync.requestedPermissions)
                    }
                }

                if (BuildConfig.WEB_UI_ENABLED) {
                    WebPreviewHost(
                        coordinator = coordinator,
                        commandDispatcher = commandDispatcher,
                        agentTaskService = agentTaskService,
                        memoryService = memoryService,
                        cloudAgentService = cloudAgentService,
                        voiceInputService = voiceInputService,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AppNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        publishPermissionSnapshot(requested = runningPermissionRequestAttempted)
        recoverActiveSessionIfNeeded()
    }

    override fun onStop() {
        voiceInputService.cancel()
        cloudDialog?.dismiss()
        lifecycleScope.launch { cloudAgentService.clearPreparation() }
        deepseekDialog?.dismiss()
        deepseekDialog = null
        deepseekDialogToken = null
        super.onStop()
    }

    override fun onDestroy() {
        cloudDialog?.dismiss()
        deepseekDialog?.dismiss()
        deepseekDialog = null
        deepseekDialogToken = null
        super.onDestroy()
    }

    /** 只有精确定位仍获授权时，才恢复 Android 14 的 location 前台服务。 */
    private fun recoverActiveSessionIfNeeded() {
        if (!hasCoreRunningPermission()) return
        lifecycleScope.launch {
            if (coordinator.awaitRestoration() &&
                coordinator.snapshot.value.session.state in setOf("running", "paused")
            ) {
                RunningService.recover(this@MainActivity)
            }
        }
    }

    /** 手机 GPS 是唯一会阻止开始跑步的核心权限；BLE 与通知可被单独拒绝。 */
    private fun hasCoreRunningPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun publishPermissionSnapshot(requested: Boolean) {
        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        coordinator.updatePermissions(
            PermissionsSnapshot(
                bluetooth = permissionValue(bluetoothPermissions, requested),
                bodySensors = "notRequested",
                runningSensors = permissionValue(
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    requested,
                ),
            ),
        )
    }

    private fun permissionValue(permissions: List<String>, requested: Boolean): String {
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        return when {
            granted -> "granted"
            requested -> "denied"
            else -> "notRequested"
        }
    }

    /** 视觉评审宿主固定使用可读系统栏；正式主题联动由安全 WebView 宿主接管。 */
    @Suppress("DEPRECATION")
    private fun configurePreviewSystemBars() {
        window.statusBarColor = Color.rgb(16, 19, 16)
        window.navigationBarColor = Color.rgb(16, 19, 16)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
