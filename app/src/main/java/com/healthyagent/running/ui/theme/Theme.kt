package com.healthyagent.running.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = VoltGreen,
    onPrimary = InkBlack,
    secondary = Gray500,
    onSecondary = InkBlack,
    background = InkBlack,
    onBackground = PaperWhite,
    surface = SurfaceDark,
    onSurface = PaperWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = Gray500,
    error = SignalRed,
    onError = PaperWhite,
    outline = Gray700,
)

@Composable
fun HealthyAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = HealthyAgentTypography,
        content = content,
    )
}
