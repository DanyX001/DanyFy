/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.data.offline

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.music.spotui.di.SongPlayer
import com.music.spotui.extensions.ExtensionAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The songs an audio-source extension streams, downloaded in a single-flight background [CacheWriter]
 * while the song plays (and optionally warmed for the next song) and evicted when finished.
 *
 * Player and AutoMix decoder sources read from a read-only [CacheDataSource] (`setCacheWriteDataSinkFactory(null)`)
 * so they never block on 5 MB `CacheSpan` write locks against background downloads.
 */
@OptIn(UnstableApi::class)
object ExtStreamCache {
    private class ActiveWriter(
        val canceled: AtomicBoolean = AtomicBoolean(false),
        val writer: AtomicReference<CacheWriter?> = AtomicReference(null),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        fun cancel() {
            canceled.set(true)
            writer.get()?.cancel()
        }
    }

    private val activeWriters = ConcurrentHashMap<String, ActiveWriter>()
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun key(uri: Uri) = key(uri.lastPathSegment.orEmpty())

    private fun readOnlyCached(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(SongPlayer.mediaCache(context.applicationContext))
        .setUpstreamDataSourceFactory(OfflineCrypto.Factory(context))
        .setCacheKeyFactory { spec -> key(spec.uri) }
        .setCacheWriteDataSinkFactory(null)
        // A cache that fails (full storage) never stops the song: it is read from the network.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun downloadingCached(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(SongPlayer.mediaCache(context.applicationContext))
        .setUpstreamDataSourceFactory(OfflineCrypto.Factory(context))
        .setCacheKeyFactory { spec -> key(spec.uri) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** The player's sources: an extension's song through the read-only cache view, anything else as before. */
    class Factory(private val context: Context) : DataSource.Factory {
        private val plain = OfflineCrypto.Factory(context)
        private val viaCache by lazy { readOnlyCached(context) }
        override fun createDataSource(): DataSource = Routing(plain.createDataSource()) { viaCache.createDataSource() }
    }

    /** Opens [cachedSource] for an extension's song, [plain] for the rest. */
    private class Routing(private val plain: DataSource, private val cachedSource: () -> DataSource) : DataSource {
        private var cache: DataSource? = null
        private var active: DataSource = plain
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            plain.addTransferListener(transferListener)
            cache?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = if (dataSpec.uri.scheme == ExtensionAudio.SCHEME)
                (cache ?: cachedSource().also { c -> listeners.forEach(c::addTransferListener); cache = c })
            else plain
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
        override fun getUri(): Uri? = active.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
        override fun close() = active.close()
    }

    /**
     * The file an extension answered with for a song, as far as its answer says: the extension and
     * its source, the codec, depth, rate, type, length and transform. Two answers for one song can
     * be two files (a 24-bit master one day, a 16-bit copy the next), which the cache, keyed by the
     * song, cannot tell apart.
     */
    internal fun identity(s: app.myspoti.extensions.AudioStream): String = listOf(
        s.providerId, s.source, s.codec, s.bitDepth, s.sampleRate, s.mimeType, s.durationMs, s.transform?.let { it::class.simpleName },
    ).joinToString("|")

    /** Every song's file identity as last cached, at most this many (then started over: each song's cache is dropped once). */
    private const val IDENTITIES_MAX = 1000
    private const val IDENTITIES = "ext_stream_identities"
    private const val EXT_KEY_PREFIX = "ext:"

    /**
     * An extension answered [stream] for the song [trackId]: when it is another file than the one the
     * cache holds for the song (or the cache's is not known), the song's cached bytes go before it is
     * read. Returns whether it was dropped.
     */
    fun answered(context: Context, trackId: String, stream: app.myspoti.extensions.AudioStream): Boolean {
        if (trackId.isBlank()) return false
        val prefs = context.getSharedPreferences(IDENTITIES, Context.MODE_PRIVATE)
        val now = identity(stream)
        val before = prefs.getString(trackId, null)
        if (!mustDrop(before, now)) return false
        val edit = prefs.edit()
        if (prefs.all.size >= IDENTITIES_MAX) edit.clear()
        edit.putString(trackId, now).apply()
        runCatching { SongPlayer.mediaCache(context.applicationContext).removeResource(key(trackId)) }
        if (before != null) app.myspoti.host.android.Logbook.log("stream: $trackId is another file than the one cached, the cache of the song dropped")
        return true
    }

    /** The cached bytes of a song go unless they are known to be the same file as [now]. */
    internal fun mustDrop(before: String?, now: String): Boolean = before != now

    internal fun key(trackId: String) = "$EXT_KEY_PREFIX$trackId"

    /** True when the entire extension stream for [trackId] is already stored in [SongPlayer.mediaCache]. */
    fun isTrackCached(context: Context, trackId: String): Boolean {
        if (trackId.isBlank()) return false
        return isKeyFullyCached(SongPlayer.mediaCache(context.applicationContext), key(trackId))
    }

    /** Downloads the currently playing extension track sequentially into cache so mid-song seeks and slow links never stutter. */
    fun cacheActive(context: Context, uri: Uri, keepGoing: () -> Boolean) {
        val limit = com.music.spotui.data.AppStorage.mediaCacheLimit(context)
        val cap = maxOf(limit / 2, (limit * 3) / 4)
        cacheIntoStorage(context, uri, cap, keepGoing)
    }

    /**
     * Reads the upcoming song at [uri] into the cache, up to half the media cache. Blocking; [keepGoing] false stops it.
     */
    fun cacheAhead(context: Context, uri: Uri, keepGoing: () -> Boolean) {
        val cap = com.music.spotui.data.AppStorage.mediaCacheLimit(context) / 2
        cacheIntoStorage(context, uri, cap, keepGoing)
    }

    /** Cancels any active caching for [trackId] and deletes its temporary `"ext:$trackId"` spans unless [isStillNeeded] is true. */
    fun evictTrack(context: Context, trackId: String, isStillNeeded: (String) -> Boolean = { false }) {
        if (trackId.isBlank()) return
        val appCtx = context.applicationContext
        val entry = activeWriters[trackId]
        entry?.cancel()
        cleanupScope.launch {
            runCatching { entry?.done?.await() }
            if (isStillNeeded(trackId)) return@launch
            removeTrackResources(
                cache = SongPlayer.mediaCache(appCtx),
                prefs = appCtx.getSharedPreferences(IDENTITIES, Context.MODE_PRIVATE),
                trackId = trackId,
            )
        }
    }

    /** Cancels all active extension stream downloads and deletes all temporary `"ext:*"` cache entries (leaves OfflineStore intact). */
    fun clearActiveCache(context: Context) {
        val appCtx = context.applicationContext
        val entries = activeWriters.values.toList()
        entries.forEach { it.cancel() }
        cleanupScope.launch {
            entries.forEach { runCatching { it.done.await() } }
            val cache = SongPlayer.mediaCache(appCtx)
            val prefs = appCtx.getSharedPreferences(IDENTITIES, Context.MODE_PRIVATE)
            removeAllExtResources(cache, prefs)
        }
    }

    internal fun removeTrackResources(cache: SimpleCache, prefs: android.content.SharedPreferences, trackId: String) {
        runCatching { cache.removeResource(key(trackId)) }
        prefs.edit().remove(trackId).apply()
    }

    internal fun removeAllExtResources(cache: SimpleCache, prefs: android.content.SharedPreferences) {
        val edit = prefs.edit()
        cache.keys.toList().forEach { k ->
            if (k.startsWith(EXT_KEY_PREFIX)) {
                runCatching { cache.removeResource(k) }
                edit.remove(k.removePrefix(EXT_KEY_PREFIX))
            }
        }
        edit.apply()
    }

    private fun cacheIntoStorage(context: Context, uri: Uri, cap: Long, keepGoing: () -> Boolean) {
        if (uri.scheme != ExtensionAudio.SCHEME) return
        val trackId = uri.lastPathSegment.orEmpty()
        if (trackId.isBlank()) return
        val appCtx = context.applicationContext
        if (isTrackCached(appCtx, trackId)) return

        val entry = ActiveWriter()
        val existing = activeWriters.putIfAbsent(trackId, entry)
        if (existing != null) {
            // Another coroutine is already caching this track; let it finish without contending.
            while (keepGoing() && !existing.done.isCompleted) {
                runCatching { Thread.sleep(150) }.onFailure { return }
            }
            return
        }

        val spec = DataSpec.Builder().setUri(uri).setLength(cap).build()
        val writer = CacheWriter(
            downloadingCached(appCtx).createDataSourceForDownloading(),
            spec,
            null,
        ) { _, _, _ ->
            if (!keepGoing() || entry.canceled.get()) entry.writer.get()?.cancel()
        }
        entry.writer.set(writer)
        val watch = Thread {
            while (keepGoing() && !entry.canceled.get()) {
                runCatching { Thread.sleep(200) }.onFailure { return@Thread }
            }
            writer.cancel()
        }.apply { isDaemon = true; name = "ext-cache-watch"; start() }
        try {
            writer.cache()
        } finally {
            watch.interrupt()
            activeWriters.remove(trackId, entry)
            entry.done.complete(Unit)
        }
    }

    private fun isKeyFullyCached(cache: SimpleCache, cacheKey: String): Boolean {
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        return len > 0 && cache.isCached(cacheKey, 0L, len)
    }
}
