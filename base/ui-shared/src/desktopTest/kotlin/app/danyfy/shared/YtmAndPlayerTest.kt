package app.danyfy.shared

import app.danyfy.shared.data.AudioStreamCandidate
import app.danyfy.shared.data.LibraryStore
import app.danyfy.shared.data.PlayerController
import app.danyfy.shared.data.Sha1
import app.danyfy.shared.data.TrackItem
import app.danyfy.shared.data.YtmApi
import app.danyfy.shared.data.YtmParser
import app.danyfy.shared.data.sapisidFromCookies
import app.danyfy.shared.data.sapisidHashAuth
import app.danyfy.shared.platform.PlatformAudio
import app.danyfy.shared.platform.PlatformRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtmAndPlayerTest {

    @Test
    fun testSha1AndSapisidHash() {
        val sapisid = "sapisid-test"
        val timestamp = 1_700_000_000L
        val auth = sapisidHashAuth(sapisid, timestamp)
        assertEquals("SAPISIDHASH 1700000000_c0178091cdd52a25dadb4af9a2cf9979070658fc", auth)
    }

    @Test
    fun testSapisidCookieParsing() {
        assertNull(sapisidFromCookies(null))
        assertNull(sapisidFromCookies(""))
        assertNull(sapisidFromCookies("VISITOR_INFO1_LIVE=xyz; PREF=f1=50000000"))

        assertEquals("test_sapisid", sapisidFromCookies("foo=bar; SAPISID=test_sapisid; other=123"))
        assertEquals("secure_sapisid", sapisidFromCookies("__Secure-3PAPISID=secure_sapisid; other=123"))
    }

    @Test
    fun testParsePlayerStreamsFiltersForAudioMp4() {
        val mockPlayerJson = buildJsonObject {
            putJsonObject("streamingData") {
                putJsonArray("adaptiveFormats") {
                    // Opus format - should be filtered out because AVPlayer cannot play it
                    add(buildJsonObject {
                        put("itag", 251)
                        put("url", "https://example.com/audio.webm")
                        put("mimeType", "audio/webm; codecs=\"opus\"")
                        put("bitrate", 160000)
                    })
                    // AAC low
                    add(buildJsonObject {
                        put("itag", 139)
                        put("url", "https://example.com/audio139.mp4")
                        put("mimeType", "audio/mp4; codecs=\"mp4a.40.5\"")
                        put("bitrate", 48000)
                    })
                    // AAC high
                    add(buildJsonObject {
                        put("itag", 140)
                        put("url", "https://example.com/audio140.mp4")
                        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                        put("bitrate", 128000)
                    })
                }
                put("hlsManifestUrl", "https://example.com/live.m3u8")
            }
        }

        val candidates = YtmParser.parsePlayerStreams(mockPlayerJson, "TestUA")
        // Should have 140 (128k), 139 (48k), then HLS
        assertTrue(candidates.size >= 2)
        assertEquals(140, candidates[0].itag)
        assertEquals("https://example.com/audio140.mp4", candidates[0].url)
        assertEquals(139, candidates[1].itag)
        // Verify webm was excluded
        assertFalse(candidates.any { it.itag == 251 })
    }

    @Test
    fun testPlayerControllerQueueNavigation() {
        val mockAudio = object : PlatformAudio {
            var lastPlayedUrl: String? = null
            var isPaused = false

            override fun playStream(
                url: String,
                headers: Map<String, String>,
                trackId: String,
                title: String,
                artist: String,
                album: String,
                artworkUrl: String?,
                durationMs: Long,
            ) {
                lastPlayedUrl = url
                isPaused = false
            }

            override fun pause() { isPaused = true }
            override fun resume() { isPaused = false }
            override fun seekTo(positionMs: Long) {}
            override fun stop() {}
        }

        PlatformRegistry.audio = mockAudio
        val api = YtmApi(PlatformRegistry)
        val store = LibraryStore(PlatformRegistry)
        val controller = PlayerController(api, store, PlatformRegistry)

        val t1 = TrackItem(id = "1", title = "Track One", artist = "Artist A", durationSeconds = 180)
        val t2 = TrackItem(id = "2", title = "Track Two", artist = "Artist B", durationSeconds = 200)
        val t3 = TrackItem(id = "3", title = "Track Three", artist = "Artist C", durationSeconds = 220)

        controller.playTrack(t1, listOf(t1, t2, t3))
        assertEquals("1", controller.currentTrack?.id)
        assertEquals(0, controller.queueIndex)

        // Simulate audio playback started
        controller.onPlaybackStateChanged(isPlaying = true, isBuffering = false)
        assertTrue(controller.isPlaying)
        assertFalse(controller.isBuffering)

        // Next
        controller.next()
        assertEquals("2", controller.currentTrack?.id)
        assertEquals(1, controller.queueIndex)

        // Previous
        controller.previous()
        assertEquals("1", controller.currentTrack?.id)
        assertEquals(0, controller.queueIndex)
    }
}
