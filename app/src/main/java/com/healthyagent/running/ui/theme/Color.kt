package com.healthyagent.running.ui.theme

import androidx.compose.ui.graphics.Color
import com.healthyagent.running.core.model.HrZone

// ── 运动品牌风骨架（黑白高对比）──
val InkBlack = Color(0xFF0A0A0A)        // 主背景（近纯黑）
val SurfaceDark = Color(0xFF161616)     // 卡片/消息条底
val SurfaceElevated = Color(0xFF232323) // 浮起元素
val PaperWhite = Color(0xFFFAFAFA)      // 主文字/大数字
val Gray500 = Color(0xFF8A8A8A)         // 次要文字
val Gray700 = Color(0xFF3A3A3A)         // 分隔线/未激活轨道

// ── 强调色（唯一交互强调）──
val VoltGreen = Color(0xFFD4FF3F)       // 主按钮、流萤标识、正常状态
val SignalRed = Color(0xFFFF453A)       // URGENT / 危险专用

// ── 心率区间功能色（收紧饱和度，黑底统一明度）──
val HrZone1 = Color(0xFF5B8E7D)  // Z1 热身 — 青绿灰
val HrZone2 = Color(0xFF3FA7D6)  // Z2 燃脂 — 天蓝
val HrZone3 = Color(0xFFF2C14E)  // Z3 有氧 — 暖黄
val HrZone4 = Color(0xFFF2813F)  // Z4 阈值 — 橙
val HrZone5 = SignalRed          // Z5 极限 — 红（与危险语义统一）

/** 心率区间 → 功能色（含无数据时的中性色） */
fun zoneColor(zone: HrZone?): Color = when (zone) {
    HrZone.Z1 -> HrZone1
    HrZone.Z2 -> HrZone2
    HrZone.Z3 -> HrZone3
    HrZone.Z4 -> HrZone4
    HrZone.Z5 -> HrZone5
    null -> Gray500
}

// ── 过渡兼容别名（批次 4 三屏重写后删除）──
val PrimaryDark = InkBlack
val PrimaryAccent = VoltGreen
val OnSurfaceDark = PaperWhite
val TextSecondary = Gray500
