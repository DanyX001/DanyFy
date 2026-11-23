package app.danyfy.shared.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

@Immutable
data class SampleArt(
    val start: Color,
    val end: Color,
    val label: String,
)

@Immutable
data class SampleSong(
    val id: String,
    val title: String,
    val singer: String,
    val album: String,
    val durationMs: Long,
    val art: SampleArt,
    val explicit: Boolean = false,
)

@Immutable
data class SampleHomeItem(
    val id: String,
    val kind: Kind,
    val name: String,
    val subtitle: String,
    val art: SampleArt,
) {
    enum class Kind { Playlist, Album, Artist, Show }
}

@Immutable
data class SampleHomeSection(
    val title: String,
    val items: List<SampleHomeItem>,
)

@Immutable
data class SampleBrowseCategory(
    val key: String,
    val title: String,
    val color: Color,
    val art: SampleArt,
)

@Immutable
data class SampleLibraryEntry(
    val id: String,
    val name: String,
    val subtitle: String,
    val isPlaylist: Boolean,
    val isPinned: Boolean = false,
    val isCreatorMe: Boolean = false,
    val art: SampleArt,
    val kind: LibraryKind = LibraryKind.PLAYLIST,
) {
    enum class LibraryKind { LIKED, LOCAL_FILES, DOWNLOADS, FOLDER, PLAYLIST, ALBUM, PODCAST }
}

enum class LibrarySort {
    CUSTOM,
    RECENTS,
    RECENTLY_ADDED,
    ALPHABETICAL,
    CREATOR,
}

enum class LibraryChip {
    ALL,
    PLAYLISTS,
    BY_ME,
    ARTISTS,
    ALBUMS,
    PODCASTS,
    SONGS,
}

enum class AudioQualityOption {
    AUTO,
    LOW,
    NORMAL,
    HIGH,
    VERY_HIGH,
}

enum class DownloadQualityOption {
    OFF,
    SMALL,
    CD,
    HI_RES,
    ASK,
}

/**
 * Sample catalog & interactive UI state for iOS milestone 1 (offline preview without playback or network).
 * All tracks, playlists, albums, artists, categories and settings state are local in-memory Compose states
 * so taps, likes, queue navigation, search, library filters, mini-player and player transitions all feel alive.
 */
object SampleCatalog {

    val songs: List<SampleSong> = listOf(
        SampleSong("t1", "Midnight Signal", "Klara Lind & The Echoes", "Night Drive Sessions", 214_000L, SampleArt(Color(0xFF1D4E5E), Color(0xFF0A1926), "MS")),
        SampleSong("t2", "Velvet Horizon", "Milo Thorne", "Late Summer Tapes", 198_000L, SampleArt(Color(0xFF8B2E4B), Color(0xFF280B16), "VH"), explicit = true),
        SampleSong("t3", "Glass Harbor", "Nordic Pulse", "Harbor Lights", 242_000L, SampleArt(Color(0xFF235D43), Color(0xFF0C2218), "GH")),
        SampleSong("t4", "Paper Satellites", "Sora Takahashi", "Tokyo Rain", 186_000L, SampleArt(Color(0xFF4B3D8E), Color(0xFF151030), "PS")),
        SampleSong("t5", "Copper Sun", "Elena Vance", "Gold & Rust", 229_000L, SampleArt(Color(0xFFAD5A1D), Color(0xFF341806), "CS")),
        SampleSong("t6", "Static In Bloom", "Dorian Vale", "Analog Ghosts", 205_000L, SampleArt(Color(0xFF6B2D78), Color(0xFF200A28), "SB"), explicit = true),
        SampleSong("t7", "Silver Overpass", "Klara Lind & The Echoes", "Night Drive Sessions", 247_000L, SampleArt(Color(0xFF285578), Color(0xFF0D1D2C), "SO")),
        SampleSong("t8", "Cold Espresso", "Juno Collective", "3AM Roastery", 174_000L, SampleArt(Color(0xFF6E4730), Color(0xFF24150D), "CE")),
        SampleSong("t9", "Blue Hour Transit", "Nordic Pulse", "Harbor Lights", 261_000L, SampleArt(Color(0xFF1E5288), Color(0xFF081930), "BH")),
        SampleSong("t10", "Halogen Hearts", "Milo Thorne", "Late Summer Tapes", 211_000L, SampleArt(Color(0xFF9E3A2D), Color(0xFF330F0B), "HH")),
        SampleSong("t11", "Low Tide FM", "Aria Solis", "Coastal Dial", 193_000L, SampleArt(Color(0xFF1A6B69), Color(0xFF082221), "LT")),
        SampleSong("t12", "Neon Escalator", "Sora Takahashi", "Tokyo Rain", 189_000L, SampleArt(Color(0xFF7D2965), Color(0xFF26091F), "NE"), explicit = true),
        SampleSong("t13", "Cedar & Vinyl", "Rowan Hayes", "Sunday Crates", 235_000L, SampleArt(Color(0xFF7A5F24), Color(0xFF251C09), "CV")),
        SampleSong("t14", "Rain On Platform 4", "Dorian Vale", "Analog Ghosts", 252_000L, SampleArt(Color(0xFF364F6B), Color(0xFF101924), "RP")),
        SampleSong("t15", "Orbiting Still", "Elena Vance", "Gold & Rust", 208_000L, SampleArt(Color(0xFF93424A), Color(0xFF2E1216), "OS")),
        SampleSong("t16", "Amber Streetlights", "Juno Collective", "3AM Roastery", 182_000L, SampleArt(Color(0xFFB36B1D), Color(0xFF361E05), "AS")),
        SampleSong("t17", "Subtle Static", "Aria Solis", "Coastal Dial", 221_000L, SampleArt(Color(0xFF3B6648), Color(0xFF112116), "SS")),
        SampleSong("t18", "Last Train East", "Rowan Hayes", "Sunday Crates", 244_000L, SampleArt(Color(0xFF534078), Color(0xFF181126), "LT")),
        SampleSong("t19", "Daylight Fade", "Klara Lind & The Echoes", "City After Dark", 196_000L, SampleArt(Color(0xFF2A6673), Color(0xFF0B1F24), "DF")),
        SampleSong("t20", "Clear Frequency", "Nordic Pulse", "Northern Circuits", 232_000L, SampleArt(Color(0xFF1B5E42), Color(0xFF071F15), "CF")),
    )

    val artists: List<SampleHomeItem> = listOf(
        SampleHomeItem("art1", SampleHomeItem.Kind.Artist, "Klara Lind & The Echoes", "Artist", SampleArt(Color(0xFF216278), Color(0xFF0B222A), "KL")),
        SampleHomeItem("art2", SampleHomeItem.Kind.Artist, "Milo Thorne", "Artist", SampleArt(Color(0xFF8C324F), Color(0xFF2B0E18), "MT")),
        SampleHomeItem("art3", SampleHomeItem.Kind.Artist, "Nordic Pulse", "Artist", SampleArt(Color(0xFF1D6B4B), Color(0xFF082217), "NP")),
        SampleHomeItem("art4", SampleHomeItem.Kind.Artist, "Sora Takahashi", "Artist", SampleArt(Color(0xFF524196), Color(0xFF181232), "ST")),
        SampleHomeItem("art5", SampleHomeItem.Kind.Artist, "Elena Vance", "Artist", SampleArt(Color(0xFFB56022), Color(0xFF381C08), "EV")),
        SampleHomeItem("art6", SampleHomeItem.Kind.Artist, "Dorian Vale", "Artist", SampleArt(Color(0xFF70317E), Color(0xFF220C28), "DV")),
        SampleHomeItem("art7", SampleHomeItem.Kind.Artist, "Juno Collective", "Artist", SampleArt(Color(0xFF785136), Color(0xFF26180F), "JC")),
        SampleHomeItem("art8", SampleHomeItem.Kind.Artist, "Aria Solis", "Artist", SampleArt(Color(0xFF1E706E), Color(0xFF092423), "AS")),
    )

    val albums: List<SampleHomeItem> = listOf(
        SampleHomeItem("alb1", SampleHomeItem.Kind.Album, "Night Drive Sessions", "Album • Klara Lind & The Echoes", SampleArt(Color(0xFF1D4E5E), Color(0xFF0A1926), "ND")),
        SampleHomeItem("alb2", SampleHomeItem.Kind.Album, "Late Summer Tapes", "Album • Milo Thorne", SampleArt(Color(0xFF8B2E4B), Color(0xFF280B16), "LS")),
        SampleHomeItem("alb3", SampleHomeItem.Kind.Album, "Harbor Lights", "Album • Nordic Pulse", SampleArt(Color(0xFF235D43), Color(0xFF0C2218), "HL")),
        SampleHomeItem("alb4", SampleHomeItem.Kind.Album, "Tokyo Rain", "Album • Sora Takahashi", SampleArt(Color(0xFF4B3D8E), Color(0xFF151030), "TR")),
        SampleHomeItem("alb5", SampleHomeItem.Kind.Album, "Gold & Rust", "Album • Elena Vance", SampleArt(Color(0xFFAD5A1D), Color(0xFF341806), "GR")),
        SampleHomeItem("alb6", SampleHomeItem.Kind.Album, "Analog Ghosts", "Album • Dorian Vale", SampleArt(Color(0xFF6B2D78), Color(0xFF200A28), "AG")),
        SampleHomeItem("alb7", SampleHomeItem.Kind.Album, "3AM Roastery", "Album • Juno Collective", SampleArt(Color(0xFF6E4730), Color(0xFF24150D), "3AM")),
        SampleHomeItem("alb8", SampleHomeItem.Kind.Album, "Coastal Dial", "Album • Aria Solis", SampleArt(Color(0xFF1A6B69), Color(0xFF082221), "CD")),
    )

    val playlists: List<SampleHomeItem> = listOf(
        SampleHomeItem("pl_liked", SampleHomeItem.Kind.Playlist, "Liked Songs", "Playlist • 20 songs", SampleArt(Color(0xFF450AF5), Color(0xFF8E8EE5), "♥")),
        SampleHomeItem("pl1", SampleHomeItem.Kind.Playlist, "Daily Mix 1", "Klara Lind, Nordic Pulse, Aria Solis and more", SampleArt(Color(0xFF1DB954), Color(0xFF0A3D1C), "DM1")),
        SampleHomeItem("pl2", SampleHomeItem.Kind.Playlist, "Late Night Synth", "Warm analog pads for focus and after hours", SampleArt(Color(0xFF6C2BD9), Color(0xFF1C093D), "LNS")),
        SampleHomeItem("pl3", SampleHomeItem.Kind.Playlist, "Discover Weekly", "Your weekly mixtape of fresh music", SampleArt(Color(0xFF2D46B9), Color(0xFF0B143D), "DW")),
        SampleHomeItem("pl4", SampleHomeItem.Kind.Playlist, "Release Radar", "Catch all the latest music from artists you follow", SampleArt(Color(0xFF1E3264), Color(0xFF091126), "RR")),
        SampleHomeItem("pl5", SampleHomeItem.Kind.Playlist, "Coffeehouse Acoustic", "Mellow strings and quiet mornings", SampleArt(Color(0xFF8D67AB), Color(0xFF271B30), "CA")),
        SampleHomeItem("pl6", SampleHomeItem.Kind.Playlist, "Nighttime Drive", "Cruising tempo for empty highways", SampleArt(Color(0xFFAF2896), Color(0xFF330A2B), "NTD")),
        SampleHomeItem("pl7", SampleHomeItem.Kind.Playlist, "Deep Focus", "Keep calm and focus with ambient & post-rock", SampleArt(Color(0xFF148A08), Color(0xFF052902), "DF")),
        SampleHomeItem("pl8", SampleHomeItem.Kind.Playlist, "Daily Mix 2", "Milo Thorne, Elena Vance, Dorian Vale and more", SampleArt(Color(0xFFE13300), Color(0xFF451000), "DM2")),
        SampleHomeItem("pl9", SampleHomeItem.Kind.Playlist, "Sunday Crates", "Warm dusty soul & lo-fi cuts", SampleArt(Color(0xFFBC5900), Color(0xFF381A00), "SC")),
        SampleHomeItem("pl10", SampleHomeItem.Kind.Playlist, "Indie Electronic", "Fresh synthpop, house, and left-field beats", SampleArt(Color(0xFF006450), Color(0xFF00211A), "IE")),
        SampleHomeItem("pl11", SampleHomeItem.Kind.Playlist, "Rainy Window", "Soft piano and rain-soaked instrumentals", SampleArt(Color(0xFF503750), Color(0xFF181018), "RW")),
    )

    val shortcuts: List<SampleHomeItem> = listOf(
        playlists[0],
        playlists[1],
        albums[0],
        playlists[2],
        playlists[3],
        albums[1],
        playlists[6],
        playlists[7],
    )

    val homeSections: List<SampleHomeSection> = listOf(
        SampleHomeSection("Made For You", listOf(playlists[1], playlists[8], playlists[3], playlists[4], playlists[10])),
        SampleHomeSection("Jump back in", listOf(albums[0], albums[1], playlists[2], albums[3], albums[4], playlists[6])),
        SampleHomeSection("Recommended albums", listOf(albums[2], albums[5], albums[6], albums[7], albums[3])),
        SampleHomeSection("Suggested artists", artists.take(6)),
    )

    val browseCategories: List<SampleBrowseCategory> = listOf(
        SampleBrowseCategory("new-releases", "New Releases", Color(0xFFE8115B), albums[0].art),
        SampleBrowseCategory("music", "Music", Color(0xFFDC148C), playlists[1].art),
        SampleBrowseCategory("live-events", "Live Events", Color(0xFF8400E7), playlists[2].art),
        SampleBrowseCategory("made-for-you", "Made For You", Color(0xFF1E3264), playlists[3].art),
        SampleBrowseCategory("pop", "Pop", Color(0xFF148A08), playlists[4].art),
        SampleBrowseCategory("hip-hop", "Hip-Hop", Color(0xFFBC5900), albums[1].art),
        SampleBrowseCategory("electronic", "Dance / Electronic", Color(0xFFD84000), playlists[10].art),
        SampleBrowseCategory("indie", "Indie", Color(0xFFE91429), albums[4].art),
        SampleBrowseCategory("focus", "Focus", Color(0xFF503750), playlists[7].art),
        SampleBrowseCategory("chill", "Chill", Color(0xFF477D95), playlists[5].art),
        SampleBrowseCategory("rock", "Rock", Color(0xFF006450), albums[5].art),
        SampleBrowseCategory("discover", "Discover", Color(0xFF8D67AB), playlists[6].art),
    )

    val libraryEntries: List<SampleLibraryEntry> = listOf(
        SampleLibraryEntry("liked", "Liked Songs", "Playlist • 20 songs", isPlaylist = true, isPinned = true, isCreatorMe = true, art = playlists[0].art, kind = SampleLibraryEntry.LibraryKind.LIKED),
        SampleLibraryEntry("local", "Local Files", "", isPlaylist = true, isPinned = true, isCreatorMe = true, art = SampleArt(Color(0xFF2E7D4F), Color(0xFF1A4B2E), "LF"), kind = SampleLibraryEntry.LibraryKind.LOCAL_FILES),
        SampleLibraryEntry("downloads", "Downloaded", "", isPlaylist = true, isPinned = true, isCreatorMe = true, art = SampleArt(Color(0xFF1D6B45), Color(0xFF0E3D26), "DL"), kind = SampleLibraryEntry.LibraryKind.DOWNLOADS),
        SampleLibraryEntry("pl1", playlists[1].name, "Playlist • DanyFy", isPlaylist = true, isPinned = true, isCreatorMe = false, art = playlists[1].art),
        SampleLibraryEntry("pl2", playlists[2].name, "Playlist • Dany", isPlaylist = true, isPinned = false, isCreatorMe = true, art = playlists[2].art),
        SampleLibraryEntry("alb1", albums[0].name, "Album • Klara Lind & The Echoes", isPlaylist = false, isPinned = false, isCreatorMe = false, art = albums[0].art, kind = SampleLibraryEntry.LibraryKind.ALBUM),
        SampleLibraryEntry("pl3", playlists[3].name, "Playlist • DanyFy", isPlaylist = true, isPinned = false, isCreatorMe = false, art = playlists[3].art),
        SampleLibraryEntry("alb2", albums[1].name, "Album • Milo Thorne", isPlaylist = false, isPinned = false, isCreatorMe = false, art = albums[1].art, kind = SampleLibraryEntry.LibraryKind.ALBUM),
        SampleLibraryEntry("pl5", playlists[5].name, "Playlist • Dany", isPlaylist = true, isPinned = false, isCreatorMe = true, art = playlists[5].art),
        SampleLibraryEntry("alb3", albums[2].name, "Album • Nordic Pulse", isPlaylist = false, isPinned = false, isCreatorMe = false, art = albums[2].art, kind = SampleLibraryEntry.LibraryKind.ALBUM),
        SampleLibraryEntry("pl6", playlists[6].name, "Playlist • Dany", isPlaylist = true, isPinned = false, isCreatorMe = true, art = playlists[6].art),
        SampleLibraryEntry("alb4", albums[3].name, "Album • Sora Takahashi", isPlaylist = false, isPinned = false, isCreatorMe = false, art = albums[3].art, kind = SampleLibraryEntry.LibraryKind.ALBUM),
        SampleLibraryEntry("pl9", playlists[9].name, "Playlist • DanyFy", isPlaylist = true, isPinned = false, isCreatorMe = false, art = playlists[9].art),
        SampleLibraryEntry("pl10", playlists[10].name, "Playlist • DanyFy", isPlaylist = true, isPinned = false, isCreatorMe = false, art = playlists[10].art),
    )

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val secStr = if (seconds < 10) "0$seconds" else "$seconds"
        return "$minutes:$secStr"
    }
}

/**
 * Interactive playback state (connected to PlayerController when active, with clean fallback to
 * SampleCatalog for offline preview / screenshots).
 */
object PreviewPlayerState {
    var activeController: PlayerController? = null
    var activeLibraryStore: LibraryStore? = null

    var currentIndex by mutableIntStateOf(0)
    private var internalIsPlaying by mutableStateOf(false)
    private var internalProgress by mutableFloatStateOf(0.32f)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(0) // 0=off, 1=all, 2=one
    var likedIds by mutableStateOf(setOf("t1", "t3", "t5", "t9"))

    var isPlaying: Boolean
        get() = activeController?.isPlaying ?: internalIsPlaying
        set(value) {
            internalIsPlaying = value
            val ctrl = activeController
            if (ctrl != null && ctrl.isPlaying != value) {
                ctrl.togglePlayPause()
            }
        }

    var progressFraction: Float
        get() = activeController?.progress ?: internalProgress
        set(value) {
            internalProgress = value
            activeController?.seekTo(value)
        }

    val currentSong: SampleSong
        get() {
            val track = activeController?.currentTrack
            if (track != null) {
                return SampleSong(
                    id = track.id,
                    title = track.title,
                    singer = track.artist,
                    album = track.album,
                    durationMs = if ((activeController?.durationMs ?: 0L) > 0L) activeController!!.durationMs else track.durationSeconds * 1000L,
                    art = fallbackArtFor(track.id, track.title),
                    explicit = track.explicit,
                )
            }
            return SampleCatalog.songs[currentIndex.coerceIn(SampleCatalog.songs.indices)]
        }

    val prevSong: SampleSong
        get() {
            val ctrl = activeController
            if (ctrl != null && ctrl.queue.isNotEmpty()) {
                val idx = (ctrl.queueIndex - 1 + ctrl.queue.size) % ctrl.queue.size
                val t = ctrl.queue[idx]
                return SampleSong(t.id, t.title, t.artist, t.album, t.durationSeconds * 1000L, fallbackArtFor(t.id, t.title), t.explicit)
            }
            val size = SampleCatalog.songs.size
            return SampleCatalog.songs[(currentIndex - 1 + size) % size]
        }

    val nextSong: SampleSong
        get() {
            val ctrl = activeController
            if (ctrl != null && ctrl.queue.isNotEmpty()) {
                val idx = (ctrl.queueIndex + 1) % ctrl.queue.size
                val t = ctrl.queue[idx]
                return SampleSong(t.id, t.title, t.artist, t.album, t.durationSeconds * 1000L, fallbackArtFor(t.id, t.title), t.explicit)
            }
            return SampleCatalog.songs[(currentIndex + 1) % SampleCatalog.songs.size]
        }

    fun selectSong(song: SampleSong) {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.playTrack(
                TrackItem(
                    id = song.id,
                    title = song.title,
                    artist = song.singer,
                    album = song.album,
                    durationSeconds = song.durationMs / 1000L,
                    artworkUrl = null,
                    explicit = song.explicit,
                )
            )
        } else {
            val idx = SampleCatalog.songs.indexOfFirst { it.id == song.id }
            if (idx >= 0) currentIndex = idx
            internalProgress = 0.08f
            internalIsPlaying = true
        }
    }

    fun next() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.next()
        } else {
            currentIndex = (currentIndex + 1) % SampleCatalog.songs.size
            internalProgress = 0f
        }
    }

    fun previous() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.previous()
        } else {
            val size = SampleCatalog.songs.size
            currentIndex = (currentIndex - 1 + size) % size
            internalProgress = 0f
        }
    }

    fun togglePlay() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.togglePlayPause()
        } else {
            internalIsPlaying = !internalIsPlaying
        }
    }

    fun isLiked(id: String): Boolean {
        val lib = activeLibraryStore
        return if (lib != null) lib.isLiked(id) else id in likedIds
    }

    fun toggleLike(id: String) {
        val lib = activeLibraryStore
        if (lib != null) {
            val curr = currentSong
            lib.toggleLike(
                TrackItem(
                    id = curr.id,
                    title = curr.title,
                    artist = curr.singer,
                    album = curr.album,
                    durationSeconds = curr.durationMs / 1000L,
                    artworkUrl = null,
                    explicit = curr.explicit,
                )
            )
        } else {
            likedIds = if (id in likedIds) likedIds - id else likedIds + id
        }
    }
}
