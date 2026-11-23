package app.danyfy.shared.data

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

enum class Look {
    CLASSIC,
    IMMERSIVE;

    companion object {
        fun of(name: String?): Look = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CLASSIC
    }
}

@Serializable
data class TrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Long = 0L,
    val durationText: String = "",
    val artworkUrl: String? = null,
    val explicit: Boolean = false,
)

@Serializable
data class CollectionCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val kind: String = "playlist", // playlist, album, artist, mix
)

@Serializable
data class HomeFeedShelf(
    val title: String,
    val subtitle: String? = null,
    val items: List<CollectionCard> = emptyList(),
)

@Serializable
data class BrowseCategory(
    val id: String,
    val title: String,
    val colorHex: String? = null,
    val params: String? = null,
)

@Serializable
data class CollectionPage(
    val id: String,
    val title: String,
    val owner: String = "",
    val description: String = "",
    val artworkUrl: String? = null,
    val tracks: List<TrackItem> = emptyList(),
    val isAlbum: Boolean = false,
    val isArtist: Boolean = false,
)

@Serializable
data class AudioStreamCandidate(
    val url: String,
    val itag: Int = 0,
    val mimeType: String = "audio/mp4",
    val bitrate: Int = 128000,
    val userAgent: String = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
)

@Serializable
data class ResolvedStreams(
    val trackId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long = 0L,
    val candidates: List<AudioStreamCandidate> = emptyList(),
)

fun fallbackArtFor(id: String, label: String): SampleArt {
    val h = kotlin.math.abs(id.hashCode())
    val palettes = listOf(
        Pair(Color(0xFF1E3A8A), Color(0xFF3B82F6)), // Deep blue -> bright blue
        Pair(Color(0xFF831843), Color(0xFFEC4899)), // Deep pink -> rose
        Pair(Color(0xFF14532D), Color(0xFF22C55E)), // Deep green -> emerald
        Pair(Color(0xFF581C87), Color(0xFFA855F7)), // Deep purple -> violet
        Pair(Color(0xFF7C2D12), Color(0xFFF97316)), // Deep amber -> orange
        Pair(Color(0xFF0F766E), Color(0xFF14B8A6)), // Deep teal -> cyan
        Pair(Color(0xFF312E81), Color(0xFF6366F1)), // Indigo
    )
    val pair = palettes[h % palettes.size]
    val shortLabel = if (label.length > 20) label.take(18) + "…" else label
    return SampleArt(start = pair.first, end = pair.second, label = shortLabel)
}
