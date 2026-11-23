package app.danyfy.shared.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.danyfy.shared.data.fallbackArtFor
import app.danyfy.shared.platform.PlatformRegistry
import app.danyfy.shared.platform.decodeImageWithColor

private object ArtworkCache {
    private const val MAX_ENTRIES = 120
    private val memoryMap = mutableMapOf<String, Pair<ImageBitmap, Color>>()
    private val keyOrder = mutableListOf<String>()

    fun get(url: String): Pair<ImageBitmap, Color>? = memoryMap[url]

    fun put(url: String, value: Pair<ImageBitmap, Color>) {
        if (memoryMap.containsKey(url)) {
            keyOrder.remove(url)
        } else if (memoryMap.size >= MAX_ENTRIES) {
            val oldest = if (keyOrder.isNotEmpty()) keyOrder.removeAt(0) else null
            if (oldest != null) memoryMap.remove(oldest)
        }
        memoryMap[url] = value
        keyOrder.add(url)
    }
}

@Composable
fun MediaArtwork(
    artworkUrl: String?,
    title: String,
    id: String,
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    contentScale: ContentScale = ContentScale.Crop,
) {
    var cachedEntry by remember(artworkUrl) {
        mutableStateOf(artworkUrl?.let { ArtworkCache.get(it) })
    }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank() || cachedEntry != null) return@LaunchedEffect
        try {
            val bytes = PlatformRegistry.httpGetBytes(artworkUrl)
            if (bytes != null) {
                val decoded = decodeImageWithColor(bytes)
                if (decoded != null) {
                    ArtworkCache.put(artworkUrl, decoded)
                    cachedEntry = decoded
                }
            }
        } catch (_: Throwable) {}
    }

    val entry = cachedEntry
    if (entry != null) {
        Image(
            bitmap = entry.first,
            contentDescription = title,
            contentScale = contentScale,
            modifier = modifier.clip(shape),
        )
    } else {
        val sample = remember(id, title) { fallbackArtFor(id, title) }
        SampleArtwork(
            art = sample,
            shape = shape,
            modifier = modifier,
        )
    }
}

@Composable
fun rememberCoverTint(
    artworkUrl: String?,
    id: String,
    title: String,
    defaultColor: Color = Color(0xFF1E3A8A),
): Color {
    var tint by remember(artworkUrl, id) {
        val cached = artworkUrl?.let { ArtworkCache.get(it) }
        mutableStateOf(cached?.second ?: fallbackArtFor(id, title).start)
    }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect
        val cached = ArtworkCache.get(artworkUrl)
        if (cached != null) {
            tint = cached.second
            return@LaunchedEffect
        }
        try {
            val bytes = PlatformRegistry.httpGetBytes(artworkUrl)
            if (bytes != null) {
                val decoded = decodeImageWithColor(bytes)
                if (decoded != null) {
                    ArtworkCache.put(artworkUrl, decoded)
                    tint = decoded.second
                }
            }
        } catch (_: Throwable) {}
    }

    return tint
}
