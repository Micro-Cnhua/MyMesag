package com.mymesag.ui

import androidx.compose.material3.MaterialTheme
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

@Composable
fun MyMesagTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
