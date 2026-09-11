package com.healthyagent.running.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.healthyagent.running.core.model.RunConfig
import com.healthyagent.running.core.model.effectiveHrAlertThresholdBpm

/**
 * 设置页 — 跑步目标 / 语音 / LLM / 数据源
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
) {
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val llmEnabled by viewModel.llmEnabled.collectAsState()
    val deepseekConfigured by viewModel.deepseekConfigured.collectAsState()
    val canConfigureDeepseek by viewModel.canConfigureDeepseek.collectAsState()
    val mockScenario by viewModel.mockScenario.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── 跑步目标 ──
            SectionHeader("跑步目标")

            val paceMinState = viewModel.targetPaceMin.collectAsState()
            val paceSecState = viewModel.targetPaceSec.collectAsState()
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = paceMinState.value,
                    onValueChange = { viewModel.setTargetPace(it, paceSecState.value) },
                    label = { Text("目标配速（分）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedTextField(
                    value = paceSecState.value,
                    onValueChange = { viewModel.setTargetPace(paceMinState.value, it) },
                    label = { Text("秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = viewModel.targetDistance.collectAsState().value,
                onValueChange = { viewModel.setTargetDistance(it) },
                label = { Text("目标距离（公里）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = viewModel.maxHeartRate.collectAsState().value,
                onValueChange = { viewModel.setMaxHeartRate(it) },
                label = { Text("最大心率（bpm）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))
            val configuredMaxHr = viewModel.maxHeartRate.collectAsState().value.toIntOrNull()
            val automaticThreshold = configuredMaxHr?.takeIf { it in 80..240 }
                ?.let { RunConfig(maxHeartRate = it).effectiveHrAlertThresholdBpm() }
            OutlinedTextField(
                value = viewModel.hrAlertThresholdBpm.collectAsState().value,
                onValueChange = viewModel::setHrAlertThresholdBpm,
                label = { Text("警戒心率（bpm）") },
                placeholder = { Text(automaticThreshold?.let { "自动（当前 $it bpm）" } ?: "自动（请先填写有效最大心率）") },
                supportingText = { Text("80-240；留空恢复自动，连续达到 10 秒后提醒。自动值并非个体医学评估。") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = viewModel.hrAlertRepeatIntervalSec.collectAsState().value,
                onValueChange = viewModel::setHrAlertRepeatIntervalSec,
                label = { Text("高心率重复提醒间隔（秒）") },
                supportingText = { Text("30-300，默认 120；有效值自动保存，仅在下次跑步生效。") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            viewModel.hrAlertError.collectAsState().value?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()

            // ── 语音播报 ──
            SectionHeader("语音播报")

            SwitchRow("启用语音播报", checked = ttsEnabled) {
                viewModel.setTtsEnabled(it)
            }

            OutlinedTextField(
                value = viewModel.ttsMinInterval.collectAsState().value,
                onValueChange = { viewModel.setTtsMinInterval(it) },
                label = { Text("最小播报间隔（秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = ttsEnabled,
            )

            OutlinedTextField(
                value = viewModel.fishApiKey.collectAsState().value,
                onValueChange = { viewModel.setFishApiKey(it) },
                label = { Text("Fish Audio API Key") },
                supportingText = { Text("用于云端声音克隆播报（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = ttsEnabled,
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()

            // ── AI 教练 ──
            SectionHeader("DeepSeek 实时陪伴")

            SwitchRow("AI 陪伴", checked = llmEnabled, enabled = deepseekConfigured) {
                viewModel.setLlmEnabled(it)
            }

            Text(if (!deepseekConfigured) "未配置" else if (llmEnabled) "AI 陪伴已启用，尚未验证连接" else "已配置，AI 陪伴关闭")
            OutlinedButton(onClick = viewModel::configureDeepseek, enabled = canConfigureDeepseek) {
                Text(if (deepseekConfigured) "更换 DeepSeek 密钥" else "配置 DeepSeek 密钥")
            }
            Text("密钥仅在私密窗口填写，保存不会自动开启、检测或试听。跑步及暂停期间不可更换。")
            viewModel.deepseekError.collectAsState().value?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()

            // ── 数据源 ──
            SectionHeader("数据源")

            val scenarios = listOf(
                "SPEED_TEST" to "快速测试",
                "EASY_RUN" to "轻松跑",
                "PROGRESSIVE" to "渐加速",
                "HEART_RATE_SPIKE" to "心率飙升",
                "CADENCE_DROP" to "步频骤降",
            )

            scenarios.forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setMockScenario(key) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                    )
                    if (mockScenario == key) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onToggle)
    }
}
