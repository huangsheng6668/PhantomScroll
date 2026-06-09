package com.phantom.scroll.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val DarkColorScheme = darkColorScheme(
    primary = PhantomBlue,
    secondary = PhantomCyan,
    tertiary = PhantomPurple,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun PhantomScrollTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * OverlayTheme is dedicated for window overlays (floating windows).
 * It skips accessing the Activity Window to avoid casting crashes, as the service context is not an Activity.
 */
@Composable
fun OverlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
