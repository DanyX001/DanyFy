/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.offline

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.ExtensionAudio
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtStreamCacheTest {
    private fun writeMediaCacheEntry(cacheKey: String, data: ByteArray) {
        val ctx = RuntimeEnvironment.getApplication()
        val cache = SongPlayer.mediaCache(ctx)
        val ds = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(data) }
            .createDataSourceForDownloading()
        val spec = DataSpec.Builder()
            .setUri("https://example.invalid/$cacheKey")
            .setKey(cacheKey)
            .build()
        CacheWriter(ds, spec, null, null).cache()
    }

    @After fun tearDown() {
        SongPlayer.resetMediaCacheForTest()
    }

    @Test fun evictTrackRemovesOnlyFinishedTrackAndRespectsStillNeededGuard() {
        val ctx = RuntimeEnvironment.getApplication()
        writeMediaCacheEntry(ExtStreamCache.key("trackA"), byteArrayOf(10, 20, 30, 40))
        writeMediaCacheEntry(ExtStreamCache.key("trackB"), byteArrayOf(50, 60, 70, 80))
        writeMediaCacheEntry("other:keep", byteArrayOf(1, 2))

        assertTrue(ExtStreamCache.isTrackCached(ctx, "trackA"))
        assertTrue(ExtStreamCache.isTrackCached(ctx, "trackB"))

        // Guard prevents evicting a track currently crossfading or jumped back to.
        ExtStreamCache.evictTrack(ctx, "trackA") { it == "trackA" }
        Thread.sleep(150)
        assertTrue(ExtStreamCache.isTrackCached(ctx, "trackA"))

        // Once no longer needed, only trackA is evicted; trackB and non-ext entries remain.
        ExtStreamCache.evictTrack(ctx, "trackA") { false }
        val deadline = System.currentTimeMillis() + 3_000
        while (ExtStreamCache.isTrackCached(ctx, "trackA") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertFalse(ExtStreamCache.isTrackCached(ctx, "trackA"))
        assertTrue(ExtStreamCache.isTrackCached(ctx, "trackB"))
        assertTrue(SongPlayer.mediaCache(ctx).isCached("other:keep", 0L, 2L))
    }

    @Test fun clearActiveCacheEvictsAllExtensionStreamsWithoutTouchingOtherKeys() {
        val ctx = RuntimeEnvironment.getApplication()
        writeMediaCacheEntry(ExtStreamCache.key("track1"), byteArrayOf(1, 2, 3))
        writeMediaCacheEntry(ExtStreamCache.key("track2"), byteArrayOf(4, 5, 6))
        writeMediaCacheEntry("other:asset", byteArrayOf(9, 8))

        ExtStreamCache.clearActiveCache(ctx)
        val deadline = System.currentTimeMillis() + 3_000
        while ((ExtStreamCache.isTrackCached(ctx, "track1") || ExtStreamCache.isTrackCached(ctx, "track2")) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertFalse(ExtStreamCache.isTrackCached(ctx, "track1"))
        assertFalse(ExtStreamCache.isTrackCached(ctx, "track2"))
        assertTrue(SongPlayer.mediaCache(ctx).isCached("other:asset", 0L, 2L))
    }

    @Test fun playerFactoryReadsCachedSpansCleanlyWithoutContention() {
        val ctx = RuntimeEnvironment.getApplication()
        val expected = byteArrayOf(11, 22, 33, 44, 55)
        writeMediaCacheEntry(ExtStreamCache.key("cachedSong"), expected)

        val ds = ExtStreamCache.Factory(ctx).createDataSource()
        val uri = Uri.parse("${ExtensionAudio.SCHEME}://track/cachedSong")
        val length = ds.open(DataSpec.Builder().setUri(uri).build())
        assertEquals(expected.size.toLong(), length)
        val actual = ByteArray(expected.size)
        var offset = 0
        while (offset < actual.size) {
            val read = ds.read(actual, offset, actual.size - offset)
            assertTrue(read > 0)
            offset += read
        }
        ds.close()
        assertArrayEquals(expected, actual)
    }
}
