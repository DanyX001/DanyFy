package app.myspoti.engine.android

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.HttpResponse
import app.myspoti.engine.PlayableMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SegmentDataSourceCancellationTest {
    private fun media() = PlayableMedia(
        trackUri = "spotify:track:test",
        fileId = "file123",
        format = 10,
        codec = "mp4a.40.2",
        bitrate = 128_000,
        cdnUrls = listOf("https://cdn1.example/audio", "https://cdn2.example/audio"),
        cdnHeaders = emptyMap(),
        initRange = 0L..99L,
        firstSegmentOffset = 100L,
        timescale = 44100,
        segments = listOf(PlayableMedia.Segment(100L..199L, 0L, 44100L)),
        widevinePssh = null,
        durationMs = 1_000L,
        gainDb = null,
        expiresAtMs = Long.MAX_VALUE,
    )

    @Test
    fun cancelAbortsBlockingChunkOpenWithoutTryingFallbackUrls() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val cancelledHttp = CompletableDeferred<Unit>()
        val http = object : Http {
            override suspend fun request(request: HttpRequest): HttpResponse {
                attempts.incrementAndGet()
                started.countDown()
                try {
                    awaitCancellation()
                } finally {
                    cancelledHttp.complete(Unit)
                }
            }
        }
        val item = CdnItem(media(), http) {}
        val ds = SegmentDataSource { uri -> if (uri == item.uri) item else null }
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit {
                try {
                    ds.open(DataSpec.Builder().setUri(item.uri).setPosition(0L).setLength(100L).build())
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    finished.countDown()
                }
            }
            assertTrue("request did not start", started.await(3, TimeUnit.SECONDS))
            item.cancel()
            assertTrue("open did not abort promptly", finished.await(3, TimeUnit.SECONDS))
            assertTrue(failure.get() is CancelledAudioException)
            assertEquals(1, attempts.get())
            assertTrue(cancelledHttp.isCompleted)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun cancelAbortsBlockingDirectOpen() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val attempts = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val http = object : Http {
            override suspend fun request(request: HttpRequest): HttpResponse {
                attempts.incrementAndGet()
                started.countDown()
                awaitCancellation()
            }
        }
        val item = CdnItem(media(), http) {}
        val ds = SegmentDataSource { uri -> if (uri == item.uri) item else null }
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit {
                try {
                    // Partial range hits fetchDirect instead of fetchChunk.
                    ds.open(DataSpec.Builder().setUri(item.uri).setPosition(10L).setLength(20L).build())
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    finished.countDown()
                }
            }
            assertTrue("request did not start", started.await(3, TimeUnit.SECONDS))
            item.cancel()
            assertTrue("open did not abort promptly", finished.await(3, TimeUnit.SECONDS))
            assertTrue(failure.get() is CancelledAudioException)
            assertEquals(1, attempts.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
