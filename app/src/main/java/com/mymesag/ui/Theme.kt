package com.mymesag.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 现代化蓝色主题
private val PrimaryBlue = Color(0xFF2196F3)
private val PrimaryBlueLight = Color(0xFFE3F2FD)
private val PrimaryBlueDark = Color(0xFF1565C0)
private val SecondaryBlue = Color(0xFF03A9F4)
private val White = Color(0xFFFFFFFF)
private val SurfaceLight = Color(0xFFF8F9FA)
private val TextPrimary = Color(0xFF212121)
private val TextSecondary = Color(0xFF757575)

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = White,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = PrimaryBlueDark,
    secondary = SecondaryBlue,
    onSecondary = White,
    secondaryContainer = Color(0xFFE1F5FE),
    onSecondaryContainer = Color(0xFF01579B),
    tertiary = Color(0xFF4CAF50),
    onTertiary = White,
    error = Color(0xFFE53935),
    onError = White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFC62828),
    background = White,
    onBackground = TextPrimary,
    surface = White,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFE0E0E0)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFF81D4FA),
    onSecondary = Color(0xFF003549),
    secondaryContainer = Color(0xFF01579B),
    onSecondaryContainer = Color(0xFFE1F5FE),
    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF00390F),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFC4C6C8),
    outline = Color(0xFF4A4A4A)
)

@Composable
fun MyMesagTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
