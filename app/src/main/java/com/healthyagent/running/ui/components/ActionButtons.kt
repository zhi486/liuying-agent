package com.healthyagent.running.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthyagent.running.ui.theme.Gray500
import com.healthyagent.running.ui.theme.Gray700
import com.healthyagent.running.ui.theme.InkBlack
import com.healthyagent.running.ui.theme.PaperWhite
import com.healthyagent.running.ui.theme.SignalRed
import com.healthyagent.running.ui.theme.VoltGreen

/**
 * 主按钮 — Volt 胶囊，用于"开始跑步"等主动作
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VoltGreen,
            contentColor = InkBlack,
            disabledContainerColor = Gray700,
            disabledContentColor = Gray500,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 次按钮 — 白描边胶囊，用于"暂停"等次要动作
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, PaperWhite),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PaperWhite),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 危险按钮 — 红底胶囊，用于"结束"
 */
@Composable
fun DestructiveActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SignalRed,
            contentColor = PaperWhite,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
