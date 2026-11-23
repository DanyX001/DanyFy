package app.danyfy.shared.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.danyfy.shared.platform.PlatformRegistry
import kotlinx.serialization.encodeToString

class LibraryStore(private val registry: PlatformRegistry = PlatformRegistry) {
    var look by mutableStateOf(Look.of(registry.storage.getString("danyfy_look")))
        private set

    val likedTracks = mutableStateListOf<TrackItem>()
    val recentTracks = mutableStateListOf<TrackItem>()
    val savedCollections = mutableStateListOf<CollectionCard>()

    init {
        loadFromStorage()
    }

    fun setLookPreference(newLook: Look) {
        look = newLook
        registry.storage.setString("danyfy_look", newLook.name)
    }

    private fun loadFromStorage() {
        try {
            val likedJson = registry.storage.getString("danyfy_liked_tracks")
            if (!likedJson.isNullOrBlank()) {
                val list = YtmParser.json.decodeFromString<List<TrackItem>>(likedJson)
                likedTracks.clear()
                likedTracks.addAll(list)
            }
        } catch (_: Throwable) {}

        try {
            val recentJson = registry.storage.getString("danyfy_recent_tracks")
            if (!recentJson.isNullOrBlank()) {
                val list = YtmParser.json.decodeFromString<List<TrackItem>>(recentJson)
                recentTracks.clear()
                recentTracks.addAll(list)
            }
        } catch (_: Throwable) {}

        try {
            val savedJson = registry.storage.getString("danyfy_saved_collections")
            if (!savedJson.isNullOrBlank()) {
                val list = YtmParser.json.decodeFromString<List<CollectionCard>>(savedJson)
                savedCollections.clear()
                savedCollections.addAll(list)
            }
        } catch (_: Throwable) {}
    }

    private fun persistLiked() {
        try {
            val json = YtmParser.json.encodeToString(likedTracks.toList())
            registry.storage.setString("danyfy_liked_tracks", json)
        } catch (_: Throwable) {}
    }

    private fun persistRecent() {
        try {
            val json = YtmParser.json.encodeToString(recentTracks.toList())
            registry.storage.setString("danyfy_recent_tracks", json)
        } catch (_: Throwable) {}
    }

    private fun persistSaved() {
        try {
            val json = YtmParser.json.encodeToString(savedCollections.toList())
            registry.storage.setString("danyfy_saved_collections", json)
        } catch (_: Throwable) {}
    }

    fun isLiked(trackId: String): Boolean = likedTracks.any { it.id == trackId }

    fun toggleLike(track: TrackItem): Boolean {
        val existing = likedTracks.indexOfFirst { it.id == track.id }
        return if (existing >= 0) {
            likedTracks.removeAt(existing)
            persistLiked()
            false
        } else {
            likedTracks.add(0, track)
            persistLiked()
            true
        }
    }

    fun addRecent(track: TrackItem) {
        val existing = recentTracks.indexOfFirst { it.id == track.id }
        if (existing >= 0) {
            recentTracks.removeAt(existing)
        }
        recentTracks.add(0, track)
        if (recentTracks.size > 50) {
            recentTracks.removeAt(recentTracks.size - 1)
        }
        persistRecent()
    }

    fun isCollectionSaved(id: String): Boolean = savedCollections.any { it.id == id }

    fun toggleSaveCollection(card: CollectionCard): Boolean {
        val existing = savedCollections.indexOfFirst { it.id == card.id }
        return if (existing >= 0) {
            savedCollections.removeAt(existing)
            persistSaved()
            false
        } else {
            savedCollections.add(0, card)
            persistSaved()
            true
        }
    }
}
