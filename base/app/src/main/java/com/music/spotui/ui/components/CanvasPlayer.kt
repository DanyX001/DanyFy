package com.music.spotui.ui.components

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.music.spotui.data.api.Api
import com.music.spotui.di.AppForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * One Canvas player for the whole app: closing and reopening the player screen keeps the
 * clip loaded (and its last frame), instead of building a new player that starts from nothing.
 *
 * Uses a dedicated `canvas-active` cache separated from [com.music.spotui.di.SongPlayer.mediaCache]
 * so large audio streams never evict Canvas loops or hold write locks against the player. When in
 * the foreground, ExoPlayer streams through a single-pass writable cache source while background
 * warming/preloading uses [CacheWriter] (for progressive clips) or [HlsDownloader] (for HLS `.m3u8`
 * clips) and clears finished songs when playback ends or moves to another track.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CanvasPlayer {
    internal const val CACHE_DIR = "canvas-active"
    private const val CACHE_LIMIT_BYTES = 64L * 1024L * 1024L
    private const val CACHE_FRAGMENT_BYTES = 512L * 1024L
    private const val CANVAS_MAX_HEIGHT = 720
    private const val MAX_ERROR_RETRIES = 2

    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cacheCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private class DownloadEntry(val job: Job, val cancelAction: () -> Unit)

    private val downloadJobs = ConcurrentHashMap<String, DownloadEntry>()

    @Volatile private var canvasCache: SimpleCache? = null
    @Volatile private var canvasCacheDir: File? = null

    private var videoThread: HandlerThread? = null
    private var exo: ExoPlayer? = null

    private fun ensureVideoLooper(): Looper = synchronized(stateLock) {
        val existing = videoThread
        if (existing != null && existing.isAlive) {
            existing.looper
        } else {
            HandlerThread("canvas-video", Process.THREAD_PRIORITY_DISPLAY).apply { start() }.also {
                videoThread = it
            }.looper
        }
    }
    private var url: String? = null
    private var framed: String? = null
    private var errorRetries: Int = 0
    private val firstFrame = mutableListOf<() -> Unit>()
    private val videoRatios = ConcurrentHashMap<String, Float>()
    val videoRatioVersion = androidx.compose.runtime.mutableIntStateOf(0)

    private var activeTrackId: String? = null
    private var activeUrl: String? = null
    private var warmedTrackId: String? = null
    private var warmedUrl: String? = null

    internal fun canvasCache(context: Context): SimpleCache {
        val appCtx = context.applicationContext
        val dir = File(appCtx.cacheDir, CACHE_DIR)
        canvasCache?.takeIf { canvasCacheDir == dir }?.let { return it }
        return synchronized(stateLock) {
            canvasCache?.takeIf { canvasCacheDir == dir } ?: run {
                canvasCache?.let { old -> runCatching { old.release() } }
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES),
                    StandaloneDatabaseProvider(appCtx),
                ).also {
                    canvasCache = it
                    canvasCacheDir = dir
                }
            }
        }
    }

    /**
     * [url] is loaded and ready to draw: its first frame shows as soon as a view is attached,
     * so the player screen opens on the clip instead of the cover.
     */
    fun isReady(url: String) = this.url == url && (framed == url || exo?.playbackState == Player.STATE_READY)

    /**
     * Loads the clip of the song now playing before the player screen opens (called while
     * the mini player is on screen, so only when the app is in use). Main thread.
     */
    fun preload(context: Context, url: String, trackId: String? = null) {
        if (url.isBlank()) return
        if (isImageUrl(url)) {
            if (AppForeground.inForeground) {
                runCatching { com.bumptech.glide.Glide.with(context.applicationContext).load(url).preload() }
            }
            return
        }
        markActiveClip(context.applicationContext, url, trackId)
        if (!AppForeground.inForeground) {
            startDownload(context.applicationContext, url)
            return
        }
        get(context, url, trackId = trackId)
    }

    /**
     * The app left the screen: the clip's decoder is given back, the clip's address kept, and the
     * clip prepared again at the next [get]. Main thread.
     */
    fun onBackground() {
        val player = exo ?: return
        player.playWhenReady = false
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        framed = null
    }

    /** The app is on screen again: the clip given back in the background is loaded again. Main thread. */
    fun onForeground() {
        val player = exo ?: return
        if (url == null) return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
    }

    /**
     * Downloads the clip [url] into `canvas-active` on storage in a single-flight job, without
     * holding a video decoder: used for warming the next track cleanly on unmetered networks.
     */
    suspend fun warm(context: Context, url: String, trackId: String? = null) {
        if (url.isBlank()) return
        val appCtx = context.applicationContext
        if (isImageUrl(url)) {
            runCatching { com.bumptech.glide.Glide.with(appCtx).downloadOnly().load(url).submit() }
            return
        }
        if (!isCacheableVideoUrl(url)) return
        val oldWarmed = synchronized(stateLock) {
            val prev = warmedUrl
            warmedUrl = url
            warmedTrackId = trackId
            if (prev != null && prev != url && prev != activeUrl) prev else null
        }
        if (oldWarmed != null) evictUrl(appCtx, oldWarmed)
        val entry = ensureDownloadEntry(appCtx, url) ?: return
        entry.job.join()
    }

    fun get(context: Context, url: String, trackId: String? = null): ExoPlayer {
        val appCtx = context.applicationContext
        markActiveClip(appCtx, url, trackId = trackId)
        // Cancel any active background warm/preload transfer for this URL so ExoPlayer's writable
        // cache source keeps 100% of the network pipe and writes segments in a single pass.
        cancelDownload(url)
        val player = exo ?: run {
            val cacheSource = playbackCacheDataSourceFactory(appCtx)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 500, 2_000)
                .build()
            ExoPlayer.Builder(appCtx)
                .setPlaybackLooper(ensureVideoLooper())
                .setPriority(C.PRIORITY_PROCESSING_BACKGROUND)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheSource))
                .build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = false
                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            val activeUrl = this@CanvasPlayer.url ?: return
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                val aspect = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                                if (aspect > 0f && videoRatios[activeUrl] != aspect) {
                                    videoRatios[activeUrl] = aspect
                                    mainHandler.post { videoRatioVersion.intValue++ }
                                }
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            errorRetries = 0
                            framed = this@CanvasPlayer.url
                            firstFrame.toList().forEach { it() }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED && this@CanvasPlayer.url != null) {
                                // Recover immediately if a stream hits end-of-stream instead of looping.
                                exo?.let { p ->
                                    p.seekTo(0)
                                    p.prepare()
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val failedUrl = this@CanvasPlayer.url
                            framed = null
                            if (failedUrl != null) {
                                // Clear corrupt/incomplete spans so the next prepare streams cleanly.
                                scheduleRemoveResource(appCtx, failedUrl)
                            }
                            val p = exo ?: return
                            if (failedUrl != null && errorRetries < MAX_ERROR_RETRIES) {
                                errorRetries++
                                mainHandler.postDelayed({
                                    if (exo === p && this@CanvasPlayer.url == failedUrl) {
                                        p.setMediaItem(buildMediaItem(failedUrl))
                                        p.prepare()
                                    }
                                }, 350L)
                            }
                        }
                    })
                }.also { exo = it }
        }
        val maxHeight = minOf(CANVAS_MAX_HEIGHT, app.myspoti.power.EnergySettings.state.value.policy.videoMaxHeight)
        if (player.trackSelectionParameters.maxVideoHeight != maxHeight) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        if (url != this.url) {
            this.url = url
            framed = null
            errorRetries = 0
            player.setMediaItem(buildMediaItem(url))
            player.prepare()
        } else if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        return player
    }

    /**
     * Updates the active song: cancels and deletes the finished song's Canvas clip from disk
     * when playback advances to [newTrackId] (promoting a warmed clip when it matches).
     */
    fun onTrackChanged(context: Context, newTrackId: String?) {
        val appCtx = context.applicationContext
        val normalized = newTrackId?.takeIf { it.isNotBlank() }
        val toEvict: String? = synchronized(stateLock) {
            if (normalized != null && normalized == activeTrackId) return
            val prevUrl = activeUrl
            activeTrackId = normalized
            if (normalized != null && normalized == warmedTrackId && warmedUrl != null) {
                activeUrl = warmedUrl
                warmedTrackId = null
                warmedUrl = null
            } else {
                activeUrl = null
            }
            if (prevUrl != null && prevUrl != activeUrl && prevUrl != warmedUrl) prevUrl else null
        }
        if (toEvict != null) {
            stopPlayerIfShowing(toEvict)
            evictUrl(appCtx, toEvict)
        }
    }

    /** Called when playback ends completely or stops: clears all cached Canvas clips from storage. */
    fun onTrackEnded(context: Context) {
        val appCtx = context.applicationContext
        val toEvict = synchronized(stateLock) {
            val urls = listOfNotNull(activeUrl, warmedUrl).distinct()
            activeTrackId = null
            activeUrl = null
            warmedTrackId = null
            warmedUrl = null
            urls
        }
        toEvict.forEach { stopPlayerIfShowing(it) }
        clearCache(appCtx)
    }

    /** Removes all entries in `canvas-active` (e.g. from Settings > Storage or when a track ends). */
    fun clearCache(context: Context) {
        val appCtx = context.applicationContext
        val entries = downloadJobs.values.toList()
        entries.forEach { entry ->
            entry.cancelAction()
            entry.job.cancel()
        }
        downloadJobs.clear()
        synchronized(stateLock) {
            activeTrackId = null
            activeUrl = null
            warmedTrackId = null
            warmedUrl = null
        }
        val cache = canvasCache ?: File(appCtx.cacheDir, CACHE_DIR).takeIf { it.exists() }?.let { canvasCache(appCtx) } ?: return
        cacheCleanupScope.launch {
            entries.forEach { runCatching { it.job.join() } }
            runCatching {
                cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
            }
        }
    }

    /** Tests: [player] stands for the shared player, with [url] loaded in it. */
    internal fun attach(player: ExoPlayer, url: String) {
        exo = player
        this.url = url
        framed = null
        errorRetries = 0
    }

    /** Tests: tears down static player and SimpleCache instances between tests. */
    internal fun resetForTest() {
        downloadJobs.values.forEach { entry ->
            entry.cancelAction()
            entry.job.cancel()
        }
        downloadJobs.clear()
        synchronized(stateLock) {
            activeTrackId = null
            activeUrl = null
            warmedTrackId = null
            warmedUrl = null
            canvasCache?.let { old -> runCatching { old.release() } }
            canvasCache = null
            canvasCacheDir = null
        }
        runCatching { exo?.release() }
        exo = null
        synchronized(stateLock) {
            videoThread?.quitSafely()
            videoThread = null
        }
        url = null
        framed = null
        firstFrame.clear()
        videoRatios.clear()
        videoRatioVersion.intValue = 0
    }

    internal fun videoAspectRatio(url: String): Float? = videoRatios[url]

    /**
     * In full-screen portrait mode, artwork significantly wider than the display (Apple Music 3:4
     * portrait or 1:1 square on a ~20:9 phone) would be cropped 40–55% by `RESIZE_MODE_ZOOM`.
     * Returns the fraction of container height to occupy at 1.0x uncropped width (feathered at
     * the edge into the player backdrop), or `null` when `RESIZE_MODE_ZOOM` fits naturally (9:16).
     */
    internal fun fullScreenWidthFitFraction(
        containerWidth: Float,
        containerHeight: Float,
        videoAspect: Float?,
    ): Float? {
        if (containerWidth <= 0f || containerHeight <= 0f || videoAspect == null || videoAspect <= 0f) return null
        val containerAspect = containerWidth / containerHeight
        val cropRatio = videoAspect / containerAspect
        if (cropRatio <= 1.32f) return null
        return (containerAspect / videoAspect).coerceIn(0.25f, 1f)
    }

    /**
     * Full-screen display height fraction in the portrait player: gently extends 3:4 portrait clips
     * (~12% taller, cropping <6% per side) so the bottom feather sits behind the lowered
     * title/controls instead of raising the backdrop fog into the middle of the art.
     */
    internal fun fullScreenDisplayFraction(
        containerWidth: Float,
        containerHeight: Float,
        videoAspect: Float?,
        centered: Boolean = false,
    ): Float? {
        val exact = fullScreenWidthFitFraction(containerWidth, containerHeight, videoAspect) ?: return null
        return if (centered) exact else (exact * 1.12f).coerceAtMost(0.70f)
    }

    fun onFirstFrame(listener: () -> Unit): () -> Unit {
        firstFrame += listener
        return { firstFrame -= listener }
    }

    internal fun isCached(context: Context, url: String): Boolean {
        val cache = canvasCache(context)
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        if (len > 0 && cache.isCached(url, 0L, len)) return true
        return cache.getCachedBytes(url, 0L, Long.MAX_VALUE) > 0L
    }

    internal fun hasActiveDownload(url: String): Boolean = downloadJobs.containsKey(url)

    private fun isImageUrl(url: String): Boolean = Api.isImageCanvas(url)

    internal fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

    private fun isCacheableVideoUrl(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        // Progressive streams (.mp4 / .webm / .mov) and HLS playlists (.m3u8) are cached while playing.
        return !path.endsWith(".mpd")
    }

    private fun buildMediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .apply { if (isHlsUrl(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

    private fun cacheDataSinkFactory(appCtx: Context): CacheDataSink.Factory =
        CacheDataSink.Factory()
            .setCache(canvasCache(appCtx))
            .setFragmentSize(CACHE_FRAGMENT_BYTES)

    private fun playbackCacheDataSourceFactory(appCtx: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(canvasCache(appCtx))
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setUpstreamDataSourceFactory(httpFactory())
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory(appCtx))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun downloadCacheFactory(appCtx: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(canvasCache(appCtx))
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setUpstreamDataSourceFactory(httpFactory())
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory(appCtx))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun downloadingDataSource(appCtx: Context): CacheDataSource =
        downloadCacheFactory(appCtx).createDataSourceForDownloading()

    private fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)

    private fun markActiveClip(appCtx: Context, url: String, trackId: String?) {
        val stale = synchronized(stateLock) {
            val prev = activeUrl
            activeUrl = url
            if (trackId != null) activeTrackId = trackId
            if (warmedUrl == url) {
                warmedUrl = null
                warmedTrackId = null
            }
            if (prev != null && prev != url && prev != warmedUrl) prev else null
        }
        if (stale != null) evictUrl(appCtx, stale)
    }

    private fun cancelDownload(url: String) {
        val entry = downloadJobs.remove(url) ?: return
        entry.cancelAction()
        entry.job.cancel()
    }

    private fun startDownload(appCtx: Context, url: String) {
        if (!isCacheableVideoUrl(url)) return
        ensureDownloadEntry(appCtx, url)
    }

    private fun ensureDownloadEntry(appCtx: Context, url: String): DownloadEntry? {
        downloadJobs[url]?.takeIf { it.job.isActive }?.let { return it }
        val activeCancel = AtomicReference<(() -> Unit)?>(null)
        val job = ioScope.launch {
            try {
                val cache = canvasCache(appCtx)
                if (isHlsUrl(url)) {
                    val downloader = HlsDownloader(buildMediaItem(url), downloadCacheFactory(appCtx))
                    activeCancel.set { downloader.cancel() }
                    downloader.download { _, _, _ ->
                        if (!isActive) downloader.cancel()
                    }
                } else {
                    if (isFullyCached(cache, url)) return@launch
                    val spec = DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setKey(url)
                        .setLength(CACHE_LIMIT_BYTES)
                        .build()
                    val writer = CacheWriter(downloadingDataSource(appCtx), spec, null) { _, _, _ ->
                        if (!isActive) activeCancel.get()?.invoke()
                    }
                    activeCancel.set { writer.cancel() }
                    writer.cache()
                }
            } catch (_: CancellationException) {
                // Song ended or changed while downloading.
            } catch (_: Throwable) {
                // Playback still streams through the read-only CacheDataSource.
            }
        }
        val cancelAction: () -> Unit = { activeCancel.get()?.invoke() }
        val entry = DownloadEntry(job, cancelAction)
        val raced = downloadJobs.putIfAbsent(url, entry)
        if (raced != null && raced.job.isActive) {
            cancelAction()
            job.cancel()
            return raced
        }
        job.invokeOnCompletion {
            if (job.isCancelled) cancelAction()
            downloadJobs.remove(url, entry)
        }
        return entry
    }

    private fun isFullyCached(cache: SimpleCache, key: String): Boolean {
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return len > 0 && cache.isCached(key, 0L, len)
    }

    private fun removeUrlFromCache(appCtx: Context, cache: SimpleCache, url: String) {
        if (isHlsUrl(url)) {
            runCatching { HlsDownloader(buildMediaItem(url), downloadCacheFactory(appCtx)).remove() }
        }
        runCatching { cache.removeResource(url) }
    }

    private fun evictUrl(appCtx: Context, url: String) {
        val entry = downloadJobs.remove(url)
        entry?.cancelAction?.invoke()
        entry?.job?.cancel()
        cacheCleanupScope.launch {
            runCatching { entry?.job?.join() }
            val stillNeeded = synchronized(stateLock) { url == activeUrl || url == warmedUrl }
            if (!stillNeeded) {
                canvasCache?.let { cache -> removeUrlFromCache(appCtx, cache, url) }
            }
        }
    }

    private fun scheduleRemoveResource(appCtx: Context, url: String) {
        val entry = downloadJobs.remove(url)
        entry?.cancelAction?.invoke()
        entry?.job?.cancel()
        cacheCleanupScope.launch {
            runCatching { entry?.job?.join() }
            removeUrlFromCache(appCtx, canvasCache(appCtx), url)
        }
    }

    private fun stopPlayerIfShowing(targetUrl: String) {
        val stop = {
            if (url == targetUrl) {
                exo?.let {
                    it.playWhenReady = false
                    if (it.playbackState != Player.STATE_IDLE) it.stop()
                    it.clearMediaItems()
                }
                framed = null
                url = null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else mainHandler.post(stop)
    }
}
