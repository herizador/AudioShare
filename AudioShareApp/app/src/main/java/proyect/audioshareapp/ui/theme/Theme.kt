package com.audioshare.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CyberpunkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Black,
    primaryContainer = NeonGreen.copy(alpha = 0.15f),
    onPrimaryContainer = NeonGreen,
    secondary = Violet,
    onSecondary = Black,
    secondaryContainer = Violet.copy(alpha = 0.15f),
    onSecondaryContainer = Violet,
    tertiary = NeonRed,
    onTertiary = Black,
    background = Black,
    onBackground = White,
    surface = DarkSurface,
    onSurface = White,
    surfaceVariant = DarkCard,
    onSurfaceVariant = WhiteDim,
    outline = WhiteFaint,
    error = NeonRed,
    onError = Black,
    errorContainer = NeonRed.copy(alpha = 0.15f),
    onErrorContainer = NeonRed
)

@Composable
fun AudioShareTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Black.toArgb()
            window.navigationBarColor = Black.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = CyberpunkColors,
        typography = Typography,
        content = content
    )
}
