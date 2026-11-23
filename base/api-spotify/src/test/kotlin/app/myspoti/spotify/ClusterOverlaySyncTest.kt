package app.myspoti.spotify

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import app.myspoti.engine.Cluster
import app.myspoti.engine.ClusterDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClusterOverlaySyncTest {
    private val track = NowPlaying(
        kind = ItemKind.TRACK,
        uri = "spotify:track:abc",
        uid = "u1",
        name = "Song",
        artists = listOf(ArtistRef("spotify:artist:1", "Artist")),
        album = AlbumRef("spotify:album:1", "Album"),
        imageUrl = null,
        durationMs = 200_000L,
    )

    @Test
    fun overlay_cluster_removes_measured_phone_server_clock_skew() {
        val serverTs = 1_794_268_055_000L
        val phoneSkewMs = 700L
        val phoneNow = serverTs + phoneSkewMs + 50L
        val cluster = Cluster(
            activeDeviceId = "tv-1",
            devices = listOf(ClusterDevice("tv-1", "TV", "tv", 0.5)),
            trackUri = track.uri,
            contextUri = "spotify:playlist:p1",
            paused = false,
            positionMs = 10_000L,
            timestampMs = serverTs,
            durationMs = 200_000L,
            speed = 1.0,
            isBuffering = false,
            clusterTimestampMs = serverTs,
            clockOffsetMs = phoneSkewMs,
        )
        val page = PlayerState(
            item = track,
            contextUri = "spotify:playlist:p1",
            isPaused = false,
            isBuffering = false,
            positionMs = 3_000L,
            timestampMs = phoneNow - 5_000L,
            speed = 1.0,
            shuffle = false,
            repeat = Repeat.OFF,
            restrictions = PlayerRestrictions(),
        )

        val overlaid = EngineStates.overlayCluster(page, cluster, fallbackTimestampMs = phoneNow)!!
        assertEquals(10_050L, overlaid.positionAt(phoneNow))
    }

    @Test
    fun overlay_cluster_preserves_buffering_and_zero_speed_so_clock_holds_while_tv_loads() {
        val ts = 1_794_268_055_000L
        val cluster = Cluster(
            activeDeviceId = "tv-1",
            devices = listOf(ClusterDevice("tv-1", "TV", "tv", 0.5)),
            trackUri = track.uri,
            contextUri = null,
            paused = false,
            positionMs = 25_000L,
            timestampMs = ts,
            durationMs = 200_000L,
            speed = 0.0,
            isBuffering = true,
        )
        val overlaid = EngineStates.of(cluster)
        assertTrue(overlaid.isBuffering)
        assertEquals(0.0, overlaid.speed)
        assertEquals(25_000L, overlaid.positionAt(ts + 900L))
    }

    @Test
    fun overlay_cluster_treats_spotify_ad_uri_as_ad_without_exposing_blank_track() {
        val ts = 1_794_268_055_000L
        val adCluster = Cluster(
            activeDeviceId = "tv-1",
            devices = listOf(ClusterDevice("tv-1", "TV", "TV", 0.5)),
            trackUri = "spotify:ad:00001234",
            contextUri = "spotify:playlist:p1",
            paused = false,
            positionMs = 4_000L,
            timestampMs = ts,
            durationMs = 30_000L,
            speed = 1.0,
            isBuffering = false,
        )
        assertEquals(ItemKind.AD, EngineStates.of(adCluster).item?.kind)

        val page = PlayerState(
            item = track,
            contextUri = "spotify:playlist:p1",
            isPaused = false,
            isBuffering = false,
            positionMs = 42_000L,
            timestampMs = ts,
            speed = 1.0,
            shuffle = false,
            repeat = Repeat.OFF,
            restrictions = PlayerRestrictions(),
        )
        val overlaidWithPage = EngineStates.overlayCluster(page, adCluster, fallbackTimestampMs = ts + 200L)!!
        assertEquals(track.uri, overlaidWithPage.item?.uri)
        assertEquals(42_000L, overlaidWithPage.positionMs)
        assertTrue(overlaidWithPage.isBuffering)
        assertEquals(0.0, overlaidWithPage.speed)

        val overlaidWithoutPage = EngineStates.overlayCluster(null, adCluster, fallbackTimestampMs = ts + 200L)!!
        assertNull(overlaidWithoutPage.item)
        assertTrue(overlaidWithoutPage.isBuffering)
        assertEquals(0.0, overlaidWithoutPage.speed)
    }
}
