package com.tricreta.scopesms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = ScopeGreen,
    onPrimary = Color.White,
    primaryContainer = ScopeGreenBright,
    onPrimaryContainer = Color.White,
    inversePrimary = ScopeGreenDim,
    secondary = ScopeRed,
    onSecondary = Color.White,
    secondaryContainer = ScopeRedBright,
    onSecondaryContainer = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = ScopeGreenDim,
    onPrimary = ScopeGreenDeep,
    primaryContainer = ScopeGreen,
    onPrimaryContainer = ScopeGreenLight,
    inversePrimary = ScopeGreen,
    secondary = ScopeRedBright,
    onSecondary = Color.White,
    secondaryContainer = ScopeRed,
    onSecondaryContainer = Color.White,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = ErrorDark,
    onError = Color.Black,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

/**
 * Dynamic colour is intentionally not wired up: the agent's brand palette
 * should win over the device wallpaper, and Material You would repaint the
 * app differently on every Android 12+ handset.
 */
@Composable
fun ScopeSmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ScopeSmsTypography,
        content = content,
    )
}
