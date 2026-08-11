package com.healthyagent.running.ui.history

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthyagent.running.core.math.MetricsCalculator
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.core.model.Split
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录列表界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit = {},
) {
    val sessions by viewModel.sessions.collectAsState()
    val expandedId by viewModel.expandedSessionId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("跑步历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无跑步记录", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        isExpanded = expandedId == session.id,
                        onToggle = { viewModel.toggleExpand(session.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: RunSession,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 日期和距离
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDate(session.startTimeMillis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "%.1f km".format(session.totalDistanceMeters / 1000.0),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 时长 + 平均配速
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem("时长", formatDuration(session.totalDurationSec))
                Spacer(modifier = Modifier.width(24.dp))
                StatItem("平均配速", session.avgPaceSecPerKm?.let { MetricsCalculator.formatPace(it) } ?: "--")
                Spacer(modifier = Modifier.width(24.dp))
                StatItem("平均心率", session.avgHeartRate?.let { "${it} bpm" } ?: "--")
            }

            // 展开：分段详情
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    if (session.splits.isEmpty()) {
                        Text("无分段数据", style = MaterialTheme.typography.bodySmall)
                    } else {
                        session.splits.forEach { split ->
                            SplitRow(split)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitRow(split: Split) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "第${split.index}公里",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = formatDuration(split.durationSec),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = MetricsCalculator.formatPace(split.avgPaceSecPerKm),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = split.avgHeartRate?.let { "${it} bpm" } ?: "--",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINESE)
    return sdf.format(Date(millis))
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}时${m}分${s}秒" else "${m}分${s.toString().padStart(2, '0')}秒"
}
