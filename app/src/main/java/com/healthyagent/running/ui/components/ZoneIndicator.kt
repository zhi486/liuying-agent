package com.healthyagent.running.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.healthyagent.running.core.model.HrZone
import com.healthyagent.running.ui.theme.zoneColor

/**
 * 心率区间五段指示条 — 当前区间加高提亮，其余低调
 */
@Composable
fun ZoneIndicator(
    currentZone: HrZone?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HrZone.entries.forEach { zone ->
            val active = zone == currentZone
            val color = zoneColor(zone)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (active) 10.dp else 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) color else color.copy(alpha = 0.25f)),
            )
        }
    }
}
