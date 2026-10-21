package com.forddiag.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDeep = Color(0xFF0C1210)
val Bg = Color(0xFF121A17)
val BgElevated = Color(0xFF1A2420)
val Line = Color(0xFF2A3A33)
val TextPrimary = Color(0xFFE8F0EB)
val TextMuted = Color(0xFF8FA399)
val Accent = Color(0xFF3DD68C)
val AccentDim = Color(0xFF2A9A64)
val Warn = Color(0xFFE8B84A)
val Danger = Color(0xFFE86A5A)

private val FordColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = BgDeep,
    secondary = AccentDim,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = Bg,
    onSurface = TextPrimary,
    surfaceVariant = BgElevated,
    onSurfaceVariant = TextMuted,
    outline = Line,
    error = Danger,
)

@Composable
fun FordDiagTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FordColorScheme,
        content = content,
    )
}
