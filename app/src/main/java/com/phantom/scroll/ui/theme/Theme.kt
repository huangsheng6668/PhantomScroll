package com.phantom.scroll.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark neon-glow theme for the permission guide page (AGENTS.md design spec):
 * background #0F0F12, cards #1E1E24, accents #00E5FF / #2979FF.
 *
 * The native floating overlay keeps its own light/green high-contrast Material theme
 * (res/values/themes.xml → Theme.PhantomScroll.Overlay); the two UI surfaces are
 * intentionally independent and this Compose scheme must not leak into the overlay.
 */
private val DarkColorScheme = darkColorScheme(
    primary = PhantomCyan,
    secondary = PhantomBlue,
    tertiary = WarningOrange,
    background = DarkBackground,
    surface = DarkSurface,
    // Cyan is a bright accent: dark text on primary keeps contrast accessible.
    onPrimary = Color(0xFF00262B),
    onSecondary = Color.White,
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
