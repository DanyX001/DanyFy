package app.danyfy.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.danyfy.shared.data.SampleArt
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.downloads_cover
import app.danyfy.shared.resources.folder_cover
import app.danyfy.shared.resources.local_files_cover
import org.jetbrains.compose.resources.painterResource

/**
 * Renders offline placeholder cover art matching Spotify's square/circular tiles with a diagonal
 * gradient and subtle monogram so no network requests are needed in milestone 1.
 */
@Composable
fun SampleArtwork(
    art: SampleArt,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    specialKind: String? = null,
) {
    val specialRes = when (specialKind) {
        "downloads" -> Res.drawable.downloads_cover
        "local" -> Res.drawable.local_files_cover
        "folder" -> Res.drawable.folder_cover
        else -> null
    }
    if (specialRes != null) {
        androidx.compose.foundation.Image(
            painter = painterResource(specialRes),
            contentDescription = null,
            modifier = modifier.clip(shape),
        )
        return
    }
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(art.start, art.end))),
    ) {
        val sp = (maxWidth.value * 0.28f).coerceIn(11f, 64f).sp
        Text(
            text = art.label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
        )
    }
}

/** Card / player backdrop tint derived from a cover's dominant color, matching Android Palette. */
fun artworkTint(art: SampleArt): Color = lerp(art.start, Color(0xFF181818), 0.32f)
