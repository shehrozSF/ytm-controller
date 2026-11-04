package com.shehrozsf.ytmcontroller

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * YouTube Music's palette: near-black background, dark grey surfaces, white text and
 * selections (selected chips are white with black text), grey secondary text, and red as
 * the only accent. Always dark, like YTM.
 */
object YtmColors {
    val Background = Color(0xFF030303)
    val Surface1 = Color(0xFF0F0F0F)
    val Surface2 = Color(0xFF181818)
    val Surface3 = Color(0xFF212121)
    val Surface4 = Color(0xFF282828)
    val Divider = Color(0xFF3F3F3F)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFAAAAAA)
    val Red = Color(0xFFFF0000)
}

private val YtmScheme = darkColorScheme(
    // Primary = white: tab indicator, filled buttons (white pill, black text), progress.
    primary = YtmColors.TextPrimary,
    onPrimary = Color.Black,
    primaryContainer = YtmColors.Surface3,
    onPrimaryContainer = YtmColors.TextPrimary,
    inversePrimary = Color.Black,
    // Selected segmented buttons and chips use secondaryContainer: white with black text.
    secondary = YtmColors.TextSecondary,
    onSecondary = Color.Black,
    secondaryContainer = YtmColors.TextPrimary,
    onSecondaryContainer = Color.Black,
    tertiary = YtmColors.Red,
    onTertiary = Color.White,
    tertiaryContainer = YtmColors.Red,
    onTertiaryContainer = Color.White,
    background = YtmColors.Background,
    onBackground = YtmColors.TextPrimary,
    surface = YtmColors.Background,
    onSurface = YtmColors.TextPrimary,
    surfaceVariant = YtmColors.Surface3,
    onSurfaceVariant = YtmColors.TextSecondary,
    // Same as surface, so elevated components don't pick up a tint from the white primary.
    surfaceTint = YtmColors.Background,
    surfaceDim = YtmColors.Background,
    surfaceBright = YtmColors.Surface4,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = YtmColors.Surface1,
    surfaceContainer = YtmColors.Surface2,
    surfaceContainerHigh = YtmColors.Surface3,
    surfaceContainerHighest = YtmColors.Surface4,
    inverseSurface = YtmColors.TextPrimary,
    inverseOnSurface = Color.Black,
    outline = YtmColors.Divider,
    outlineVariant = YtmColors.Surface4,
    error = Color(0xFFFF4E45),
    onError = Color.Black,
    errorContainer = Color(0xFF5C1210),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

@Composable
fun YtmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = YtmScheme, content = content)
}
