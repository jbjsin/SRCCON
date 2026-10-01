package dev.sphc.srccon.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeMode {
    LIGHT,
    DARK,
    NIGHT;

    fun next(): AppThemeMode = entries[(ordinal + 1) % entries.size]
}
private val LightColors = lightColorScheme(
    primary = Color(0xFF6242B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF211047),
    secondary = Color(0xFF645A70),
    background = Color(0xFFFFF8FF),
    onBackground = Color(0xFF1D1A20),
    surface = Color(0xFFFFF8FF),
    onSurface = Color(0xFF1D1A20),
    surfaceVariant = Color(0xFFE9E2EB),
    onSurfaceVariant = Color(0xFF4A454E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1A05D),
    onPrimary = Color(0xFF211F14),
    primaryContainer = Color(0xFF4D452A),
    onPrimaryContainer = Color(0xFFE5D48E),
    secondary = Color(0xFFC7BA83),
    background = Color(0xFF111216),
    onBackground = Color(0xFFE6E2E8),
    surface = Color(0xFF18191E),
    onSurface = Color(0xFFE6E2E8),
    surfaceVariant = Color(0xFF2A2B31),
    onSurfaceVariant = Color(0xFFC9C5CD),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFB4333A),
    onPrimary = Color(0xFF130000),
    primaryContainer = Color(0xFF330608),
    onPrimaryContainer = Color(0xFFD9565B),
    secondary = Color(0xFFA33A40),
    onSecondary = Color(0xFF120000),
    background = Color.Black,
    onBackground = Color(0xFFD9565B),
    surface = Color(0xFF050000),
    onSurface = Color(0xFFD9565B),
    surfaceVariant = Color(0xFF1B0405),
    onSurfaceVariant = Color(0xFFBD454B),
    surfaceDim = Color(0xFF030000),
    surfaceBright = Color(0xFF300A0C),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF100203),
    surfaceContainer = Color(0xFF170405),
    surfaceContainerHigh = Color(0xFF200607),
    surfaceContainerHighest = Color(0xFF29090B),
    outline = Color(0xFF7A2025),
    outlineVariant = Color(0xFF481014),
    error = Color(0xFFD3454B),
    onError = Color(0xFF160000),
)

@Composable
fun SrcconTheme(mode: AppThemeMode, content: @Composable () -> Unit) {
    val colors = when (mode) {
        AppThemeMode.LIGHT -> LightColors
        AppThemeMode.DARK -> DarkColors
        AppThemeMode.NIGHT -> NightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
