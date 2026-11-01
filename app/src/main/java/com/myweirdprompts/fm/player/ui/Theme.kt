package com.myweirdprompts.fm.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colours from radio/styles.css (the PWA), so both builds look like one product.
private val Dark = darkColorScheme(
    primary = Color(0xFFE8EDF5), onPrimary = Color(0xFF0B1220),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF0B1220), onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF16202F), onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainer = Color(0xFF16202F), secondaryContainer = Color(0xFF243247),
    outline = Color(0xFF2A3647),
)
private val Light = lightColorScheme(
    primary = Color(0xFF0F172A), onPrimary = Color.White,
    background = Color(0xFFF8FAFC), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF64748B),
    surfaceContainer = Color.White, secondaryContainer = Color(0xFFE2E8F0),
    outline = Color(0xFFE2E8F0),
)

@Composable
fun MwpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
