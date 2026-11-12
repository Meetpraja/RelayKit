package com.relaykit.demo.core.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BrandLight,
    secondary = Color(0xFF465F91),
    background = CanvasLight,
    surface = Color.White,
    surfaceVariant = Color(0xFFE9ECF2),
    outlineVariant = Color(0xFFDDE1E8),
)
private val DarkColors = darkColorScheme(
    primary = BrandDark,
    secondary = Color(0xFFB8C8EA),
    background = CanvasDark,
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF282C34),
    outlineVariant = Color(0xFF363B45),
)

/** RelayKit app theme follows the system light/dark preference. */
@Composable
fun RelayKitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
}
