package com.music.spotui.data.api

import app.myspoti.api.Canvas
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CanvasRetryTest {
    @Test fun `transient failure is not poisoned and later retry succeeds`() = runBlocking {
        val resolver = CanvasResolver()
        val calls = AtomicInteger(0)

        val failed = runCatching {
            resolver.getOrFetch("t1", isReady = { true }) {
                calls.incrementAndGet()
                throw IOException("network blip")
            }
        }
        assertTrue(failed.isFailure)
        assertNull(resolver.known("t1"))

        val recovered = resolver.getOrFetch("t1", isReady = { true }) {
            calls.incrementAndGet()
            Canvas("spotify:canvas:c1", "https://canvaz.scdn.co/upload/c1.mp4", "VIDEO_LOOPING")
        }
        assertEquals("https://canvaz.scdn.co/upload/c1.mp4", recovered)
        assertEquals(2, calls.get())
        assertEquals("https://canvaz.scdn.co/upload/c1.mp4", resolver.known("t1")?.value)
    }

    @Test fun `unreadiness does not poison cache and local tracks are skipped`() = runBlocking {
        val resolver = CanvasResolver()
        assertNull(resolver.getOrFetch("t2", isReady = { false }) {
            error("should not fetch while unready")
        })
        assertNull(resolver.known("t2"))

        assertNull(resolver.getOrFetch("local:1234", isReady = { true }) {
            error("should not fetch local track ids")
        })
        assertNull(resolver.known("local:1234"))
    }

    @Test fun `concurrent callers share one fetch and clear drops cached state`() = runBlocking {
        val resolver = CanvasResolver()
        val calls = AtomicInteger(0)

        val (first, second) = Pair(
            async {
                resolver.getOrFetch("t3", isReady = { true }) {
                    calls.incrementAndGet()
                    delay(40)
                    Canvas("spotify:canvas:img", "https://canvaz.scdn.co/upload/img123", "IMAGE")
                }
            },
            async {
                delay(10)
                resolver.getOrFetch("t3", isReady = { true }) {
                    calls.incrementAndGet()
                    null
                }
            },
        )
        assertEquals("https://canvaz.scdn.co/upload/img123", first.await())
        assertEquals("https://canvaz.scdn.co/upload/img123", second.await())
        assertEquals(1, calls.get())
        assertTrue(resolver.isImageUrl("https://canvaz.scdn.co/upload/img123"))

        resolver.clear()
        assertNull(resolver.known("t3"))
        assertFalse(resolver.isImageUrl("https://canvaz.scdn.co/upload/img123"))
        assertTrue(resolver.isImageUrl("https://cdn.example.com/canvas.gif?token=1"))
        assertFalse(resolver.isImageUrl("https://canvaz.scdn.co/upload/c1.mp4"))
    }

    @Test fun `authoritative null is cached until clear`() = runBlocking {
        val resolver = CanvasResolver()
        val calls = AtomicInteger(0)

        assertNull(resolver.getOrFetch("t4", isReady = { true }) { calls.incrementAndGet(); null })
        assertNull(resolver.getOrFetch("t4", isReady = { true }) {
            calls.incrementAndGet()
            Canvas("c", "https://canvaz.scdn.co/x.mp4", "VIDEO")
        })
        assertEquals(1, calls.get())
    }

    @Test fun `local tracks resolve when allowLocal is enabled for extensions`() = runBlocking {
        val resolver = CanvasResolver()
        val url = resolver.getOrFetch("local:track-42", isReady = { true }, allowLocal = true) {
            Canvas("ext:local:track-42", "https://mvod.itunes.apple.com/clip.mp4", "VIDEO_LOOPING")
        }
        assertEquals("https://mvod.itunes.apple.com/clip.mp4", url)
        assertFalse(resolver.isImageUrl("https://mvod.itunes.apple.com/clip.mp4"))
        assertEquals("https://mvod.itunes.apple.com/clip.mp4", resolver.known("local:track-42")?.value)
    }
}
