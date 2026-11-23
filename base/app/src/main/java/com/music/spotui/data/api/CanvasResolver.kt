package com.music.spotui.data.api

import app.myspoti.api.Canvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory Canvas lookup cache with single-flight request deduplication and image/video tracking.
 * Authoritative answers (including "no Canvas") are cached; unreadiness and transient failures are
 * never cached so later opens or retries recover cleanly.
 */
internal class CanvasResolver {
    class Entry(val value: String?, val isImage: Boolean = false)

    private val map = ConcurrentHashMap<String, Entry>()
    private val imageUrls = ConcurrentHashMap<String, Boolean>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Entry>>()

    fun known(trackId: String): Entry? = map[trackId]

    fun clear() {
        map.clear()
        imageUrls.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    fun isImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (imageUrls[url] == true) return true
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return IMAGE_EXTS.any { path.endsWith(it) }
    }

    suspend fun getOrFetch(
        trackId: String,
        isReady: suspend () -> Boolean,
        allowLocal: Boolean = false,
        fetch: suspend (trackId: String) -> Canvas?,
    ): String? {
        if (trackId.isBlank() || (!allowLocal && trackId.startsWith("local:"))) return null
        map[trackId]?.let { return it.value }
        if (!isReady()) return null

        while (true) {
            map[trackId]?.let { return it.value }
            val existing = inFlight[trackId]
            if (existing != null) {
                try {
                    return existing.await().value
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val deferred = CompletableDeferred<Entry>()
            val raced = inFlight.putIfAbsent(trackId, deferred)
            if (raced != null) continue
            return try {
                val canvas = fetch(trackId)
                val url = canvas?.videoUrl?.takeIf { it.isNotBlank() }
                val entry = if (url != null) {
                    val img = isImageCanvasModel(url, canvas.type)
                    if (img) imageUrls[url] = true
                    Entry(url, img)
                } else {
                    Entry(null, false)
                }
                map[trackId] = entry
                deferred.complete(entry)
                url
            } catch (e: CancellationException) {
                deferred.cancel(e)
                throw e
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(trackId, deferred)
            }
        }
    }

    companion object {
        private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")
        private val VIDEO_EXTS = listOf(".mp4", ".webm", ".m3u8", ".mov")

        fun isImageCanvasModel(url: String, type: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            if (VIDEO_EXTS.any { path.endsWith(it) }) return false
            if (IMAGE_EXTS.any { path.endsWith(it) }) return true
            return type.equals("IMAGE", ignoreCase = true) || type.equals("GIF", ignoreCase = true)
        }
    }
}
