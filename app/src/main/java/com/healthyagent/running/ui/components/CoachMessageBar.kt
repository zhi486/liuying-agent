package com.healthyagent.running.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.ui.theme.PaperWhite
import com.healthyagent.running.ui.theme.SurfaceDark

/**
 * 教练消息的视觉样式
 *
 * @param accentColor 左边条 + 标签颜色
 * @param label 消息来源标签（如"流萤"）
 * @param pulsing 是否脉冲呼吸（URGENT 用，表达"她担心到认真起来"）
 */
data class CoachMessageStyle(
    val accentColor: Color,
    val label: String,
    val pulsing: Boolean,
)

// ═══════════════════════════════════════════════════════════════════
// TODO(开拓者实现): 优先级 → 视觉样式映射
//
// 这个函数决定了流萤每种消息的"视觉性格"，是整个消息条的灵魂：
// - URGENT（心率危险等）：她担心到认真起来 —— 该多醒目？
// - COACH（LLM 每公里建议）：她平时轻声说的话 —— 用什么标识？
// - SPLIT（每公里播报）/ APP（开始结束提示）：偏系统感的消息
//
// 可用色令牌：VoltGreen / SignalRed / Gray500（见 theme/Color.kt）
// ═══════════════════════════════════════════════════════════════════
fun coachMessageStyle(priority: SpeechPriority): CoachMessageStyle = TODO("交给开拓者决定")

/**
 * 流萤消息条 — TTS 实际播报内容的屏幕上镜像
 *
 * 布局：左侧 4dp 强调条（URGENT 时脉冲）+ 来源标签 + 消息正文。
 */
@Composable
fun CoachMessageBar(
    text: String,
    style: CoachMessageStyle,
    modifier: Modifier = Modifier,
) {
    // URGENT 时强调色脉冲呼吸
    val accentAlpha = if (style.pulsing) {
        val transition = rememberInfiniteTransition(label = "coachPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        alpha
    } else {
        1f
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceDark,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧强调条（跟随内容高度）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(style.accentColor.copy(alpha = accentAlpha)),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = style.accentColor.copy(alpha = accentAlpha),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PaperWhite,
                )
            }
        }
    }
}
