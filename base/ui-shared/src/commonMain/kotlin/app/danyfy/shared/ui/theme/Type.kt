package app.danyfy.shared.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.app_sans
import org.jetbrains.compose.resources.Font

var systemFontEnabled by mutableStateOf(false)

/**
 * UI typeface: Manrope (SIL Open Font License 1.1), one variable font file picked through its
 * "wght" axis for weights 400..900, identical to Android's Type.kt.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun sans(weight: Int) = Font(
    Res.font.app_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
    ),
)

@Composable
fun appSansFamily(): FontFamily {
    if (systemFontEnabled) return FontFamily.Default
    return FontFamily(
        sans(400),
        sans(500),
        sans(600),
        sans(700),
        sans(800),
        sans(900),
    )
}

fun appTypography(family: FontFamily): Typography {
    val base = TextStyle(fontFamily = family)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold)
    return Typography(
        displayLarge = title,
        displayMedium = title,
        displaySmall = title,
        headlineLarge = title,
        headlineMedium = title,
        headlineSmall = title,
        titleLarge = base.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.copy(fontWeight = FontWeight.Bold),
        titleSmall = base.copy(fontWeight = FontWeight.Bold),
        bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base,
        bodySmall = base,
        labelLarge = base,
        labelMedium = base,
        labelSmall = base,
    )
}
