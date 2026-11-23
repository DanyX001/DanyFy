package com.music.spotui.ui.components

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import com.music.spotui.di.AppForeground
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Canvas player gives its decoder back when the app leaves the screen (a paused but prepared
 * player kept a hardware video decoder, counted and powered as video playing in the background),
 * loads the same clip again when the app comes back, and evicts finished song clips from storage.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CanvasPlayerTest {
    private val clip = "file:///canvas/clip.mp4"
    private var player: ExoPlayer? = null

    private fun prepared(): ExoPlayer = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build().also { p ->
        player = p
        p.setMediaItem(MediaItem.fromUri(clip))
        p.prepare()
        p.playWhenReady = true
        CanvasPlayer.attach(p, clip)
    }

    private fun writeFakeClip(url: String, bytes: ByteArray) {
        val ctx = RuntimeEnvironment.getApplication()
        val cache = CanvasPlayer.canvasCache(ctx)
        val ds = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }
            .createDataSourceForDownloading()
        val spec = DataSpec.Builder()
            .setUri(url)
            .setKey(url)
            .build()
        CacheWriter(ds, spec, null, null).cache()
    }

    @After fun release() {
        player?.release()
        CanvasPlayer.resetForTest()
        AppForeground.onBackground()
    }

    @Test fun inTheBackgroundThePlayerHoldsNoDecoder() {
        val p = prepared()
        assertNotEquals(Player.STATE_IDLE, p.playbackState)
        CanvasPlayer.onBackground()
        // Stopped: its renderers (and so their codecs) are released, the clip stays its item.
        assertEquals(Player.STATE_IDLE, p.playbackState)
        assertFalse(p.playWhenReady)
        assertEquals(1, p.mediaItemCount)
        assertFalse(CanvasPlayer.isReady(clip))
    }

    @Test fun backOnScreenTheSameClipIsLoadedAgain() {
        val p = prepared()
        CanvasPlayer.onBackground()
        CanvasPlayer.onForeground()
        assertNotEquals(Player.STATE_IDLE, p.playbackState)
        assertEquals(clip, p.currentMediaItem?.localConfiguration?.uri.toString())
    }

    @Test fun backgroundTwiceAndForegroundWithoutBackgroundChangeNothing() {
        val p = prepared()
        CanvasPlayer.onForeground()
        assertNotEquals(Player.STATE_IDLE, p.playbackState)
        CanvasPlayer.onBackground()
        CanvasPlayer.onBackground()
        assertEquals(Player.STATE_IDLE, p.playbackState)
    }

    @Test fun finishedCanvasClipIsRemovedFromActiveCacheOnTrackChangeAndEnd() {
        val ctx = RuntimeEnvironment.getApplication()
        val urlA = "https://canvaz.scdn.co/upload/a.mp4"
        val urlB = "https://canvaz.scdn.co/upload/b.mp4"
        writeFakeClip(urlA, byteArrayOf(1, 2, 3, 4))
        assertTrue(CanvasPlayer.isCached(ctx, urlA))

        CanvasPlayer.onTrackChanged(ctx, "trackA")
        CanvasPlayer.get(ctx, urlA, trackId = "trackA")
        // Track B starts -> Track A's clip is scheduled for removal while Track B stays clean.
        CanvasPlayer.onTrackChanged(ctx, "trackB")
        val deadline = System.currentTimeMillis() + 3_000
        while (CanvasPlayer.isCached(ctx, urlA) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(CanvasPlayer.isCached(ctx, urlA))

        writeFakeClip(urlB, byteArrayOf(5, 6, 7, 8))
        assertTrue(CanvasPlayer.isCached(ctx, urlB))
        CanvasPlayer.onTrackEnded(ctx)
        val endDeadline = System.currentTimeMillis() + 3_000
        while (CanvasPlayer.isCached(ctx, urlB) && System.currentTimeMillis() < endDeadline) {
            Thread.sleep(20)
        }
        assertFalse(CanvasPlayer.isCached(ctx, urlB))
    }

    @Test fun hlsDetectionAndCacheCleanupOnTrackEnd() {
        val ctx = RuntimeEnvironment.getApplication()
        val hlsUrl = "https://mvod.itunes.apple.com/itunes-assets/square/sq768.m3u8?token=abc"
        assertTrue(CanvasPlayer.isHlsUrl(hlsUrl))
        assertFalse(CanvasPlayer.isHlsUrl("https://canvaz.scdn.co/upload/a.mp4"))

        writeFakeClip(hlsUrl, byteArrayOf(10, 20, 30, 40))
        assertTrue(CanvasPlayer.isCached(ctx, hlsUrl))

        CanvasPlayer.onTrackChanged(ctx, "trackHls")
        CanvasPlayer.get(ctx, hlsUrl, trackId = "trackHls")
        CanvasPlayer.onTrackEnded(ctx)
        val deadline = System.currentTimeMillis() + 3_000
        while (CanvasPlayer.isCached(ctx, hlsUrl) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(CanvasPlayer.isCached(ctx, hlsUrl))
    }

    @Test fun canvasVideoUsesDedicatedLowerPriorityPlaybackLooper() {
        val ctx = RuntimeEnvironment.getApplication()
        val p = CanvasPlayer.get(ctx, "https://canvaz.scdn.co/upload/prio.mp4", trackId = "trackPrio")
        assertTrue(
            "Canvas ExoPlayer should run on its dedicated video looper, not main looper",
            p.playbackLooper !== android.os.Looper.getMainLooper(),
        )
        assertEquals("canvas-video", p.playbackLooper.thread.name)
    }

    @Test fun foregroundPreloadAndGetAvoidCompetingBackgroundDownloads() {
        val ctx = RuntimeEnvironment.getApplication()
        val hlsUrl = "https://mvod.itunes.apple.com/itunes-assets/square/sq486_bal.m3u8"

        // Foreground preload hands straight to ExoPlayer's writable cache source (no duplicate HlsDownloader race).
        AppForeground.onForeground()
        CanvasPlayer.preload(ctx, hlsUrl, trackId = "trackFg")
        assertFalse(CanvasPlayer.hasActiveDownload(hlsUrl))

        // Background preload starts background caching without a decoder; switching back to get() cancels it cleanly.
        AppForeground.onBackground()
        val bgUrl = "https://mvod.itunes.apple.com/itunes-assets/square/sq486_bg.m3u8"
        CanvasPlayer.preload(ctx, bgUrl, trackId = "trackBg")
        CanvasPlayer.get(ctx, bgUrl, trackId = "trackBg")
        assertFalse(CanvasPlayer.hasActiveDownload(bgUrl))
    }

    @Test fun fullScreenWidthFitFractionPreventsOverZoomForAppleAspectRatios() {
        // 20:9 portrait phone display (1080x2400 -> aspect 0.45):
        // - 9:16 Spotify Canvas (0.5625) fills edge-to-edge cleanly via zoom (returns null).
        assertEquals(null, CanvasPlayer.fullScreenWidthFitFraction(1080f, 2400f, 9f / 16f))
        // - 3:4 Apple Portrait Canvas (0.75) would zoom 1.67x; width-fit uses 60% height cleanly.
        assertEquals(0.60f, CanvasPlayer.fullScreenWidthFitFraction(1080f, 2400f, 3f / 4f)!!, 0.001f)
        // - 1:1 Square fallback (1.0) would zoom 2.22x; width-fit uses 45% height cleanly.
        assertEquals(0.45f, CanvasPlayer.fullScreenWidthFitFraction(1080f, 2400f, 1f)!!, 0.001f)
        // - 3:4 portrait tablet (1200x1600 -> aspect 0.75) fits 3:4 video natively (returns null).
        assertEquals(null, CanvasPlayer.fullScreenWidthFitFraction(1200f, 1600f, 3f / 4f))

        // Portrait display reach gives ~12% extra vertical reach so the fog feather stays low behind controls.
        assertEquals(0.672f, CanvasPlayer.fullScreenDisplayFraction(1080f, 2400f, 3f / 4f, centered = false)!!, 0.001f)
        assertEquals(0.60f, CanvasPlayer.fullScreenDisplayFraction(1080f, 2400f, 3f / 4f, centered = true)!!, 0.001f)
    }
}
