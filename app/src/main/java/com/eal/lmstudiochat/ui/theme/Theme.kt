package com.eal.lmstudiochat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF10120F)
val Panel = Color(0xFF1A1D18)
val PanelHigh = Color(0xFF262A22)
val UserBubble = Color(0xFF3C4C30)
val AssistantBubble = Color(0xFF22261E)
val Accent = Color(0xFFC6E07A)
val InkText = Color(0xFFE7EADF)
val Muted = Color(0xFF9AA392)
val Danger = Color(0xFFE08A78)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF17200A),
    secondary = Accent,
    background = Ink,
    onBackground = InkText,
    surface = Panel,
    onSurface = InkText,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    error = Danger,
    outline = Color(0xFF3A4034),
)

@Composable
fun LmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
