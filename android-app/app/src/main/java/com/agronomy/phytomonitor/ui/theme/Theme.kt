package com.agronomy.phytomonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Leaf40,
    onPrimary = Color.White,
    primaryContainer = Leaf90,
    onPrimaryContainer = Leaf10,
    secondary = Moss40,
    onSecondary = Color.White,
    secondaryContainer = Moss90,
    onSecondaryContainer = Moss30,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber30,
    error = Rust40,
    onError = Color.White,
    errorContainer = Rust90,
    onErrorContainer = Rust30,
    background = Field98,
    onBackground = Ink10,
    surface = Field98,
    onSurface = Ink10,
    surfaceVariant = Field90,
    onSurfaceVariant = Ink30,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Field95,
    surfaceContainerHigh = Field92,
    surfaceContainerHighest = Field90,
    outline = Color(0xFF717971),
    outlineVariant = Ink80,
)

private val DarkColors = darkColorScheme(
    primary = Leaf80,
    onPrimary = Leaf20,
    primaryContainer = Leaf30,
    onPrimaryContainer = Leaf90,
    secondary = Moss80,
    onSecondary = Color(0xFF223526),
    secondaryContainer = Moss30,
    onSecondaryContainer = Moss90,
    tertiary = Amber80,
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    error = Rust80,
    onError = Rust30,
    errorContainer = Rust30,
    onErrorContainer = Rust90,
    background = Field6,
    onBackground = Ink90,
    surface = Field6,
    onSurface = Ink90,
    surfaceVariant = Ink30,
    onSurfaceVariant = Ink80,
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Field12,
    surfaceContainerHigh = Field17,
    surfaceContainerHighest = Field22,
    outline = Color(0xFF8A938A),
    outlineVariant = Ink30,
)

private val AgroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Fixed brand colours instead of Android 12 dynamic colour: the app is shown to the commission
 * and used outdoors, so it must look the same and stay readable on every phone.
 */
@Composable
fun AgroVoiceAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = AgroShapes,
        content = content
    )
}
