package com.healthyagent.running.ui.running

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthyagent.running.core.model.ConnectionState
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.core.model.RunState
import com.healthyagent.running.ui.theme.*

/**
 * 跑步主界面 — 大数字深色仪表盘风格
 */
@Composable
fun RunningScreen(
    viewModel: RunningViewModel,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RunningUiEvent.ShowError -> Toast.makeText(
                    context,
                    event.message,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryDark)
            .padding(24.dp)
    ) {
        // 顶部：连接状态 + 设备名称
        StatusBar(
            sensorStatus = uiState.sensorStatus,
            deviceName = uiState.deviceName,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 核心指标区：心率（大字）+ 区间色
        HeartRateDisplay(
            heartRateText = uiState.display.heartRateText,
            zoneLabel = uiState.display.zoneLabel,
            hrZone = uiState.display.hrZone,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 辅助指标行：配速 | 步频 | 步幅
        MetricsRow(
            paceText = uiState.display.paceText,
            cadenceText = uiState.display.cadenceText,
            strideLengthText = uiState.display.strideLengthText,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 距离 + 时间
        DistanceTimeRow(
            distanceText = uiState.display.distanceText,
            elapsedText = uiState.display.elapsedText,
        )

        Spacer(modifier = Modifier.weight(1f))

        // 数据新鲜度提示
        if (uiState.display.isDataFresh == false) {
            Text(
                text = "⚠ 数据中断，请检查手环",
                color = HrZone5,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
        }

        // 底部控制按钮
        ControlButtons(
            runState = uiState.runState,
            sensorStatus = uiState.sensorStatus,
            onConnect = { viewModel.connectSensor() },
            onStart = { viewModel.startRun() },
            onPause = { viewModel.pauseRun() },
            onResume = { viewModel.resumeRun() },
            onStop = { viewModel.stopRun() },
        )
    }
}

@Composable
private fun StatusBar(
    sensorStatus: ConnectionState,
    deviceName: String,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 连接状态指示灯
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusColor = when (sensorStatus) {
                ConnectionState.CONNECTED -> HrZone1
                ConnectionState.CONNECTING -> HrZone3
                ConnectionState.DISCONNECTED -> TextSecondary
                ConnectionState.ERROR -> HrZone5
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (sensorStatus) {
                    ConnectionState.CONNECTED -> "已连接"
                    ConnectionState.CONNECTING -> "连接中…"
                    ConnectionState.DISCONNECTED -> "未连接"
                    ConnectionState.ERROR -> "连接失败"
                },
                color = statusColor,
                fontSize = 14.sp,
            )
        }

        // 设备名称 + 历史按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (deviceName.isNotEmpty()) {
                Text(
                    text = deviceName,
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            TextButton(
                onClick = onOpenHistory,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("历史", color = PrimaryAccent, fontSize = 13.sp)
            }
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Text("⚙", color = PrimaryAccent, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun HeartRateDisplay(
    heartRateText: String,
    zoneLabel: String,
    hrZone: HrZone?,
) {
    val zoneColor = zoneColor(hrZone)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 心率大数字
        Text(
            text = heartRateText,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = zoneColor,
        )
        Text(
            text = "bpm",
            fontSize = 18.sp,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 区间标签
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = zoneColor.copy(alpha = 0.2f),
        ) {
            Text(
                text = zoneLabel,
                color = zoneColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MetricsRow(
    paceText: String,
    cadenceText: String,
    strideLengthText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MetricItem(
            label = "配速",
            value = paceText,
            unit = "/km",
        )
        MetricItem(
            label = "步频",
            value = cadenceText,
            unit = "spm",
        )
        MetricItem(
            label = "步幅",
            value = strideLengthText,
            unit = "cm",
        )
    }
}

@Composable
private fun MetricItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = OnSurfaceDark,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = unit,
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun DistanceTimeRow(distanceText: String, elapsedText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MetricItem(
            label = "距离",
            value = distanceText,
            unit = "km",
        )
        MetricItem(
            label = "时间",
            value = elapsedText,
            unit = "",
        )
    }
}

@Composable
private fun ControlButtons(
    runState: RunState,
    sensorStatus: ConnectionState,
    onConnect: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val isConnected = sensorStatus == ConnectionState.CONNECTED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        when (runState) {
            RunState.IDLE -> {
                // 未连接 → 连接按钮；已连接 → 开始按钮
                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                    ) {
                        Text("连接设备", color = PrimaryDark)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = HrZone1),
                    ) {
                        Text("开始跑步", color = PrimaryDark)
                    }
                }
            }
            RunState.RUNNING -> {
                OutlinedButton(
                    onClick = onPause,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HrZone3),
                ) {
                    Text("暂停")
                }
                Spacer(modifier = Modifier.width(24.dp))
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = HrZone5),
                ) {
                    Text("结束")
                }
            }
            RunState.PAUSED -> {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = HrZone1),
                ) {
                    Text("继续", color = PrimaryDark)
                }
                Spacer(modifier = Modifier.width(24.dp))
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HrZone5),
                ) {
                    Text("结束")
                }
            }
            RunState.FINISHED -> {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                ) {
                    Text("重新开始", color = PrimaryDark)
                }
            }
            else -> {}
        }
    }
}
