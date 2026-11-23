package app.danyfy.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private fun spotifyColorScheme(accent: Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = Color.Black,
    primaryContainer = accent,
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    tertiary = accent,
    onTertiary = Color.Black,
    background = AppBackground,
    onBackground = Color.White,
    surface = AppBackground,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent,
    surfaceDim = AppBackground,
    surfaceBright = Color(0xFF2A2A2A),
    surfaceContainerLowest = AppBackground,
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF3E3E3E),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = accent,
    outline = Color(0xFF727272),
    outlineVariant = Color(0xFF3E3E3E),
    error = Color(0xFFF3727F),
    onError = Color.Black,
    scrim = Color.Black,
)

@Composable
fun SpotuiTheme(
    content: @Composable () -> Unit,
) {
    val sans: FontFamily = appSansFamily()
    val typo = appTypography(sans)
    val visualScheme = spotifyColorScheme(AppAccent)

    MaterialTheme(
        colorScheme = visualScheme,
        typography = typo,
    ) {
        ProvideTextStyle(
            value = LocalTextStyle.current.copy(
                fontFamily = sans,
                letterSpacing = (-0.2).sp,
            ),
            content = content,
        )
    }
}
