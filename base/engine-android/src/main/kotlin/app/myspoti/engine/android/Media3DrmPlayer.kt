// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import app.myspoti.engine.DrmPlayer
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.LicenceChannel
import app.myspoti.engine.PlayableMedia
import app.myspoti.engine.PlaybackMetrics
import app.myspoti.engine.PlayerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val MUSIC: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

/**
 * The engine's [DrmPlayer] on Media3 ExoPlayer: one Widevine-protected AAC file at a time, read from
 * the CDN through [SegmentDataSource] (the Web Player's range pattern) and FragmentedMp4Extractor
 * (the init chunk's `sidx` makes it seekable), decrypted by the phone's CDM.
 *
 * DRM: one [DefaultDrmSessionManager] in multi-session mode for the player's life. Its
 * [MediaDrmCallback] hands the CDM challenge to the engine's [LicenceChannel] and returns the raw
 * licence (blocking on Media3's DRM request thread, as the seam allows). Init data comes from the
 * file's moov `pssh`, which the extractor attaches to the format. Privacy mode as in the browser
 * (EME setServerCertificate): the application certificate is set on the CDM before its first
 * session, fetched once through the channel (the core caches it too).
 *
 * Preload: the next item's init chunk and first segment are fetched into memory, and a DRM session
 * is acquired with the moov's own `pssh` box (not the seektable's: the moov one carries an extra
 * field 1, algorithm AES-CTR, so the bytes differ and Media3 would not match them). When the item
 * is loaded, the manager finds that session by equal init data and reuses it: no licence round
 * trip, no CDN round trip for the first 10 s.
 *
 * Every call is posted, in order, to the player's own looper (a background thread unless one is
 * given), so the engine may call from any coroutine. Events go to the listener from that thread.
 */
@OptIn(UnstableApi::class)
class Media3DrmPlayer(
    context: Context,
    private val http: Http,
    /** Where the player's steps go (the debug harness passes the Logbook). */
    private val log: (String) -> Unit = {},
    /** Sets the application certificate on the CDM, as the browser's EME did. */
    private val privacyMode: Boolean = true,
    /** Asks MediaDrm for L3 even on an L1 phone: a diagnostic for licences refused on L1. */
    private val forceL3: Boolean = false,
    looper: Looper? = null,
    private val audioProcessors: () -> Array<androidx.media3.common.audio.AudioProcessor> = { emptyArray() },
    private val wrapAudioSink: (AudioSink, Array<androidx.media3.common.audio.AudioProcessor>) -> AudioSink = { sink, _ -> sink },
) : DrmPlayer {
    /**
     * The player paused itself (audio focus lost for good, headphones unplugged): the caller should
     * send a pause command so Spotify's state follows. Called on the player's thread.
     */
    var onPlayerPause: ((reason: String) -> Unit)? = null
    /** The caller wants sound (a load that plays, a resume): only then is a pause of the player's own told, and once. */
    @Volatile private var wantsPlay = false

    private val app = context.applicationContext
    private fun urgentAudioThread(name: String): HandlerThread = object : HandlerThread(name, android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) {
        override fun onLooperPrepared() {
            OrpheonRustBridge.boostAudioThread()
        }
    }
    private val thread = if (looper == null) urgentAudioThread("engine-player").apply { start() } else null
    private val playbackThread = if (looper == null) urgentAudioThread("engine-playback").apply { start() } else null
    private val handler = Handler(looper ?: thread!!.looper)
    private val appDispatcher = handler.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Calls in the order the engine made them; a load may first wait for the certificate. */
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile private var listener: (PlayerEvent) -> Unit = {}
    @Volatile private var licence: LicenceChannel? = null
    @Volatile private var certificate: ByteArray? = null
    private val items = ConcurrentHashMap<String, CdnItem>()

    // Player-thread state.
    private lateinit var exo: ExoPlayer
    private var outgoingExo: ExoPlayer? = null
    private var outgoingItem: CdnItem? = null
    private val exoListener: Player.Listener = Events()
    private val outgoingListener: Player.Listener = OutgoingEvents()
    private lateinit var drm: DefaultDrmSessionManager
    private lateinit var sources: ProgressiveMediaSource.Factory
    private var drmHeld = false
    private val preloads = HashMap<String, DrmSession>()
    private var handedOverSession: DrmSession? = null
    @Volatile private var released = false
    @Volatile private var concurrentDrmBlocked = false
    @Volatile private var current: CdnItem? = null
    private var currentUri = ""
    private var loadedSent = false
    private var endedSent = false
    private var status: Status? = null
    private var seekPending = false
    private var loadAtMs = 0L
    private var volume = 1f
    private var muted = false
    private var crossfadeActive = false
    private var crossfadeStartMs = 0L
    private var crossfadeDurationMs = 0L
    private var crossfadeBasePosMs = 0L
    private var crossfadeFailsafeAtMs = 0L
    private var crossfadeProgress = 0f
    private var crossfadeRetrySpec: CrossfadeRetrySpec? = null
    private var singleFadeActive = false
    private var singleFadeStartMs = 0L
    private var singleFadeDurationMs = 0L
    private var singleFadeFrom = 1f
    private var singleFadeTo = 1f
    private var singleFadeGain = 1f
    /** The audio renderer, decoding or silent (see [MirrorAudioRenderer]); set when the player is built. */
    private var audioRenderer: MirrorAudioRenderer? = null

    override val supportsConcurrentPlayback: Boolean get() = !concurrentDrmBlocked

    private data class CrossfadeRetrySpec(
        val media: PlayableMedia,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    // Metrics, read from the engine's thread.
    @Volatile private var initialBufferingMs = 0L
    @Volatile private var stallStartMs = 0L
    @Volatile private var stalls = 0
    @Volatile private var stalledMs = 0L
    @Volatile private var maxStalledMs = 0L
    @Volatile private var seekStartMs = 0L
    @Volatile private var seekRebufferingMs = 0L

    private enum class Status { PLAYING, PAUSED, BUFFERING }

    init {
        handler.post { build() }
        scope.launch { for (c in commands) runCatching { c() }.onFailure { log("player: ${it.message}") } }
    }

    private fun build() {
        OrpheonRustBridge.boostAudioThread()
        val provider = ExoMediaDrm.Provider { uuid ->
            try {
                FrameworkMediaDrm.newInstance(uuid).also { d ->
                    if (forceL3) runCatching { d.setPropertyString("securityLevel", "L3") }
                        .onSuccess { log("widevine: L3 forced") }.onFailure { log("widevine: L3 refused: ${it.message}") }
                    if (privacyMode) certificate?.let { c ->
                        runCatching { d.setPropertyByteArray("serviceCertificate", c) }
                            .onSuccess { log("widevine: privacy mode on (${c.size} B certificate)") }
                            .onFailure { log("widevine: certificate refused by the CDM: ${it.message}") }
                    }
                }
            } catch (e: UnsupportedDrmException) {
                log("widevine: unsupported (${e.reason})")
                DummyExoMediaDrm()
            }
        }
        drm = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, provider)
            .setMultiSession(true)
            .setSessionKeepaliveMs(C.TIME_UNSET)
            .build(LicenceCallback())
        sources = ProgressiveMediaSource.Factory(
            SegmentDataSource.Factory { items[it.toString()] },
            ExtractorsFactory { arrayOf(FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)) },
        )
            .setDrmSessionManagerProvider(DrmSessionManagerProvider { drm })
            // 32 KB between load-control checks so ExoPlayer transitions to STATE_READY as soon as the first segment arrives.
            .setContinueLoadingCheckIntervalBytes(32 * 1024)
        exo = buildExo().also { it.addListener(exoListener) }
    }

    private fun buildExo(): ExoPlayer =
        ExoPlayer.Builder(app, Renderers())
            .setLooper(handler.looper)
            .apply { playbackThread?.let { setPlaybackLooper(it.looper) } }
            .setPriority(C.PRIORITY_PLAYBACK)
            .setAudioAttributes(MUSIC, !muted)
            .setHandleAudioBecomingNoisy(!muted)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Keep 30-60 s compressed buffer ahead so UI interaction or screen-off Wi-Fi jitter never underruns.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 60_000, 250, 1_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()

    /** Media3's renderers, the audio one wrapped so a muted mirror is played without being decoded. */
    private inner class Renderers : DefaultRenderersFactory(app) {
        private val asyncCodecFactory: MediaCodecAdapter.Factory by lazy {
            DefaultMediaCodecAdapterFactory(
                app,
                { urgentAudioThread("engine-codec-cb") },
                { urgentAudioThread("engine-codec-q") },
            ).forceEnableAsynchronous()
        }

        override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = asyncCodecFactory

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val processors = audioProcessors()
            val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(processors)
                .setAudioTrackBufferSizeProvider(
                    androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(250_000)
                        .setMaxPcmBufferDurationUs(2_500_000)
                        .build()
                )
                .setEnableOnAudioPositionAdvancingFix(true)
                .build()
            return wrapAudioSink(sink, processors)
        }

        override fun buildAudioRenderers(
            context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
            audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
        ) {
            val built = ArrayList<Renderer>()
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, built)
            built.mapTo(out) { r -> if (r is MediaCodecAudioRenderer) MirrorAudioRenderer(r).also { audioRenderer = it } else r }
        }
    }

    /** Holds the DRM manager for the player's life (keep-alive disabled so CDM sessions close when released). */
    private suspend fun holdDrm() {
        if (drmHeld) return
        drmHeld = true
        withContext(Handler(exo.playbackLooper).asCoroutineDispatcher()) {
            drm.setPlayer(exo.playbackLooper, PlayerId.UNSET)
            drm.prepare()
        }
    }

    private suspend fun ensureCertificate(channel: LicenceChannel) {
        if (!privacyMode || certificate != null) return
        runCatching { certificate = channel.serviceCertificate() }
            .onFailure { log("widevine: application certificate unavailable, privacy mode off: ${it.message}") }
    }

    private fun enqueue(block: suspend () -> Unit) { commands.trySend(block) }
    private fun onPlayer(block: () -> Unit) = enqueue { withContext(appDispatcher) { block() } }
    /**
     * Loads asked for and not yet given to the player (a load waits for the certificate and the
     * player's thread): meanwhile the song going out still plays and reports, and its positions
     * were taken for the new song's (log seen: a song finalized at 3:22 a second after it was
     * loaded, the old song's place). Only an error goes out then.
     */
    private class LicenceJob(val fileId: String?, val deferred: Deferred<ByteArray>)

    private val loadsPending = AtomicInteger(0)
    private val loadGen = AtomicInteger(0)
    private val preloadGen = AtomicInteger(0)
    @Volatile private var preloadFileId: String? = null
    private val activeLicenceJob = AtomicReference<LicenceJob?>(null)
    private val activeCertJob = AtomicReference<Job?>(null)
    @Volatile private var pendingLicenceFileId: String? = null
    private fun emit(e: PlayerEvent) { if (!released && loadsPending.get() == 0) listener(e) }
    private fun position() = exo.currentPosition.coerceAtLeast(0)

    private fun abortPendingIo(keepFileId: String? = null, keepSecondFileId: String? = null) {
        val active = activeLicenceJob.get()
        if (active != null && (keepFileId == null || (active.fileId != keepFileId && active.fileId != keepSecondFileId))) {
            if (activeLicenceJob.compareAndSet(active, null)) active.deferred.cancel()
        }
        if (keepFileId == null || pendingLicenceFileId != keepFileId) {
            pendingLicenceFileId = keepFileId
        }
        current?.takeIf { keepFileId == null || (it.media.fileId != keepFileId && it.media.fileId != keepSecondFileId) }?.cancel()
    }

    override fun setListener(listener: (PlayerEvent) -> Unit) { this.listener = listener }

    override fun load(media: PlayableMedia, positionMs: Long, playWhenReady: Boolean, licence: LicenceChannel) =
        loadInternal(media, positionMs, playWhenReady, licence, crossfadeMs = 0L)

    override fun loadCrossfade(
        media: PlayableMedia,
        positionMs: Long,
        playWhenReady: Boolean,
        licence: LicenceChannel,
        crossfadeMs: Long,
    ) = loadInternal(media, positionMs, playWhenReady, licence, crossfadeMs = crossfadeMs)

    private fun loadInternal(
        media: PlayableMedia,
        positionMs: Long,
        playWhenReady: Boolean,
        licence: LicenceChannel,
        crossfadeMs: Long,
    ) {
        if (released) return
        val gen = loadGen.incrementAndGet()
        if (preloadFileId != media.fileId) {
            preloadGen.incrementAndGet()
        }
        loadsPending.incrementAndGet()
        val pendingDec = AtomicBoolean(true)
        activeCertJob.getAndSet(null)?.cancel()
        val keepSecond = if (crossfadeMs > 0L) current?.media?.fileId else null
        abortPendingIo(media.fileId, keepSecond)
        enqueue {
            try {
                loadNow(media, positionMs, playWhenReady, licence, gen, pendingDec, crossfadeMs)
            } finally {
                if (pendingDec.compareAndSet(true, false)) loadsPending.decrementAndGet()
            }
        }
    }

    private suspend fun loadNow(
        media: PlayableMedia,
        positionMs: Long,
        playWhenReady: Boolean,
        licence: LicenceChannel,
        gen: Int,
        pendingDec: AtomicBoolean,
        crossfadeMs: Long,
    ) {
        if (released || gen != loadGen.get()) return
        this.licence = licence
        val targetUri = uriOf(media)
        val earlyExisting = items[targetUri]
        val earlyItem = if (earlyExisting != null && !earlyExisting.isCancelled) earlyExisting else CdnItem(media, http, log).also { items[targetUri] = it }
        earlyItem.warmInitialChunks()
        if (privacyMode && certificate == null) {
            val job = scope.async { ensureCertificate(licence) }
            activeCertJob.getAndSet(job)?.cancel()
            try {
                if (!released && gen == loadGen.get()) {
                    runCatching { withTimeoutOrNull(CERT_WAIT_MS) { job.await() } }
                }
            } finally {
                activeCertJob.compareAndSet(job, null)
                job.cancel()
            }
        }
        if (released || gen != loadGen.get()) return
        withContext(appDispatcher) { holdDrm() }
        withContext(appDispatcher) {
            if (released || gen != loadGen.get()) return@withContext
            val outgoing = current
            val targetUri = uriOf(media)
            val existing = items[targetUri]
            val item = if (existing != null && !existing.isCancelled) existing else CdnItem(media, http, log).also { items[targetUri] = it }

            val canTryOverlap = crossfadeMs >= 500L &&
                !muted &&
                playWhenReady &&
                outgoing != null &&
                outgoing !== item &&
                !concurrentDrmBlocked &&
                exo.isPlaying &&
                exo.playbackState != Player.STATE_ENDED &&
                exo.playbackState != Player.STATE_IDLE
            val outDur = if (canTryOverlap) {
                exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: outgoing.media.durationMs
            } else 0L
            val effectiveFadeMs = if (canTryOverlap && outDur > 0L) {
                minOf(crossfadeMs, (outDur - position()).coerceAtLeast(0L))
            } else 0L

            current = null
            currentUri = ""
            handedOverSession?.let(::releaseOnPlayback)
            handedOverSession = null

            if (effectiveFadeMs >= 500L) {
                cancelCrossfadeLocked(restoreVolume = false)
                val prevExo = exo
                prevExo.removeListener(exoListener)
                prevExo.setAudioAttributes(MUSIC, false)
                prevExo.setHandleAudioBecomingNoisy(false)
                prevExo.addListener(outgoingListener)
                outgoingExo = prevExo
                outgoingItem = outgoing
                crossfadeActive = true
                crossfadeStartMs = 0L
                crossfadeDurationMs = effectiveFadeMs
                crossfadeBasePosMs = positionMs
                crossfadeFailsafeAtMs = System.currentTimeMillis() + effectiveFadeMs + 10_000L
                crossfadeProgress = 0f
                crossfadeRetrySpec = CrossfadeRetrySpec(media, positionMs, playWhenReady)
                exo = buildExo().also { it.addListener(exoListener) }
                // Abort any stale preloads other than media and the active outgoing item.
                abortPendingIo(media.fileId, outgoing?.media?.fileId)
            } else {
                cancelCrossfadeLocked(restoreVolume = false)
                abortPendingIo(media.fileId)
            }

            val protectedOutgoing = outgoingItem
            val protectedOutgoingUri = protectedOutgoing?.uri?.toString()
            // Evict unused CDN items (cancelling any background fetches) and release any unused preloaded Widevine DRM sessions before prepare().
            items.entries.removeAll { (key, stale) ->
                if (key == targetUri || (protectedOutgoingUri != null && key == protectedOutgoingUri && stale === protectedOutgoing)) false
                else {
                    stale.cancel()
                    true
                }
            }
            if (outgoing != null && outgoing !== item && outgoing !== protectedOutgoing) outgoing.cancel()
            preloads.entries.removeAll { (fileId, session) ->
                if (fileId == media.fileId) false
                else {
                    releaseOnPlayback(session)
                    true
                }
            }
            val preloadedSession = preloads.remove(media.fileId)
            val validPreload = preloadedSession?.takeIf { it.state != DrmSession.STATE_ERROR && it.state != DrmSession.STATE_RELEASED }
            if (preloadedSession != null && validPreload == null) {
                log("preload ${media.trackUri}: discarding failed preload session (state=${preloadedSession.state})")
                releaseOnPlayback(preloadedSession)
            }
            val preloaded = validPreload != null
            // Keep preloaded session alive until exo reaches READY (or is replaced) since keep-alive is disabled.
            handedOverSession = validPreload
            pendingLicenceFileId = media.fileId
            current = item
            currentUri = item.uri.toString()

            if (crossfadeActive) {
                exo.volume = 0f
                log("crossfade ${effectiveFadeMs}ms -> ${media.trackUri} @$positionMs")
                handler.removeCallbacks(crossfadeTick)
                handler.postDelayed(crossfadeTick, CROSSFADE_TICK_MS)
            } else if (crossfadeMs >= 200L && !muted && playWhenReady) {
                singleFadeActive = true
                singleFadeDurationMs = minOf(crossfadeMs, 1_200L)
                singleFadeStartMs = 0L
                singleFadeFrom = 0.02f
                singleFadeTo = 1f
                singleFadeGain = 0.02f
                exo.volume = volume * 0.02f
                handler.removeCallbacks(crossfadeTick)
                handler.postDelayed(crossfadeTick, CROSSFADE_TICK_MS)
            } else {
                exo.volume = if (muted) 0f else volume
            }

            wantsPlay = playWhenReady
            exo.playWhenReady = playWhenReady
            exo.setMediaSource(sources.createMediaSource(MediaItem.Builder().setUri(item.uri).setMediaId(media.fileId).build()), positionMs)
            loadedSent = false; endedSent = false; status = null; seekPending = false
            initialBufferingMs = 0; stallStartMs = 0; stalls = 0; stalledMs = 0; maxStalledMs = 0; seekStartMs = 0; seekRebufferingMs = 0
            loadAtMs = System.currentTimeMillis()
            item.loadedAtMs = loadAtMs
            // Clear pending-load suppression on the player thread before prepare() so immediate READY/Playing/Error events are delivered.
            if (pendingDec.compareAndSet(true, false)) loadsPending.decrementAndGet()
            exo.prepare()
            log("load ${media.trackUri} @$positionMs play=$playWhenReady${if (preloaded) " (preloaded: session and first segment ready)" else ""}")
        }
    }

    override fun warmMedia(media: PlayableMedia, licence: LicenceChannel) {
        if (released) return
        val uri = uriOf(media)
        if (items[uri]?.isCancelled == false && preloads.containsKey(media.fileId)) return
        preload(media, licence)
    }

    override fun preload(media: PlayableMedia, licence: LicenceChannel) {
        if (released) return
        preloadFileId = media.fileId
        val pGen = preloadGen.incrementAndGet()
        this.licence = licence
        scope.launch {
            if (released || pGen != preloadGen.get()) return@launch
            val uri = uriOf(media)
            val existing = items[uri]
            val item = if (existing != null && !existing.isCancelled) existing else CdnItem(media, http, log).also { items[uri] = it }
            fun dropIfUnused() {
                if (current !== item && !preloads.containsKey(media.fileId)) {
                    items.remove(uri, item)
                    item.cancel()
                }
            }
            val job = scope.async {
                runCatching { item.prefetch() }.onFailure { if (it !is CancellationException) log("preload ${media.trackUri}: CDN failed: ${it.message}") }
                Unit
            }
            item.prefetchJob = job
            if (media.widevinePssh == null) {
                runCatching { job.await() }
            }
            if (released || pGen != preloadGen.get()) {
                dropIfUnused()
                return@launch
            }
            withTimeoutOrNull(CERT_WAIT_MS) { ensureCertificate(licence) }
            if (released || pGen != preloadGen.get()) {
                dropIfUnused()
                return@launch
            }
            withContext(appDispatcher) { if (!released) holdDrm() }
            if (released || pGen != preloadGen.get()) {
                dropIfUnused()
                return@launch
            }
            if (item.peekInit() == null) {
                runCatching { withTimeoutOrNull(1_500L) { item.awaitInit() } }
            }
            val pssh = item.peekInit()?.let(::widevinePssh) ?: media.widevinePssh
            if (pssh == null) return@launch log("preload ${media.trackUri}: no pssh, licence at load")
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, pssh))).build()
            withContext(appDispatcher) {
                if (released || pGen != preloadGen.get() || preloads.containsKey(media.fileId)) {
                    dropIfUnused()
                    return@withContext
                }
                val session = withContext(Handler(exo.playbackLooper).asCoroutineDispatcher()) {
                    if (released || pGen != preloadGen.get()) null
                    else {
                        pendingLicenceFileId = media.fileId
                        drm.acquireSession(null, format)
                    }
                }
                if (session != null) {
                    if (released || pGen != preloadGen.get()) {
                        releaseOnPlayback(session)
                        dropIfUnused()
                    } else {
                        preloads[media.fileId] = session
                        log("preload ${media.trackUri}: first segment in memory, licence requested")
                    }
                } else {
                    dropIfUnused()
                }
            }
        }
    }

    private fun releaseOnPlayback(s: DrmSession) { Handler(exo.playbackLooper).post { s.release(null) } }

    override fun pause() = onPlayer {
        wantsPlay = false
        outgoingExo?.pause()
        // Already paused (or idle/ended/focus-suppressed where evaluate() returns early): confirm it.
        if (!exo.playWhenReady || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED ||
            exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            exo.pause()
            status = Status.PAUSED
            emit(PlayerEvent.Paused(position()))
        } else {
            exo.pause()
        }
    }

    override fun resume() = onPlayer {
        wantsPlay = true
        if (current != null && exo.playbackState == Player.STATE_IDLE) {
            exo.prepare()
        } else if (current != null && exo.playbackState == Player.STATE_ENDED) {
            endedSent = false
            exo.seekTo(0)
            exo.prepare()
        }
        // Resetting playWhenReady before play() prompts Media3 to re-request audio focus after suppression.
        if (!muted && exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            exo.playWhenReady = false
        }
        if (crossfadeActive && outgoingExo != null) {
            outgoingExo?.play()
            handler.removeCallbacks(crossfadeTick)
            handler.post(crossfadeTick)
        } else if (singleFadeActive) {
            handler.removeCallbacks(crossfadeTick)
            handler.post(crossfadeTick)
        }
        if (exo.isPlaying) {
            status = Status.PLAYING
            emit(PlayerEvent.Playing(position()))
        } else {
            exo.play()
            evaluate()
        }
    }

    override fun seekTo(positionMs: Long) = onPlayer {
        if (crossfadeActive || singleFadeActive) cancelCrossfadeLocked(restoreVolume = true)
        seekPending = true
        seekStartMs = System.currentTimeMillis()
        endedSent = false
        exo.seekTo(positionMs)
    }

    override fun stop() {
        loadGen.incrementAndGet()
        preloadGen.incrementAndGet()
        activeCertJob.getAndSet(null)?.cancel()
        abortPendingIo()
        onPlayer {
            cancelCrossfadeLocked(restoreVolume = false)
            current = null
            currentUri = ""
            loadedSent = false
            endedSent = false
            status = null
            seekPending = false
            wantsPlay = false
            handler.removeCallbacks(ticker)
            exo.stop()
            exo.clearMediaItems()
            handedOverSession?.let(::releaseOnPlayback)
            handedOverSession = null
            preloads.values.forEach(::releaseOnPlayback)
            preloads.clear()
            items.values.forEach { it.cancel() }
            items.clear()
        }
    }

    override fun setVolume(volume: Int) = onPlayer {
        this.volume = volume.coerceIn(0, 65535) / 65535f
        applyCrossfadeGainLocked()
    }

    override fun fadeOutCurrent(durationMs: Long) = onPlayer {
        if (released || current == null || muted || durationMs < 50L) return@onPlayer
        if (crossfadeActive) {
            cancelCrossfadeLocked(restoreVolume = false)
        }
        singleFadeActive = true
        singleFadeDurationMs = durationMs
        singleFadeStartMs = System.currentTimeMillis()
        singleFadeFrom = singleFadeGain.coerceIn(0.02f, 1f)
        singleFadeTo = 0.02f
        handler.removeCallbacks(crossfadeTick)
        handler.post(crossfadeTick)
    }

    override fun cancelCrossfade() = onPlayer {
        if (released) return@onPlayer
        cancelCrossfadeLocked(restoreVolume = true)
    }

    /**
     * Muted (a listen mirror: another player is heard): silent, without the audio focus, and an
     * unplugged headset does not pause it (the heard player decides). It still fetches the song,
     * asks its licence and plays it through at its pace, but decrypts and decodes nothing (see
     * [MirrorAudioRenderer]): that was about a sixth of a core for every song the app played itself.
     */
    override fun setMuted(muted: Boolean) = onPlayer {
        if (this.muted == muted) return@onPlayer
        this.muted = muted
        if (muted) cancelCrossfadeLocked(restoreVolume = false)
        exo.setAudioAttributes(MUSIC, !muted)
        exo.setHandleAudioBecomingNoisy(!muted)
        exo.volume = if (muted) 0f else volume
        audioRenderer?.let { exo.createMessage(it).setType(MirrorAudioRenderer.MSG_SET_SILENT).setPayload(muted).send() }
        log("player: ${if (muted) "silent (mirror, not decoded)" else "decoded and heard"}")
    }

    override fun metrics(): PlaybackMetrics {
        val item = current ?: return PlaybackMetrics()
        val ongoing = stallStartMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0
        return PlaybackMetrics(
            totalBytes = item.totalBytes.get(),
            firstBytesLatencyMs = item.firstBytesLatencyMs,
            initialBufferingMs = initialBufferingMs,
            stalls = stalls,
            stalledMs = stalledMs + ongoing,
            maxStalledMs = maxOf(maxStalledMs, ongoing),
            seekRebufferingMs = seekRebufferingMs,
            bandwidthAvgBps = item.bandwidthBps(),
            segmentsFetched = item.segmentsFetched.get(),
        )
    }

    /** Frees the player and its thread; the object is unusable afterwards. */
    fun release() {
        released = true
        loadGen.incrementAndGet()
        preloadGen.incrementAndGet()
        activeCertJob.getAndSet(null)?.cancel()
        abortPendingIo()
        enqueue {
            withContext(appDispatcher) {
                cancelCrossfadeLocked(restoreVolume = false)
                handedOverSession?.let(::releaseOnPlayback)
                handedOverSession = null
                preloads.values.forEach(::releaseOnPlayback)
                preloads.clear()
                items.values.forEach { it.cancel() }
                items.clear()
                if (drmHeld) Handler(exo.playbackLooper).post { drm.release() }
                exo.release()
            }
            playbackThread?.quitSafely()
            thread?.quitSafely()
            commands.close()
            scope.cancel()
        }
    }

    private fun uriOf(media: PlayableMedia) = "myspoti-cdn://audio/${media.fileId}"

    // ------------------------------------------------------------------ events

    private val ticker = object : Runnable {
        override fun run() {
            if (current != null && exo.isPlaying) {
                emit(PlayerEvent.Position(position()))
                // A track that starts on time, at most 250 ms between reports (the core asks for <= 500).
                handler.postDelayed(this, 250)
            }
        }
    }

    private fun seeked() {
        if (!seekPending) return
        seekPending = false
        if (seekStartMs > 0) seekRebufferingMs += System.currentTimeMillis() - seekStartMs
        seekStartMs = 0
        emit(PlayerEvent.Seeked(position()))
    }

    /** One status from Media3's three flags: what Spotify is told depends on audio really playing. */
    private fun evaluate() {
        if (current == null || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) return
        val s = when {
            exo.isPlaying -> Status.PLAYING
            !exo.playWhenReady || exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> Status.PAUSED
            else -> Status.BUFFERING
        }
        if (s == status) return
        status = s
        val pos = position()
        when (s) {
            Status.PLAYING -> {
                if (loadAtMs > 0) {
                    log("first audio ${System.currentTimeMillis() - loadAtMs} ms after load, at $pos ms")
                    loadAtMs = 0
                }
                if (crossfadeActive || singleFadeActive) {
                    crossfadeTick.run()
                }
                emit(PlayerEvent.Playing(pos))
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 250)
            }
            Status.PAUSED -> emit(PlayerEvent.Paused(pos))
            Status.BUFFERING -> emit(PlayerEvent.Buffering(pos))
        }
    }

    private inner class Events : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (current == null) return
            val now = System.currentTimeMillis()
            when (state) {
                Player.STATE_READY -> {
                    handedOverSession?.let(::releaseOnPlayback)
                    handedOverSession = null
                    if (!loadedSent) {
                        loadedSent = true
                        current?.loadedAtMs?.let { initialBufferingMs = now - it }
                        emit(PlayerEvent.Loaded(exo.duration.takeIf { it != C.TIME_UNSET } ?: current!!.media.durationMs))
                    }
                    if (stallStartMs > 0) {
                        val d = now - stallStartMs
                        stalledMs += d
                        maxStalledMs = maxOf(maxStalledMs, d)
                        stallStartMs = 0
                    }
                    seeked()
                }
                Player.STATE_BUFFERING -> if (loadedSent && !seekPending && exo.playWhenReady) { stalls++; stallStartMs = now }
                Player.STATE_ENDED -> if (!endedSent) {
                    endedSent = true
                    handler.removeCallbacks(ticker)
                    status = null
                    val end = exo.duration.takeIf { it != C.TIME_UNSET } ?: position()
                    log("ended at $end ms")
                    emit(PlayerEvent.Ended(end))
                }
            }
            evaluate()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = evaluate()

        override fun onPlaybackSuppressionReasonChanged(reason: Int) = evaluate()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && current != null && wantsPlay) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) wantsPlay = false
                when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> onPlayerPause?.invoke("audio focus lost")
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> onPlayerPause?.invoke("audio becoming noisy")
                }
            }
            evaluate()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek inside buffered data may not leave READY: report it once the position moved.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && exo.playbackState == Player.STATE_READY) handler.post {
                if (exo.playbackState == Player.STATE_READY) seeked()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released || isSupersededPlaybackError(loadsPending.get(), current, error)) {
                log("error ignored (superseded): ${error.errorCodeName}")
                return
            }
            val retrySpec = crossfadeRetrySpec
            if (outgoingExo != null && !loadedSent && retrySpec != null) {
                log("concurrent crossfade failed (${error.errorCodeName}), retrying single-deck playback")
                concurrentDrmBlocked = true
                cancelCrossfadeLocked(restoreVolume = false)
                crossfadeRetrySpec = null
                handedOverSession?.let(::releaseOnPlayback)
                handedOverSession = null
                exo.removeListener(exoListener)
                exo.stop()
                exo.release()
                exo = buildExo().also { it.addListener(exoListener) }
                exo.volume = if (muted) 0f else volume
                exo.playWhenReady = wantsPlay
                pendingLicenceFileId = retrySpec.media.fileId
                exo.setMediaSource(
                    sources.createMediaSource(MediaItem.Builder().setUri(uriOf(retrySpec.media)).setMediaId(retrySpec.media.fileId).build()),
                    retrySpec.positionMs,
                )
                exo.prepare()
                return
            }
            cancelCrossfadeLocked(restoreVolume = false)
            handedOverSession?.let(::releaseOnPlayback)
            handedOverSession = null
            val why = formatPlaybackErrorCause(error)
            log("error ${error.errorCodeName}: $why")
            handler.removeCallbacks(ticker)
            status = null
            emit(PlayerEvent.Error("${error.errorCodeName}: $why", position()))
        }
    }

    private inner class OutgoingEvents : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                handleOutgoingFinishedLocked()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            log("outgoing crossfade stopped early (${error.errorCodeName})")
            if ("DRM" in error.errorCodeName || "DECODER" in error.errorCodeName) concurrentDrmBlocked = true
            handleOutgoingFinishedLocked()
        }
    }

    private val crossfadeTick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (released) return
            if (crossfadeActive) {
                if (exo.isPlaying) {
                    crossfadeRetrySpec = null
                    if (crossfadeStartMs == 0L) {
                        val outDur = outgoingExo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
                            ?: outgoingItem?.media?.durationMs ?: 0L
                        val outPos = outgoingExo?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        val outLeft = if (outDur > 0L) (outDur - outPos).coerceAtLeast(1L) else crossfadeDurationMs
                        crossfadeDurationMs = minOf(crossfadeDurationMs, outLeft).coerceAtLeast(200L)
                        crossfadeStartMs = System.currentTimeMillis()
                        crossfadeFailsafeAtMs = crossfadeStartMs + crossfadeDurationMs + 2_000L
                    }
                    val elapsed = (position() - crossfadeBasePosMs).coerceAtLeast(0L)
                    val raw = if (crossfadeDurationMs <= 0L) 1f else (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
                    crossfadeProgress = maxOf(crossfadeProgress, raw)
                    applyCrossfadeGainLocked()
                    if (crossfadeProgress >= 1f || outgoingExo == null || outgoingExo?.playbackState == Player.STATE_ENDED ||
                        System.currentTimeMillis() >= crossfadeFailsafeAtMs
                    ) {
                        cancelCrossfadeLocked(restoreVolume = true)
                    } else {
                        handler.postDelayed(this, CROSSFADE_TICK_MS)
                    }
                } else {
                    exo.volume = 0f
                    if ((!wantsPlay || !exo.playWhenReady) && outgoingExo?.playWhenReady == true) {
                        outgoingExo?.pause()
                    }
                    if (outgoingExo == null || System.currentTimeMillis() >= crossfadeFailsafeAtMs) {
                        handleOutgoingFinishedLocked()
                    } else {
                        handler.postDelayed(this, CROSSFADE_TICK_MS)
                    }
                }
            } else if (singleFadeActive) {
                if (exo.isPlaying) {
                    if (singleFadeStartMs == 0L) singleFadeStartMs = System.currentTimeMillis()
                    val raw = if (singleFadeDurationMs <= 0L) 1f else ((System.currentTimeMillis() - singleFadeStartMs).toFloat() / singleFadeDurationMs).coerceIn(0f, 1f)
                    singleFadeGain = singleFadeFrom + (singleFadeTo - singleFadeFrom) * raw
                    applyCrossfadeGainLocked()
                    if (raw >= 1f) {
                        singleFadeActive = false
                        if (singleFadeTo >= 0.99f) {
                            singleFadeGain = 1f
                            exo.volume = if (muted) 0f else volume
                        }
                    } else {
                        handler.postDelayed(this, CROSSFADE_TICK_MS)
                    }
                } else if (wantsPlay && exo.playWhenReady) {
                    handler.postDelayed(this, CROSSFADE_TICK_MS)
                }
            }
        }
    }

    private fun applyCrossfadeGainLocked() {
        val g = if (muted) 0f else volume
        if (crossfadeActive) {
            val angle = crossfadeProgress.coerceIn(0f, 1f) * (Math.PI / 2.0)
            exo.volume = g * kotlin.math.sin(angle).toFloat()
            outgoingExo?.volume = g * kotlin.math.cos(angle).toFloat()
        } else if (singleFadeActive) {
            exo.volume = g * singleFadeGain.coerceIn(0f, 1f)
        } else {
            exo.volume = g
        }
    }

    private fun handleOutgoingFinishedLocked() {
        val currentFraction = kotlin.math.sin(crossfadeProgress.coerceIn(0f, 1f) * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
        val wasCrossfading = crossfadeActive
        cancelCrossfadeLocked(restoreVolume = false)
        if (wasCrossfading && currentFraction < 0.98f && current != null && !muted) {
            singleFadeActive = true
            singleFadeFrom = maxOf(currentFraction, 0.05f)
            singleFadeTo = 1f
            singleFadeGain = singleFadeFrom
            singleFadeDurationMs = 400L
            singleFadeStartMs = if (exo.isPlaying) System.currentTimeMillis() else 0L
            applyCrossfadeGainLocked()
            handler.removeCallbacks(crossfadeTick)
            handler.postDelayed(crossfadeTick, CROSSFADE_TICK_MS)
        } else {
            exo.volume = if (muted) 0f else volume
        }
    }

    private fun cancelCrossfadeLocked(restoreVolume: Boolean) {
        handler.removeCallbacks(crossfadeTick)
        crossfadeActive = false
        crossfadeStartMs = 0L
        crossfadeDurationMs = 0L
        crossfadeBasePosMs = 0L
        crossfadeProgress = 0f
        crossfadeRetrySpec = null
        singleFadeActive = false
        singleFadeGain = 1f
        outgoingExo?.let { old ->
            runCatching {
                old.removeListener(outgoingListener)
                old.stop()
                old.clearMediaItems()
                old.release()
            }
        }
        outgoingExo = null
        outgoingItem?.let { stale ->
            if (stale !== current) {
                items.remove(stale.uri.toString(), stale)
                stale.cancel()
            }
        }
        outgoingItem = null
        if (restoreVolume) exo.volume = if (muted) 0f else volume
    }

    // ------------------------------------------------------------------ licences

    /** Media3's DRM requests, run on its DRM request thread: blocking there is what it expects. */
    private inner class LicenceCallback : MediaDrmCallback {
        override fun executeKeyRequest(uuid: java.util.UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
            val channel = licence ?: throw failure("no licence channel", null)
            val ownerFileId = pendingLicenceFileId ?: current?.media?.fileId
            val t0 = System.currentTimeMillis()
            val deferred = scope.async { channel.licence(request.data) }
            val job = LicenceJob(ownerFileId, deferred)
            activeLicenceJob.getAndSet(job)?.deferred?.cancel()
            return try {
                runBlocking { deferred.await() }.also {
                    log("licence: challenge ${request.data.size} B -> licence ${it.size} B in ${System.currentTimeMillis() - t0} ms")
                }
            } catch (e: CancellationException) {
                log("licence cancelled after ${System.currentTimeMillis() - t0} ms")
                throw failure("licence cancelled", CancelledAudioException("licence cancelled", e, ownerFileId))
            } catch (e: Exception) {
                log("licence refused after ${System.currentTimeMillis() - t0} ms: ${e.message}")
                throw failure("licence", e)
            } finally {
                activeLicenceJob.compareAndSet(job, null)
            }
        }

        /** Device provisioning goes to Google's server named by the CDM, as Media3's HttpMediaDrmCallback does it. */
        override fun executeProvisionRequest(uuid: java.util.UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
            val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
            log("widevine: provisioning the device")
            return try {
                val r = runBlocking { http.request(HttpRequest("POST", url, emptyMap())) }
                if (r.status !in 200..299) throw java.io.IOException("HTTP ${r.status}")
                r.body
            } catch (e: Exception) {
                log("widevine: provisioning failed: ${e.message}")
                throw failure("provisioning", e)
            }
        }

        private fun failure(what: String, cause: Throwable?) =
            MediaDrmCallbackException(DataSpec(Uri.EMPTY), Uri.EMPTY, emptyMap(), 0, cause ?: java.io.IOException(what))
    }

    private companion object {
        const val CERT_WAIT_MS = 3_000L
        const val CROSSFADE_TICK_MS = 40L
    }
}

internal fun isSupersededPlaybackError(loadsPending: Int, current: CdnItem?, error: PlaybackException): Boolean {
    if (loadsPending > 0 || current == null || current.isCancelled) return true
    val activeFileId = current.media.fileId
    val cancelledCause = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<CancelledAudioException>()
        .firstOrNull()
    return cancelledCause != null && (cancelledCause.fileId == null || cancelledCause.fileId != activeFileId)
}

internal fun formatPlaybackErrorCause(error: PlaybackException): String {
    val causes = generateSequence<Throwable>(error.cause) { it.cause }
        .mapNotNull { t ->
            val m = t.message?.takeIf { it.isNotBlank() }
            val simple = t::class.simpleName
            when {
                m != null && simple != null && simple !in m -> "$simple: $m"
                m != null -> m
                else -> simple
            }
        }
        .distinct()
        .toList()
    return causes.takeIf { it.isNotEmpty() }?.joinToString(" <- ") ?: error.message.orEmpty()
}
