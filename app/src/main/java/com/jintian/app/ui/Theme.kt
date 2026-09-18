package com.jintian.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF315B3E), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EDDF), onPrimaryContainer = Color(0xFF24352A),
    secondaryContainer = Color(0xFFE6EDDF), onSecondaryContainer = Color(0xFF24352A),
    background = Color(0xFFF6F5F0), onBackground = Color(0xFF24352A),
    surface = Color.White, onSurface = Color(0xFF24352A),
    surfaceVariant = Color(0xFFEDF0E8), onSurfaceVariant = Color(0xFF637067),
    outlineVariant = Color(0xFFDCE3DA),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB9D9B0), onPrimary = Color(0xFF203225),
    primaryContainer = Color(0xFF313E30), onPrimaryContainer = Color(0xFFEDF3EC),
    secondaryContainer = Color(0xFF313E30), onSecondaryContainer = Color(0xFFEDF3EC),
    background = Color(0xFF191D19), onBackground = Color(0xFFEDF3EC),
    surface = Color(0xFF242A25), onSurface = Color(0xFFEDF3EC),
    surfaceVariant = Color(0xFF313A32), onSurfaceVariant = Color(0xFFB4C0B6),
    outlineVariant = Color(0xFF414C43),
)
@Composable
fun JintianTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if(darkTheme) Dark else Light, content = content)
}
