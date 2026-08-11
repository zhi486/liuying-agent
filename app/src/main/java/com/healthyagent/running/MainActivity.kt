package com.healthyagent.running

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.healthyagent.running.ai.AnalysisManager
import com.healthyagent.running.ai.EmergencyPhraseCache
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.core.session.RunSessionManager
import com.healthyagent.running.data.local.SettingsDataStore
import com.healthyagent.running.data.repository.RunRepository
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.speech.SpeechBus
import com.healthyagent.running.ui.history.HistoryScreen
import com.healthyagent.running.ui.history.HistoryViewModel
import com.healthyagent.running.ui.running.RunningScreen
import com.healthyagent.running.ui.running.RunningViewModel
import com.healthyagent.running.ui.settings.SettingsScreen
import com.healthyagent.running.ui.settings.SettingsViewModel
import com.healthyagent.running.ui.theme.HealthyAgentTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    companion object {
        /** 运行时权限列表（BLE + 身体传感器） */
        val BLE_PERMISSIONS = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // BODY_SENSORS 在 Android 13+ 是运行时权限，14+ health 前台服务必需
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.BODY_SENSORS)
            }
        }.toTypedArray()
    }

    private val sensorDataSource: SensorDataSource by inject()
    private val pipeline: RunningDataPipeline by inject()
    private val runConfig: RunConfig by inject()
    private val ruleEngine: RuleEngine by inject()
    private val speechBus: SpeechBus by inject()
    private val sessionManager: RunSessionManager by inject()
    private val repository: RunRepository by inject()
    private val analysisManager: AnalysisManager by inject()
    private val emergencyPhraseCache: EmergencyPhraseCache by inject()
    private val settingsDataStore: SettingsDataStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HealthyAgentTheme {
                var showHistory by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }

                // ViewModel 在顶层创建，导航切换不销毁
                val runningViewModel = remember {
                    RunningViewModel(
                        sensorDataSource = sensorDataSource,
                        pipeline = pipeline,
                        runConfig = runConfig,
                        ruleEngine = ruleEngine,
                        speechBus = speechBus,
                        sessionManager = sessionManager,
                        repository = repository,
                        analysisManager = analysisManager,
                        emergencyPhraseCache = emergencyPhraseCache,
                    )
                }
                val historyViewModel = remember { HistoryViewModel(repository) }
                val settingsViewModel = remember { SettingsViewModel(settingsDataStore) }

                // BLE 权限请求
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val allGranted = grants.values.all { it }
                    if (!allGranted) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "蓝牙权限被拒绝，无法连接手环",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // 检查并请求 BLE 权限
                if (!hasBlePermissions()) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        permissionLauncher.launch(BLE_PERMISSIONS)
                    }
                }

                when {
                    showSettings -> {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { showSettings = false }
                        )
                    }
                    showHistory -> {
                        HistoryScreen(
                            viewModel = historyViewModel,
                            onBack = { showHistory = false }
                        )
                    }
                    else -> {
                        RunningScreen(
                            viewModel = runningViewModel,
                            onOpenHistory = { showHistory = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    /** 检查是否已授予 BLE 权限 */
    private fun hasBlePermissions(): Boolean {
        return BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
