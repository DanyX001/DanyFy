// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package com.music.spotui.di

import android.content.Context
import android.content.Intent
import app.myspoti.engine.Cluster
import app.myspoti.engine.DrmPlayer
import app.myspoti.engine.EngineEvent
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.HttpResponse
import app.myspoti.engine.NativeEngine
import app.myspoti.engine.PlayerEvent
import app.myspoti.engine.android.AndroidEnvironment
import app.myspoti.engine.android.ChromiumHttp
import app.myspoti.engine.android.Media3DrmPlayer
import app.myspoti.engine.android.OkHttpWebSockets
import app.myspoti.engine.android.WidevineInfo
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.NativeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The native engine (:engine-android) as the account's audio ([AccountAudio]): with its setting
 * on, the app's playback commands go to it and it plays the account's songs itself (Media3 and the
 * phone's own Widevine), the Web Player page staying as the account's remote: the state, the queue
 * and the tokens. Off (the default while it is being tried), the page plays as before. The engine
 * registers a device of its own on the account, shown as this phone.
 *
 * An ad the account's player is given is never asked for nor played by the engine: it moves on
 * to the next song. While an extension plays, the engine is the listen mirror's device
 * ([MirrorEngine], [AccountSync]): it plays the extension's song unheard, without the audio focus,
 * at its smallest file, and the page is not used for it. With the engine off or left aside, the
 * mirror is the page's, and a running engine leaves the account's devices meanwhile ([leaveForMirror]).
 *
 * Debug builds drive it by hand too (TestControlReceiver): native-play <uri> [--ez l3 true],
 * native-pause, native-resume, native-seek <ms>, native-next [<uri>], native-prev, native-transfer,
 * native-stop, native-status, native-log [<lines>], native-audio on|off. Every step goes to the
 * Logbook as "native: …": HTTP calls with state PUT debug_source and melody types, licence sizes
 * and latency, CDN ranges, Widevine level, time to first audio.
 */
/** Target context/track used to recover onto a clean Connect device ID after a server ad lock. */
internal data class AdRecoveryTarget(
    val contextUri: String?,
    val trackUri: String?,
    val index: Int? = null,
    val order: List<String>? = null,
    val shuffle: Boolean? = null,
    val playlistTracks: List<String>? = null,
)

/** Pure cooldown and target-selection helpers for bypassing server-inserted phone ads. */
internal object AdRecoveryPolicy {
    const val WINDOW_MS = 60_000L
    const val MAX_ATTEMPTS = 3
    const val WEB_TAKEOVER_MIN_MS = 5_000L
    const val RECOVERY_DEDUP_MS = 5_000L
    private const val INDEXED_ROW_FROM = 100

    fun canRecover(
        recentMs: List<Long>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Boolean = recentMs.count { nowMs - it < windowMs } < maxAttempts

    fun pickRecoveryTarget(
        hintCtx: String?,
        hintTrack: String?,
        appTarget: AdRecoveryTarget?,
        lastPlayed: AdRecoveryTarget?,
        hintCurrentTrack: String? = null,
        hintNextProvider: String? = null,
        repeatAll: Boolean = false,
        skippedFromTrack: String? = null,
    ): AdRecoveryTarget? {
        fun clean(uri: String?) = uri?.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }

        val cleanHintCtx = clean(hintCtx)
        val cleanHintTrack = clean(hintTrack)
        val cleanHintCur = clean(hintCurrentTrack)
        val cleanAppCtx = clean(appTarget?.contextUri)
        val cleanAppTrack = clean(appTarget?.trackUri)
        val cleanLastCtx = clean(lastPlayed?.contextUri)
        val cleanLastTrack = clean(lastPlayed?.trackUri)
        val cleanSkippedFrom = clean(skippedFromTrack)
        val curTrack = cleanHintCur ?: cleanLastTrack

        // Autoplay tracks do not belong to the playlist context: play them standalone so Spotify
        // does not reject `(playlistCtx, nonPlaylistTrack)` and restart the playlist at song #1.
        if (cleanHintTrack != null && hintNextProvider.equals("autoplay", ignoreCase = true)) {
            return AdRecoveryTarget(
                contextUri = cleanHintTrack,
                trackUri = null,
                index = null,
                order = null,
                shuffle = appTarget?.shuffle ?: lastPlayed?.shuffle,
            )
        }

        // Defend against album `group_uri` poisoning when `hintCtx` collapsed to `spotify:album:...`
        // while the known active list context (containing `curTrack`) is a playlist/collection.
        val knownListCtx = listOfNotNull(cleanAppCtx, cleanLastCtx)
            .firstOrNull { !it.startsWith("spotify:album:") && !it.startsWith("spotify:track:") && !it.startsWith("spotify:episode:") }
        val appPlaylist = appTarget?.playlistTracks?.takeIf { cleanAppCtx == knownListCtx || cleanAppCtx == null }
        val curInKnownPlaylist = appPlaylist == null || curTrack == null || curTrack in appPlaylist
        val ctx = when {
            cleanHintCtx != null && cleanHintCtx.startsWith("spotify:album:") && knownListCtx != null && curInKnownPlaylist -> knownListCtx
            cleanHintCtx != null -> cleanHintCtx
            else -> cleanAppCtx ?: cleanLastCtx
        }

        val matchingAppTrack = cleanAppTrack?.takeIf { cleanAppCtx == null || cleanAppCtx == ctx || ctx == null }
        val playlistForCtx = appTarget?.playlistTracks?.takeIf { cleanAppCtx == null || cleanAppCtx == ctx }
        val isListContext = ctx != null && (ctx.startsWith("spotify:playlist:") || ctx.startsWith("spotify:collection:"))
        val verifiedHintTrack = cleanHintTrack?.takeIf { candidate ->
            candidate != cleanSkippedFrom &&
                (playlistForCtx == null || !isListContext ||
                    hintNextProvider.equals("queue", ignoreCase = true) ||
                    hintNextProvider.equals("autoplay", ignoreCase = true) ||
                    candidate in playlistForCtx)
        }
        val verifiedAppTrack = matchingAppTrack?.takeIf { candidate ->
            candidate != cleanSkippedFrom &&
                (playlistForCtx == null || !isListContext || candidate in playlistForCtx)
        }
        val effectiveShuffle = appTarget?.shuffle ?: lastPlayed?.shuffle
        val resolvedOrder = appTarget?.order?.takeIf { (ctx == null || it.isNotEmpty() && ctx == appTarget.contextUri) }

        var track: String? = verifiedHintTrack ?: verifiedAppTrack
        var computedIndex: Int? = null
        if (track == null && playlistForCtx != null && curTrack != null) {
            val curIdx = lastPlayed?.takeIf { it.contextUri == ctx && it.trackUri == curTrack }?.index
                ?.takeIf { it in playlistForCtx.indices && playlistForCtx[it] == curTrack }
                ?: playlistForCtx.indexOfFirst { it == curTrack }
            if (curIdx >= 0) {
                val nextIdx = curIdx + 1
                val sequentialOrder = effectiveShuffle != true || !resolvedOrder.isNullOrEmpty()
                if (nextIdx in playlistForCtx.indices) {
                    track = playlistForCtx[nextIdx]
                    computedIndex = nextIdx
                } else if (repeatAll && playlistForCtx.isNotEmpty()) {
                    track = playlistForCtx[0]
                    computedIndex = 0
                } else if (!sequentialOrder) {
                    val altIdx = playlistForCtx.indexOfFirst { it != curTrack }.takeIf { it >= 0 } ?: 0
                    track = playlistForCtx[altIdx]
                    computedIndex = altIdx
                } else {
                    return null
                }
            }
        }
        if (track == null) {
            track = (curTrack ?: cleanLastTrack)?.takeIf { it != cleanSkippedFrom }
        }

        val resolvedCtx = ctx ?: track ?: return null
        val resolvedTrack = track?.takeIf { it != resolvedCtx }
        val checkedOrder = resolvedOrder?.takeIf { resolvedCtx == appTarget?.contextUri && it.isNotEmpty() }

        if (computedIndex == null && resolvedTrack != null && playlistForCtx != null && checkedOrder == null) {
            val curIdx = curTrack?.let { c ->
                lastPlayed?.takeIf { it.contextUri == resolvedCtx && it.trackUri == c }?.index
                    ?.takeIf { it in playlistForCtx.indices && playlistForCtx[it] == c }
                    ?: playlistForCtx.indexOfFirst { it == c }
            } ?: -1
            val found = if (curIdx >= 0) {
                (curIdx + 1 until playlistForCtx.size).firstOrNull { playlistForCtx[it] == resolvedTrack }
                    ?: playlistForCtx.indexOfFirst { it == resolvedTrack }
            } else {
                playlistForCtx.indexOfFirst { it == resolvedTrack }
            }
            if (found >= 0) computedIndex = found
        }

        val resolvedIndex = when {
            checkedOrder != null -> null
            appTarget?.index != null && resolvedTrack == appTarget.trackUri -> appTarget.index
            computedIndex != null && computedIndex >= INDEXED_ROW_FROM -> computedIndex
            else -> null
        }

        return AdRecoveryTarget(
            contextUri = resolvedCtx,
            trackUri = resolvedTrack,
            index = resolvedIndex,
            order = checkedOrder,
            shuffle = effectiveShuffle,
        )
    }
}

object NativeAudio : app.myspoti.spotify.AccountAudio, app.myspoti.spotify.sync.MirrorEngine {
    private const val PREFS = "native_audio"

    /** The setting: the engine plays the account's songs (on unless turned off). */
    @Volatile var enabled: Boolean = Graph.app.getSharedPreferences(PREFS, 0).getBoolean("on", true)
        private set

    /** Until when the engine is left aside after it could not play: the page plays meanwhile. */
    @Volatile private var asideUntil = 0L
    @Volatile private var setAsideAtMs = 0L
    /** App-level provider of the next non-ad context/track when bypassing a native/WebView ad break. */
    @Volatile internal var adRecoveryTarget: ((NativeEngine.AdRecoveryHint?) -> AdRecoveryTarget?)? = null
    /** App-level provider of the track URI the user most recently pressed Next away from. */
    @Volatile internal var lastSkippedFromTrack: (() -> String?)? = null
    /** App-level callback invoked when a standalone track context finishes with no further state-machine transition. */
    @Volatile internal var onContextEnded: ((AdRecoveryTarget?) -> Unit)? = null
    @Volatile private var lastLocalPlay: AdRecoveryTarget? = null
    @Volatile private var recoveringAd = false
    @Volatile private var lastAdRecoveryAtMs = 0L
    private val adRecoveryMutex = Mutex()
    private val adRecoveryTimes = ArrayDeque<Long>()

    override val on: Boolean get() = enabled && System.currentTimeMillis() >= asideUntil

    /** The engine could not play ([why]): the page is the player for [ASIDE_MS], then the engine is tried again. */
    private fun setAside(why: String) {
        val now = System.currentTimeMillis()
        setAsideAtMs = now
        asideUntil = now + ASIDE_MS
        consecutivePlayerFailures = 0
        consecutiveRateLimitFailures = 0
        consecutiveStartFailures = 0
        log("left aside for ${ASIDE_MS / 60_000} min, the page plays: $why")
        scope.launch { runCatching { stop() } }
    }

    private const val ASIDE_MS = 2 * 60_000L
    private const val MAX_PLAYER_FAILURES_BEFORE_ASIDE = 3

    /**
     * The page's listen-sync mirror starts (an extension plays, the page plays the song unheard): the engine
     * stops and leaves the account's devices, so the page's play is the page's own. The next play of
     * the account's songs starts it again.
     */
    fun leaveForMirror() {
        if (engine == null) return
        log("the listen mirror plays on the page: the engine stops until the account's songs play again")
        scope.launch { runCatching { stop() } }
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        asideUntil = 0L
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("on", value).apply()
        log("the engine plays the account's songs: ${if (value) "on" else "off"}")
        if (!value) scope.launch { runCatching { stop() } }
    }

    /** Crossfade duration in seconds passed from the user's playback transition setting (0 = off). */
    @Volatile private var crossfadeSeconds: Int = 0

    override fun setCrossfadeSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        if (crossfadeSeconds == clamped && engine?.crossfadeSeconds == clamped) return
        crossfadeSeconds = clamped
        engine?.crossfadeSeconds = clamped
        log("crossfade set to ${clamped}s")
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        engine?.autoplayEnabled = enabled
    }

    override val deviceId: String? get() = engine?.deviceId
    override val holdsPlayback: Boolean get() = engine?.now?.value != null
    override val isRecoveringAd: Boolean get() = recoveringAd

    /**
     * The listen mirror holds the engine ([EngineSyncPage], from its first play to its end), or the
     * engine is muted by it: its songs are the mirror's, not the account's to show or follow.
     */
    @Volatile private var mirrorHolds = false
    override fun mirrorHeld(on: Boolean) { mirrorHolds = on }
    override val mirrors: Boolean get() = engine?.let { mirrorHolds || it.muted } == true

    /**
     * The engine's own playback, live from its player: [app.myspoti.spotify.SpotifyPlayer] shows it
     * over the page's state of it, which comes late and from the account's servers (a resume shown
     * six seconds back, a new song eight seconds in).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val now: kotlinx.coroutines.flow.StateFlow<NativeEngine.Now?> by lazy {
        _current.flatMapLatest { it?.now ?: kotlinx.coroutines.flow.flowOf(null) }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    /** Live Spotify Connect cluster pushed over Dealer while the engine is registered. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val cluster: kotlinx.coroutines.flow.StateFlow<Cluster?> by lazy {
        _current.flatMapLatest { it?.cluster ?: kotlinx.coroutines.flow.flowOf(null) }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    /** Hands the engine to the account's player and its device list; once, when the runtime is up. */
    fun attach() {
        val spotify = MyspotiRuntime.spotify
        spotify.player.audio = this
        spotify.devices.ownDeviceId = { deviceId }
    }

    /**
     * Pre-starts and registers the Connect device without transferring playback so live [cluster]
     * updates and direct remote commands are ready as soon as another player is connected.
     */
    suspend fun warmUp() {
        if (!on || engine != null) return
        runCatching { ready() }.onFailure { log("warm-up skipped: ${it.message}") }
    }

    /** The engine, started and registered first if it was not (the account's player, or the listen mirror's device). */
    override suspend fun ready(): NativeEngine = started(Graph.app, forceL3 = false)

    private val _current = kotlinx.coroutines.flow.MutableStateFlow<NativeEngine?>(null)
    /** The engine registered on the account now, or null. */
    override val current: kotlinx.coroutines.flow.StateFlow<NativeEngine?> = _current

    private suspend fun readyForCommand(): NativeEngine = try {
        ready().also { consecutiveStartFailures = 0 }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        if (++consecutiveStartFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE) {
            consecutiveStartFailures = 0
            setAside("it did not start (${t.message})")
        }
        throw t
    }

    override suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean?) {
        val e = readyForCommand()
        foreground()
        val prev = lastLocalPlay
        lastLocalPlay = AdRecoveryTarget(
            contextUri = contextUri,
            trackUri = trackUri,
            index = index,
            order = order,
            shuffle = shuffle,
            playlistTracks = prev?.playlistTracks?.takeIf { contextUri == prev.contextUri },
        )
        commandAtMs = System.currentTimeMillis()
        val since = e.clockMs()
        try {
            // A song played alone is its own context.
            if (trackUri != null && trackUri != contextUri) e.play(contextUri, trackUri, index, order = order?.takeIf { it.isNotEmpty() }, shuffle = shuffle)
            else e.play(contextUri, shuffle = shuffle)
            // The seek once the song is loaded and reported here: sent before, it put the song played before back.
            if (positionMs != null && positionMs > 0 && e.awaitLoaded(trackUri ?: contextUri.takeIf { it.startsWith("spotify:track:") }, since, LOAD_WAIT_MS)) e.seekTo(positionMs)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            noteCommandFailure(t)
            throw t
        }
    }
    override suspend fun pause() { engine?.takeIf { it.now.value != null }?.pause() }
    override suspend fun resume() {
        val e = readyForCommand()
        foreground()
        val needsPlay = ensureActiveTrack(e, "play")
        if (needsPlay && e.now.value?.playing == true) return
        try {
            e.resume()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            noteCommandFailure(t)
            throw t
        }
    }

    /** The playback service in the foreground before the engine's player asks for the audio (Android refuses it otherwise). */
    private suspend fun foreground() {
        if (app.myspoti.host.android.PlaybackService.isRunning) return
        app.myspoti.host.android.PlaybackService.ensureStarted(Graph.app)
        kotlinx.coroutines.withTimeoutOrNull(FOREGROUND_MS) { while (!app.myspoti.host.android.PlaybackService.isRunning) kotlinx.coroutines.delay(25) }
    }
    private const val FOREGROUND_MS = 2_000L
    private const val LOAD_WAIT_MS = 6_000L
    override suspend fun seek(positionMs: Long) {
        val e = engine?.takeIf { it.now.value != null } ?: error("no active track on native engine")
        try {
            e.seekTo(positionMs)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            noteCommandFailure(t)
            throw t
        }
    }
    override suspend fun next() {
        val e = readyForCommand()
        foreground()
        ensureActiveTrack(e, "restore")
        commandAtMs = System.currentTimeMillis()
        // Silence/fade any ongoing crossfade or old track immediately so the speaker does not keep
        // playing the previous song for 1-2 s while skip_next + replace_state complete.
        player?.fadeOutCurrent(160L)
        try {
            e.skipNext()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            noteCommandFailure(t)
            throw t
        }
    }
    override suspend fun previous() {
        val e = readyForCommand()
        foreground()
        ensureActiveTrack(e, "restore")
        player?.fadeOutCurrent(160L)
        try {
            e.previous()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            noteCommandFailure(t)
            throw t
        }
    }

    /** Active remote Connect device id reported by the engine's dealer cluster, if any. */
    val remoteDeviceId: String?
        get() = engine?.let { e -> e.cluster.value?.activeDeviceId?.takeIf { it.isNotBlank() && it != e.deviceId } }

    private suspend fun engineOrReady() = engine ?: if (on) runCatching { ready() }.getOrNull() else null

    private suspend fun sendRemote(
        endpoint: String,
        targetDeviceId: String?,
        fields: JsonObject = JsonObject(emptyMap()),
    ): Boolean {
        val e = engineOrReady() ?: return false
        return runCatching { e.remoteCommand(endpoint, fields, targetDeviceId ?: e.cluster.value?.activeDeviceId) }
            .onFailure { log("remote $endpoint failed: ${it.message}") }
            .getOrDefault(false)
    }

    suspend fun remoteVolume(level: Double, targetDeviceId: String? = null): Boolean {
        val e = engineOrReady() ?: return false
        return runCatching { e.remoteVolume(level, targetDeviceId ?: e.cluster.value?.activeDeviceId) }
            .onFailure { log("remote volume failed: ${it.message}") }
            .getOrDefault(false)
    }

    override suspend fun remotePlay(
        contextUri: String,
        trackUri: String?,
        index: Int?,
        positionMs: Long?,
        order: List<String>?,
        shuffle: Boolean?,
        targetDeviceId: String?,
    ): Boolean {
        val e = engineOrReady() ?: return false
        val target = targetDeviceId
            ?: e.cluster.value?.activeDeviceId
            ?: withTimeoutOrNull(1_500L) { e.cluster.first { it?.activeDeviceId != null } }?.activeDeviceId
        val sent = runCatching {
            e.remotePlay(
                contextUri = contextUri,
                trackUri = trackUri,
                trackIndex = index,
                order = order,
                shuffle = shuffle,
                targetDeviceId = target,
            )
        }.onFailure { log("remote play failed: ${it.message}") }.getOrDefault(false)
        if (sent && positionMs != null && positionMs > 0L) {
            if (trackUri != null && e.cluster.value?.trackUri != trackUri) {
                withTimeoutOrNull(2_500L) { e.cluster.first { it?.trackUri == trackUri } }
            }
            remoteSeek(positionMs, target)
        }
        return sent
    }

    override suspend fun remotePause(targetDeviceId: String?): Boolean = sendRemote("pause", targetDeviceId)
    override suspend fun remoteResume(targetDeviceId: String?): Boolean = sendRemote("resume", targetDeviceId)
    override suspend fun remoteSeek(positionMs: Long, targetDeviceId: String?): Boolean =
        sendRemote("seek_to", targetDeviceId, buildJsonObject { put("value", positionMs.toDouble()) })
    override suspend fun remoteNext(targetDeviceId: String?): Boolean = sendRemote("skip_next", targetDeviceId)
    override suspend fun remotePrevious(positionMs: Long, targetDeviceId: String?): Boolean =
        if (positionMs > 3_000L) remoteSeek(0L, targetDeviceId) else sendRemote("skip_prev", targetDeviceId)
    fun fadeOutForSkip(abortInFlightLoad: Boolean = false) {
        player?.fadeOutCurrent(120L)
        if (abortInFlightLoad) {
            engine?.abortPendingTrackLoad()
        }
    }

    override suspend fun skipTo(
        contextUri: String?,
        trackUri: String,
        trackUid: String?,
        provider: String?,
        order: List<String>?,
        shuffle: Boolean?,
        steps: Int?,
    ) {
        val e = readyForCommand()
        foreground()
        commandAtMs = System.currentTimeMillis()
        player?.fadeOutCurrent(120L)
        val rawCtx = contextUri?.takeIf { it.isNotBlank() }
            ?: e.now.value?.contextUri?.takeIf { it.isNotBlank() }
            ?: lastLocalPlay?.contextUri?.takeIf { it.isNotBlank() }
        val resolvedCtx = if (rawCtx != null && rawCtx.startsWith("spotify:track:") && rawCtx != trackUri && (provider == "autoplay" || steps == null || steps > 1)) {
            trackUri
        } else {
            rawCtx
        }
        val idx = if (resolvedCtx != null && !resolvedCtx.startsWith("spotify:track:") && order.isNullOrEmpty()) {
            SongPlayer.contextTrackIndex(resolvedCtx, trackUri, order)
        } else {
            null
        }
        val prev = lastLocalPlay
        if (resolvedCtx != null) {
            lastLocalPlay = AdRecoveryTarget(
                contextUri = resolvedCtx,
                trackUri = trackUri,
                index = idx,
                order = order,
                shuffle = shuffle,
                playlistTracks = prev?.playlistTracks?.takeIf { resolvedCtx == prev.contextUri },
            )
        }
        val effectiveSteps = if (resolvedCtx != null && provider != "queue" && provider != "autoplay") null else steps
        try {
            e.skipTo(resolvedCtx, trackUri, trackUid, provider, order, shuffle, effectiveSteps, trackIndex = idx)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            noteCommandFailure(t)
            throw t
        }
    }

    /** Claims active Connect playback onto [e] before a resume/skip when [e] has no loaded track; returns true if transferred. */
    private suspend fun ensureActiveTrack(e: NativeEngine, transferMode: String): Boolean {
        if (e.now.value != null && !e.playsElsewhere.value) return false
        var transferred = false
        val active = e.cluster.value?.activeDeviceId
        if (e.playsElsewhere.value || (active != null && active != e.deviceId)) {
            log("claiming active playback onto native engine before command (was ${active ?: "elsewhere"})")
            e.transfer(transferMode)
            transferred = true
            withTimeoutOrNull(LOAD_WAIT_MS) { e.now.first { it != null } }
        }
        check(e.now.value != null) { "no active track on native engine" }
        return transferred
    }

    private fun noteCommandFailure(t: Throwable) {
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        val rateLimited = "429" in msg || "RateLimited" in msg
        val adRefused = "ad" in lower && ("disallow" in lower || "403" in msg || "400" in msg)
        val repeated = if (rateLimited) {
            ++consecutiveRateLimitFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE
        } else {
            ++consecutivePlayerFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE
        }
        if (adRefused) {
            val active = engine
            scope.launch { recoverFromAd(active, msg.ifEmpty { t.javaClass.simpleName }) }
        } else if (repeated) {
            setAside(msg.ifEmpty { t.javaClass.simpleName })
        }
    }

    /**
     * Leaves a WebView ad break on this phone by clearing an old aside hold (after [AdRecoveryPolicy.WEB_TAKEOVER_MIN_MS])
     * and starting playback on a brand-new native engine `deviceId`.
     */
    internal suspend fun bypassWebAdToNative(target: AdRecoveryTarget?): Boolean {
        if (!enabled || recoveringAd || holdsPlayback) return false
        val now = System.currentTimeMillis()
        if (now - lastAdRecoveryAtMs < AdRecoveryPolicy.RECOVERY_DEDUP_MS) return false
        if (now < asideUntil && now - setAsideAtMs < AdRecoveryPolicy.WEB_TAKEOVER_MIN_MS) return false
        asideUntil = 0L
        return recoverFromAd(engine, "local WebView ad break", explicitTarget = target)
    }

    /**
     * Replaces an ad-locked [NativeEngine] registration with a fresh random `deviceId` instead of
     * falling back to the WebView ad break, resuming the next non-ad track directly on native audio.
     */
    private suspend fun recoverFromAd(
        failed: NativeEngine?,
        reason: String,
        explicitTarget: AdRecoveryTarget? = null,
    ): Boolean = adRecoveryMutex.withLock {
        val now = System.currentTimeMillis()
        if ((failed == null || explicitTarget != null) && now - lastAdRecoveryAtMs < AdRecoveryPolicy.RECOVERY_DEDUP_MS) {
            return@withLock false
        }
        if (explicitTarget != null && holdsPlayback) {
            return@withLock false
        }
        while (adRecoveryTimes.isNotEmpty() && now - adRecoveryTimes.first() >= AdRecoveryPolicy.WINDOW_MS) {
            adRecoveryTimes.removeFirst()
        }
        val currentEngine = lock.withLock { engine }
        if (failed != null && currentEngine !== failed && (explicitTarget == null || currentEngine != null)) {
            return@withLock false
        }
        if (!AdRecoveryPolicy.canRecover(adRecoveryTimes.toList(), now)) {
            setAside(reason)
            return@withLock false
        }
        recoveringAd = true
        try {
            val snap = (failed ?: currentEngine)?.adRecoverySnapshot()
            val appTarget = explicitTarget ?: runCatching { adRecoveryTarget?.invoke(snap) }.getOrNull()
            val skippedFrom = runCatching { lastSkippedFromTrack?.invoke() }.getOrNull()
            val target = AdRecoveryPolicy.pickRecoveryTarget(
                hintCtx = snap?.contextUri,
                hintTrack = snap?.nextTrackUri,
                appTarget = appTarget,
                lastPlayed = lastLocalPlay,
                hintCurrentTrack = snap?.trackUri,
                hintNextProvider = snap?.nextProvider,
                repeatAll = SongPlayer.repeatMode.value == 1,
                skippedFromTrack = skippedFrom,
            )
            adRecoveryTimes.addLast(now)
            lastAdRecoveryAtMs = now
            log("recycling native device after ad ($reason) -> ${target?.trackUri ?: target?.contextUri ?: "no target"}")
            var toClose: NativeEngine? = null
            var toRelease: Media3DrmPlayer? = null
            lock.withLock {
                val active = engine
                if (active != null && (failed == null || active === failed)) {
                    toClose = active
                    toRelease = player
                    engine = null
                    player = null
                    _current.value = null
                }
            }
            if (toClose != null || toRelease != null) {
                scope.launch {
                    runCatching { withTimeoutOrNull(1_500L) { toClose?.close() } }
                    toRelease?.release()
                }
            }
            if (target?.contextUri == null) return@withLock false
            return@withLock runCatching {
                val fresh = started(Graph.app, forceL3 = false)
                foreground()
                commandAtMs = System.currentTimeMillis()
                lastLocalPlay = target
                val ordered = target.order?.takeIf { it.isNotEmpty() }
                runCatching {
                    fresh.play(target.contextUri, target.trackUri, target.index, order = ordered, shuffle = target.shuffle)
                }.getOrElse { firstErr ->
                    if (target.trackUri != null && target.trackUri != target.contextUri) {
                        log("track-targeted recovery failed (${firstErr.message}); retrying ${target.trackUri} standalone")
                        fresh.play(target.trackUri, shuffle = target.shuffle)
                    } else {
                        throw firstErr
                    }
                }
                true
            }.onFailure { err ->
                log("native ad recovery failed: ${err.message}")
            }.getOrDefault(false)
        } finally {
            recoveringAd = false
        }
    }

    /**
     * Pulls Connect playback onto the native engine when another non-[chosenRemote] device holds it
     * so resuming never hands playback back to the WebView page.
     */
    suspend fun takeBack(chosenRemote: String?): Boolean {
        if (!on) return false
        val e = runCatching { ready() }.getOrNull() ?: return false
        if (e.now.value != null && !e.playsElsewhere.value) return true
        val active = e.cluster.value?.activeDeviceId
        if (active != null && active == chosenRemote) return false
        if (active != null && active.startsWith("social-connect")) return true
        if (e.playsElsewhere.value || (active != null && active != e.deviceId)) {
            log("pulling Connect playback to native engine (was ${active ?: "elsewhere"})")
            e.transfer("restore")
        }
        return true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var engine: NativeEngine? = null
    private var player: Media3DrmPlayer? = null
    private var http: ChromiumHttp? = null
    @Volatile private var commandAtMs = 0L
    @Volatile private var lastEvent = ""
    @Volatile private var consecutivePlayerFailures = 0
    @Volatile private var consecutiveRateLimitFailures = 0
    @Volatile private var consecutiveStartFailures = 0
    private val ring = ArrayDeque<String>()

    private fun log(line: String) {
        Logbook.log("native: $line")
        synchronized(ring) {
            ring.addLast("${System.currentTimeMillis() % 100_000_000} $line")
            while (ring.size > 400) ring.removeFirst()
        }
    }

    /** Runs [intent]'s native-* command; returns the short reply the broadcast carries back. */
    fun command(context: Context, cmd: String, intent: Intent): String {
        val arg = intent.getStringExtra("arg")?.trim().orEmpty()
        val app = context.applicationContext
        if (cmd == "native-status") return status()
        if (cmd == "native-audio") { if (arg == "on" || arg == "off") set(app, arg == "on"); return "native audio ${if (enabled) "on" else "off"}${if (enabled && !on) " (left aside, the page plays)" else ""}" }
        if (cmd == "native-log") return synchronized(ring) { ring.toList().takeLast(arg.toIntOrNull()?.coerceIn(1, 400) ?: 60).joinToString("\n") }
        if (!MyspotiRuntime.isStarted) return "error: the app's runtime is not started (open the app once)"
        log("command $cmd ${arg.take(80)}")
        scope.launch {
            runCatching {
                when (cmd) {
                    "native-play" -> {
                        require(arg.startsWith("spotify:")) { "native-play needs a spotify: uri" }
                        val e = started(app, intent.getBooleanExtra("l3", false))
                        commandAtMs = System.currentTimeMillis()
                        e.play(arg)
                    }
                    "native-pause" -> engine?.pause()
                    "native-resume" -> engine?.resume()
                    "native-seek" -> engine?.seekTo(arg.toLongOrNull() ?: error("native-seek needs a position in ms"))
                    "native-next" -> engine?.let { e ->
                        commandAtMs = System.currentTimeMillis()
                        if (arg.startsWith("spotify:")) e.play(arg) else e.skipNext()
                    }
                    "native-prev" -> engine?.previous()
                    "native-transfer" -> started(app, intent.getBooleanExtra("l3", false)).transfer("restore")
                    "native-stop" -> stop()
                    else -> log("unknown command $cmd")
                }
            }.onFailure { log("$cmd failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
        return "sent $cmd"
    }

    private fun status(): String {
        val e = engine
        return "engine=${if (e == null) "stopped" else "device ${e.deviceId.take(8)}… on ${e.spclient.substringAfter("://")}"} " +
            "widevine=${WidevineInfo.summary} http=${http?.status ?: "-"} last=$lastEvent"
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun started(context: Context, forceL3: Boolean): NativeEngine = lock.withLock {
        engine?.let { return it }
        val spotify = MyspotiRuntime.spotify
        val chromium = ChromiumHttp(context).also { http = it }
        val logged = LoggedHttp(chromium)
        val p = Media3DrmPlayer(
            context,
            chromium,
            log = ::log,
            forceL3 = forceL3,
            audioProcessors = { arrayOf(EqualizerProcessor(), SpatialProcessor(), SoundCheckProcessor()) },
            wrapAudioSink = { sink, processors ->
                val eq = processors.filterIsInstance<EqualizerProcessor>().firstOrNull()
                if (eq != null) BeatSyncSink(sink, eq.syncClock) else sink
            },
        ).also { player = it }
        com.music.spotui.rust.OrpheonRustAudio.versionOrNull()?.let { log("rust engine active: $it") }
        val config = NativeTokens.config(spotify.http)
        val e = NativeEngine(logged, NativeTokens(spotify.http), OkHttpWebSockets(MyspotiRuntime.apiHttp), Observed(p), AndroidEnvironment, config, scope)
            .also {
                it.crossfadeSeconds = crossfadeSeconds
                it.autoplayEnabled = com.music.spotui.data.preferences.AutoplayPref.on
            }
        // A pause the player made itself (focus lost, headphones out) becomes a pause command, so
        // the account's state follows and the engine reports it when the push comes back.
        p.onPlayerPause = { why -> log("player paused itself: $why"); scope.launch { runCatching { e.pause() } } }
        scope.launch {
            e.events.collect { ev ->
                lastEvent = ev.toString().take(120)
                log("event $ev")
                if (ev is EngineEvent.ContextEnded) {
                    val last = lastLocalPlay
                    scope.launch { runCatching { onContextEnded?.invoke(last) } }
                }
                if (ev is EngineEvent.TrackLoading) {
                    val snap = e.adRecoverySnapshot()
                    val ctx = snap.contextUri
                    val prev = lastLocalPlay
                    val knownIdx = if (ctx != null && ctx == prev?.contextUri && prev.playlistTracks != null && prev.index != null) {
                        val prevIdx = prev.index
                        (prevIdx + 1 until prev.playlistTracks.size).firstOrNull { prev.playlistTracks[it] == ev.uri }
                            ?: prev.playlistTracks.indexOfFirst { it == ev.uri }.takeIf { it >= 0 }
                    } else {
                        null
                    }
                    if (ctx != null || ev.uri.isNotBlank()) {
                        lastLocalPlay = AdRecoveryTarget(
                            contextUri = ctx ?: prev?.contextUri,
                            trackUri = ev.uri,
                            index = knownIdx,
                            order = prev?.order?.takeIf { ctx == null || ctx == prev.contextUri },
                            shuffle = prev?.shuffle,
                            playlistTracks = prev?.playlistTracks?.takeIf { ctx == null || ctx == prev.contextUri },
                        )
                    }
                }
                if (ev is EngineEvent.Failure || ev is EngineEvent.Unauthorized) log("attention: $ev")
                // Fatal Widevine refusals or repeated consecutive player failures hand playback back to the page;
                // transient 429s or single IO glitches during rapid skips must not tear down the engine for 15 min.
                if (ev is EngineEvent.Failure) {
                    val rateLimited = "429" in ev.message || "RateLimited" in ev.message
                    when {
                        ev.message.startsWith("ad not passed:") || ev.message.startsWith("ad blocked") -> {
                            scope.launch { recoverFromAd(e, ev.message) }
                        }
                        ev.message.startsWith("player:") -> {
                            val unrecoverableDrm = !rateLimited &&
                                ("ERROR_CODE_DRM_SCHEME_UNSUPPORTED" in ev.message || "ERROR_CODE_DRM_PROVISIONING_FAILED" in ev.message)
                            val recoverableDrm = !rateLimited && "ERROR_CODE_DRM" in ev.message && !unrecoverableDrm
                            val repeated = if (rateLimited) {
                                ++consecutiveRateLimitFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE
                            } else {
                                ++consecutivePlayerFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE
                            }
                            if (unrecoverableDrm || repeated) {
                                setAside(ev.message)
                            } else if (recoverableDrm) {
                                dropped(e, "recycling player after DRM error: ${ev.message}")
                            }
                        }
                        ev.message.startsWith("dealer:") || ev.message.startsWith("player event:") -> {
                            val repeated = if (rateLimited) {
                                ++consecutiveRateLimitFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE
                            } else {
                                ++consecutivePlayerFailures >= MAX_PLAYER_FAILURES_BEFORE_ASIDE
                            }
                            if (repeated) setAside(ev.message)
                        }
                    }
                }
                // The dealer closed: no command reaches the engine any more; the next use starts it again.
                if (ev is EngineEvent.Disconnected) dropped(e)
            }
        }
        log("starting: widevine ${WidevineInfo.summary}, ${config.deviceName}, app ${config.appVersion}")
        e.start()
        log("registered ${e.deviceId.take(8)}… on ${e.spclient.substringAfter("://")}, http ${chromium.status}")
        engine = e
        _current.value = e
        scope.launch { runCatching { e.warmLicences() } }
        e
    }

    private fun dropped(e: NativeEngine, reason: String = "the dealer closed: the engine leaves the account until its next use") {
        scope.launch {
            var shouldRewarm = false
            lock.withLock {
                if (engine !== e) return@withLock
                log(reason)
                runCatching { e.close() }
                player?.release()
                engine = null; player = null; _current.value = null
                shouldRewarm = on && AppForeground.inForeground && !AccountSync.watchActive
            }
            if (shouldRewarm) {
                delay(1_500L)
                if (on && AppForeground.inForeground && !AccountSync.watchActive && engine == null) {
                    runCatching { warmUp() }
                }
            }
        }
    }

    private suspend fun stop() = lock.withLock {
        engine?.let { runCatching { it.close() }.onFailure { t -> log("close failed: ${t.message}") } }
        player?.release()
        engine = null
        player = null
        _current.value = null
        consecutivePlayerFailures = 0
        consecutiveRateLimitFailures = 0
        log("stopped")
    }

    /** The player as the engine sees it, plus the time from the command to the first audio. */
    private class Observed(private val inner: DrmPlayer) : DrmPlayer by inner {
        override fun setListener(listener: (PlayerEvent) -> Unit) = inner.setListener { ev ->
            if (ev is PlayerEvent.Playing) {
                consecutivePlayerFailures = 0
                consecutiveRateLimitFailures = 0
                if (commandAtMs > 0) {
                    log("time to first audio ${System.currentTimeMillis() - commandAtMs} ms from the command")
                    commandAtMs = 0
                }
            }
            if (ev is PlayerEvent.Error || ev is PlayerEvent.Ended || ev is PlayerEvent.Loaded || ev is PlayerEvent.Seeked) log("player $ev")
            listener(ev)
        }
    }

    /** The engine's HTTP with one line per call: what was reported, never a token or a query string. */
    private class LoggedHttp(private val inner: Http) : Http {
        override suspend fun request(request: HttpRequest): HttpResponse {
            app.myspoti.host.android.PlaybackService.noteNetworkLoad()
            val t0 = System.currentTimeMillis()
            val what = describe(request)
            return try {
                inner.request(request).also { r ->
                    app.myspoti.host.android.PlaybackService.noteNetworkLoad()
                    log("${request.method} $what -> ${r.status} ${r.body.size} B in ${System.currentTimeMillis() - t0} ms")
                    // What Spotify says of a request it refuses (an error text, never a token).
                    if (r.status in 400..499 && r.status != 401) log("refused ${r.status}: ${r.text().take(300)}")
                }
            } catch (t: Throwable) {
                log("${request.method} $what failed after ${System.currentTimeMillis() - t0} ms: ${t.message}")
                throw t
            }
        }

        private fun describe(r: HttpRequest): String {
            val path = Logbook.noQuery(r.url).substringAfter("://")
            val body = r.body ?: return path
            if ("/audio/license" in path) return "licence challenge ${body.size} B"
            val text = body.decodeToString()
            return runCatching {
                when {
                    path.endsWith("/state") -> Json.parseToJsonElement(text).jsonObject.let { o ->
                        val sub = o["sub_state"] as? kotlinx.serialization.json.JsonObject
                        "state ${o["debug_source"]?.jsonPrimitive?.content} seq=${o["seq_num"]} pos=${sub?.get("position")} prev=${o["previous_position"]}"
                    }
                    "/melody/v1/msg/batch" in path -> {
                        val messages = Json.parseToJsonElement(text).jsonObject["messages"] as? kotlinx.serialization.json.JsonArray
                        "melody " + (messages?.joinToString(",") { (it as? kotlinx.serialization.json.JsonObject)?.get("type")?.jsonPrimitive?.content.orEmpty() } ?: "")
                    }
                    else -> path
                }
            }.getOrDefault(path)
        }
    }
}
