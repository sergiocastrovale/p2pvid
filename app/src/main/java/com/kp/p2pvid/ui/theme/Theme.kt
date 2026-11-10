package com.kp.p2pvid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object P2PColors {
    val Background = Color(0xFF0B0C0F)
    val Surface = Color(0xFF15171C)
    val Accent = Color(0xFF7CC4FF)
    val Danger = Color(0xFFE5484D)
    val Rec = Color(0xFFFF3B30)
    val Scrim = Color(0x99000000)
    val Glass = Color(0xB31C1E24)
    val Muted = Color(0xFF9A9DA6)
}

private val scheme = darkColorScheme(
    primary = P2PColors.Accent,
    onPrimary = Color(0xFF00213A),
    primaryContainer = Color(0xFF1D3A55),
    onPrimaryContainer = Color(0xFFD3E8FF),
    secondaryContainer = Color(0xFF2A2D35),
    onSecondaryContainer = Color(0xFFE6E8EE),
    error = P2PColors.Danger,
    background = P2PColors.Background,
    onBackground = Color(0xFFE8E9ED),
    surface = P2PColors.Surface,
    onSurface = Color(0xFFE8E9ED),
    surfaceVariant = Color(0xFF24262D),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainer = Color(0xFF1A1C21),
    surfaceContainerHigh = Color(0xFF202228),
    surfaceContainerHighest = Color(0xFF282A31),
    surfaceContainerLow = Color(0xFF15171C),
    outline = Color(0xFF3A3D45),
    outlineVariant = Color(0xFF2A2C32),
)

@Composable
fun P2PTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
