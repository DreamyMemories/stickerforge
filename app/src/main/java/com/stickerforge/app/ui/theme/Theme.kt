package com.stickerforge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForgeGreen = Color(0xFF3DDC97)
private val ForgeGreenDark = Color(0xFF1E7A5A)

private val DarkColors = darkColorScheme(
    primary = ForgeGreen,
    onPrimary = Color(0xFF04261A),
    primaryContainer = Color(0xFF16412F),
    onPrimaryContainer = Color(0xFFB6F2D4),
    secondary = Color(0xFF9AD5FF),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE5E5E7),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFE5E5E7),
    surfaceVariant = Color(0xFF26262E),
    onSurfaceVariant = Color(0xFFC6C6CE),
    outline = Color(0xFF4A4A55),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = ForgeGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F2D4),
    onPrimaryContainer = Color(0xFF04261A),
    secondary = Color(0xFF2C6E9B),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF1A1A1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1E),
    surfaceVariant = Color(0xFFE7E7EC),
    onSurfaceVariant = Color(0xFF45454E),
    outline = Color(0xFF9A9AA5),
    error = Color(0xFFBA1A1A),
)

@Composable
fun StickerForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
