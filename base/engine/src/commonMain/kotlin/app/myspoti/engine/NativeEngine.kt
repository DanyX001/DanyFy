// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

/** What the engine tells its caller (UI, media session, logs). */
sealed class EngineEvent {
    data class Registered(val deviceId: String) : EngineEvent()
    data class TrackLoading(val uri: String, val stateId: String) : EngineEvent()
    data class StateReported(val debugSource: String, val position: Long?) : EngineEvent()
    data class Preloaded(val uri: String) : EngineEvent()
    /** A server-inserted ad is current. The engine fetches and reports nothing for it (ADS.md). */
    data class AdBreak(val uri: String, val durationMs: Long) : EngineEvent()
    object ContextEnded : EngineEvent() {
        override fun toString(): String = "ContextEnded"
    }
    data class TokenRefreshed(val url: String) : EngineEvent()
    data class Unauthorized(val url: String) : EngineEvent()
    data class RateLimited(val url: String, val retryAfterMs: Long, val attempt: Int) : EngineEvent()
    data class Failure(val message: String) : EngineEvent()
    /** The dealer WebSocket closed: no command reaches this device until it is started again. */
    object Disconnected : EngineEvent() {
        override fun toString(): String = "Disconnected"
    }
}

/**
 * The native playback engine: the Web Player's device and play reporting, without its page.
 *
 * Model (NATIVE-PLAYBACK.md): every user action is a Connect command sent to the server from
 * this device to itself; the server answers over the dealer WebSocket with a track-playback
 * `replace_state` (a state machine and the state to be in); the engine makes the [DrmPlayer] do
 * it, and when the player reports that it did, the engine sends the state PUT and melody messages
 * the Web Player sends at that moment. Natural track ends follow the state machine's `advance`
 * transition locally, as the Web Player does (no command, no replace_state in the capture).
 *
 * All inputs (dealer frames, player events) mutate state under [stateLock] (no I/O) and serialize
 * HTTP reporting under [reportLock].
 */
class NativeEngine(
    http: Http,
    private val tokens: TokenSource,
    private val sockets: WebSockets,
    private val player: DrmPlayer,
    private val env: Environment,
    private val config: EngineConfig = EngineConfig(),
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events

    /** Compact metadata of an adjacent (`next` / `previous`) state-machine track. */
    data class TrackSummary(
        val uri: String,
        val name: String,
        val artists: String,
        val album: String,
        val imageUrl: String?,
        val durationMs: Long,
        val authors: List<Pair<String, String>> = emptyList(),
        val albumUri: String? = null,
        val provider: String? = null,
    )

    /**
     * The song the engine plays, as the account's state machine names it, and where it is: for a
     * screen with no Web Player page to read the state from (a watch). Null with nothing loaded.
     */
    data class Now(
        val uri: String, val name: String, val artists: String, val album: String, val imageUrl: String?,
        val durationMs: Long, val playing: Boolean,
        /** The position at [atMs] (this clock); it advances from there while [playing]. */
        val positionMs: Long, val atMs: Long,
        /** The list the song plays in, as the state machine says. */
        val contextUri: String? = null,
        /** Waiting for audio (loading, a seek, a stall): [playing] is false meanwhile. */
        val buffering: Boolean = false,
        /** The song's artists, (uri, name) each, as the state machine names them; [artists] joins their names. */
        val authors: List<Pair<String, String>> = emptyList(),
        /** The album's uri, when the state machine gives it. */
        val albumUri: String? = null,
        /** State-machine provider (`"context"`, `"queue"`, `"autoplay"`, ...). */
        val provider: String? = null,
        /** Next non-ad track in the engine's current state machine, when known. */
        val next: TrackSummary? = null,
        /** Previous non-ad track in the engine's current state machine, when known. */
        val previous: TrackSummary? = null,
        /** Ordered lookahead of upcoming non-ad tracks in the engine's current state machine. */
        val upcoming: List<TrackSummary> = emptyList(),
    )

    /** Snapshot captured around a server-inserted ad break or non-ad track transition. */
    data class AdRecoveryHint(
        val contextUri: String?,
        val trackUri: String?,
        val nextTrackUri: String?,
        val nextProvider: String?,
    )

    private val _now = kotlinx.coroutines.flow.MutableStateFlow<Now?>(null)
    val now: kotlinx.coroutines.flow.StateFlow<Now?> = _now

    private fun summaryOf(t: AdvanceTarget): TrackSummary {
        val m = t.nextTrack.metadata
        val prov = t.nextState.trackProvider ?: t.nextState.endContentInfo?.provider
        return TrackSummary(
            uri = m.uri,
            name = m.name.orEmpty(),
            artists = m.authors.mapNotNull { it.name }.joinToString(", "),
            album = m.groupName.orEmpty(),
            imageUrl = m.images.filter { it.url != null }.minByOrNull { kotlin.math.abs((it.width ?: 0) - 300) }?.url,
            durationMs = m.duration,
            authors = m.authors.mapNotNull { a -> a.name?.let { (a.uri ?: "") to it } },
            albumUri = m.groupUri,
            provider = prov,
        )
    }

    private fun upcomingSummariesLocked(p: Playback): List<TrackSummary> {
        val sm = machine ?: return emptyList()
        val startIdx = stateIndex.takeIf { it in sm.states.indices && sm.states[it].id == p.stateId }
            ?: sm.states.indexOfFirst { it.id == p.stateId }.takeIf { it >= 0 }
            ?: return emptyList()
        val out = ArrayList<TrackSummary>()
        val seenUris = HashSet<String>()
        seenUris += p.track.metadata.uri
        val visitedStates = HashSet<Int>()
        var cursor = startIdx
        while (cursor in sm.states.indices && visitedStates.add(cursor) && out.size < 30) {
            val st = sm.states[cursor]
            val target = resolveTargetLocked(sm, st.transitions.advance)
                ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) }
                ?: resolveTargetLocked(sm, st.transitions.skipNext)
                    ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) }
                ?: resolveTargetLocked(sm, st.transitions.showNext)
                    ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) }
            if (target != null) {
                val uri = target.nextTrack.metadata.uri
                if (uri.isNotBlank() && !uri.startsWith("spotify:ad:") && seenUris.add(uri)) {
                    out += summaryOf(target)
                }
                cursor = target.next.stateIndex ?: break
            } else {
                val rawNext = st.transitions.advance?.stateIndex
                    ?: st.transitions.skipNext?.stateIndex
                    ?: st.transitions.showNext?.stateIndex
                    ?: break
                cursor = rawNext
            }
        }
        return out
    }

    private fun adjacentSummariesLocked(p: Playback): Triple<TrackSummary?, TrackSummary?, List<TrackSummary>> {
        val sm = machine ?: return Triple(null, null, emptyList())
        val idx = stateIndex.takeIf { it in sm.states.indices && sm.states[it].id == p.stateId }
            ?: sm.states.indexOfFirst { it.id == p.stateId }.takeIf { it >= 0 }
            ?: return Triple(null, null, emptyList())
        val st = sm.states[idx]
        val upcoming = upcomingSummariesLocked(p)
        val nextTarget = findAdvanceTargetLocked(p, allowAdFallback = false)
            ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) && it.nextTrack.metadata.uri != p.track.metadata.uri }
            ?: resolveTargetLocked(sm, st.transitions.showNext)
                ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) && it.nextTrack.metadata.uri != p.track.metadata.uri }
        val prevTarget = resolveTargetLocked(sm, st.transitions.skipPrev)
            ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) && it.nextTrack.metadata.uri != p.track.metadata.uri }
        val nextSummary = nextTarget?.let(::summaryOf) ?: upcoming.firstOrNull()
        val fullUpcoming = if (nextSummary != null && upcoming.firstOrNull()?.uri != nextSummary.uri) {
            listOf(nextSummary) + upcoming.filter { it.uri != nextSummary.uri }
        } else {
            upcoming
        }
        return Triple(nextSummary, prevTarget?.let(::summaryOf), fullUpcoming)
    }

    private fun publishNow(p: Playback?, positionMs: Long, playing: Boolean, buffering: Boolean = false) {
        val m = p?.track?.metadata
        if (m == null) {
            _now.value = null
            return
        }
        val (nextSummary, prevSummary, upcoming) = adjacentSummariesLocked(p)
        val resolvedCtx = m.contextUri?.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }
            ?: lastNonAdContextUri
        _now.value = Now(
            m.uri, m.name.orEmpty(), m.authors.mapNotNull { it.name }.joinToString(", "), m.groupName.orEmpty(),
            m.images.filter { it.url != null }.minByOrNull { kotlin.math.abs((it.width ?: 0) - 300) }?.url,
            m.duration, playing, positionMs, env.nowMs(), resolvedCtx, buffering,
            m.authors.mapNotNull { a -> a.name?.let { (a.uri ?: "") to it } }, m.groupUri, p.provider,
            next = nextSummary,
            previous = prevSummary,
            upcoming = upcoming,
        )
    }

    private val _playsElsewhere = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Another device took the account's playback (an empty `replace_state`), until a state comes here again. */
    val playsElsewhere: kotlinx.coroutines.flow.StateFlow<Boolean> = _playsElsewhere

    private val _cluster = kotlinx.coroutines.flow.MutableStateFlow<Cluster?>(null)
    /** The account's devices and the active one's playback, as connect-state last told this device; null before. */
    val cluster: kotlinx.coroutines.flow.StateFlow<Cluster?> = _cluster

    @Volatile private var clockOffsetMs: Long = 0L
    @Volatile private var hasHttpClockSync: Boolean = false
    @Volatile private var hasDealerClockSample: Boolean = false

    /** Measures `localEpochMs - serverEpochMs` from an HTTP round-trip response carrying server time. */
    private fun updateClockOffsetFromHttp(sentMs: Long, receivedMs: Long, serverTsMs: Long) {
        if (serverTsMs <= 0L) return
        val rtt = receivedMs - sentMs
        if (rtt !in 0L..MAX_CLOCK_RTT_MS) return
        val sample = ((sentMs + receivedMs) / 2L) - serverTsMs
        if (kotlin.math.abs(sample) > MAX_PLAUSIBLE_CLOCK_SKEW_MS) return
        clockOffsetMs = sample
        hasHttpClockSync = true
    }

    /** Refines `localEpochMs - serverEpochMs` when a live Dealer push arrives or device NTP shifts. */
    private fun updateClockOffsetFromDealer(receivedMs: Long, serverTsMs: Long) {
        if (serverTsMs <= 0L) return
        val sample = receivedMs - serverTsMs
        if (kotlin.math.abs(sample) > MAX_PLAUSIBLE_CLOCK_SKEW_MS) return
        if (!hasHttpClockSync) {
            if (!hasDealerClockSample || sample < clockOffsetMs || sample - clockOffsetMs > CLOCK_RESYNC_GAP_MS) {
                clockOffsetMs = sample
                hasDealerClockSample = true
            }
            return
        }
        if (kotlin.math.abs(sample - clockOffsetMs) > CLOCK_RESYNC_GAP_MS || sample < clockOffsetMs) {
            clockOffsetMs = sample
        }
    }

    /**
     * The smallest file the account may play instead of the best one (a listen mirror, never heard).
     * Read at each load; the state reports name the file actually played.
     */
    var lowestQuality = false

    /** Silences the player without changing the volume the account sees (and, on Android, without the audio focus). */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) player.cancelCrossfade()
        player.setMuted(muted)
    }

    /** The player is silenced ([setMuted]): what it plays is a listen mirror, never heard here. */
    @Volatile var muted = false
        private set

    /**
     * Crossfade duration in seconds between consecutive tracks (0 = off, 1..[MAX_CROSSFADE_SECONDS]).
     * Turning off clears any in-progress overlap or fade-out at once.
     */
    @Volatile var crossfadeSeconds: Int = 0
        set(value) {
            val v = value.coerceIn(0, MAX_CROSSFADE_SECONDS)
            if (field == v) return
            field = v
            if (v == 0) player.cancelCrossfade()
        }

    /** Whether recommendation autoplay after a list context is enabled; false skips early crossfade into autoplay tracks. */
    @Volatile var autoplayEnabled: Boolean = true

    /** Pre-fetches the Widevine service certificate and licence endpoint URL off the critical path of the first track load. */
    suspend fun warmLicences() {
        runCatchingNonCancel { licences.certificate() }
        if (spclient.isNotBlank()) {
            runCatchingNonCancel { licences.licenceUrl() }
        }
    }

    private val sp = SpClient(http, tokens, config) { emit(it) }
    private val resolver = Resolver(sp, config, env)
    private val licences = Licences(sp, env, config.sdkVersion)
    private val timeline = Timeline(env)
    /** Fast, zero-I/O lock for playback/state-machine fields and timeline memory updates. */
    private val stateLock = Mutex()
    /** Microseconds-only lock for [pendingCommands]. */
    private val commandLock = Mutex()
    /** Serializes HTTP state/melody reporting so reports stay ordered without blocking [stateLock] or [commandLock]. */
    private val reportLock = Mutex()
    private val playerInputs = Channel<PlayerEvent>(Channel.UNLIMITED)
    private val reportQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var activeEventHttpJob: Job? = null
    @Volatile private var pendingLoadReport: CompletableDeferred<Unit>? = null
    private var trackGeneration = 0L

    var deviceId: String = ""
        private set
    var spclient: String = ""
        private set
    private var connectionId = ""
    private var seq = 1
    private var formatFilter = 0L
    private var sessionId = ""
    private var volume = config.initialVolume
    private var socket: WebSocketConnection? = null
    private val jobs = ArrayList<Job>()

    private var machine: StateMachine? = null
    private var stateIndex = -1
    private var current: Playback? = null
    private var flushOnFirstPlay = false
    private val pendingCommands = ArrayDeque<SentCommand>()
    private var dealerJob: Job? = null
    @Volatile private var latestReplaceSeq = 0L
    @Volatile private var activeReplaceStateId: String? = null
    @Volatile private var pendingPaused: Pair<String, Boolean>? = null
    @Volatile private var pendingSeek: Pair<String, Long>? = null
    private var lastFinalizedPosition: Long? = null
    private var consecutiveAdBreaks = 0
    @Volatile private var lastNonAdContextUri: String? = null
    @Volatile private var lastNonAdTrackUri: String? = null
    @Volatile private var adNextHint: String? = null
    @Volatile private var adNextProvider: String? = null

    /** Context and next track URI captured before entering a server-inserted ad break. */
    fun adRecoveryHint(): Pair<String?, String?> = lastNonAdContextUri to adNextHint

    /** Full non-ad context/track and next-track/provider snapshot for ad recovery. */
    fun adRecoverySnapshot(): AdRecoveryHint =
        AdRecoveryHint(lastNonAdContextUri, lastNonAdTrackUri, adNextHint, adNextProvider)

    private class SentCommand(val type: String, val id: String, val sentAt: Long, val latency: Long, val status: Int)

    private fun emit(e: EngineEvent) { _events.tryEmit(e) }

    // ---------------------------------------------------------------- start / stop

    /**
     * apresolve -> dealer (connection id) -> `POST track-playback/v1/devices` ->
     * `PUT connect-state/v1/devices/hobs_*`: the order measured on every page load.
     */
    suspend fun start() {
        val ap = EngineJson.decodeFromString(ApResolveResponse.serializer(), sp.send("GET", EngineConfig.APRESOLVE, HeaderProfile.NONE).text())
        val dealer = ap.dealer.firstOrNull()?.substringBefore(':') ?: throw EngineException.Protocol("apresolve: no dealer")
        spclient = "https://" + (ap.spclient.firstOrNull()?.substringBefore(':') ?: throw EngineException.Protocol("apresolve: no spclient"))
        resolver.spclient = spclient
        licences.spclient = spclient
        deviceId = env.randomHex(40)
        sessionId = env.nowMs().toString()

        val ws = sockets.connect("wss://$dealer/?access_token=${tokens.accessToken().value}", dealerHeaders())
        socket = ws
        connectionId = awaitConnectionId(ws)
        player.setListener { playerInputs.trySend(it) }
        jobs += scope.launch {
            for (e in playerInputs) {
                val action = runCatchingNonCancel {
                    stateLock.withLock { applyPlayerEventStateLocked(e) }
                }.onFailure { err ->
                    reportPlayerEventFailure(err)
                }.getOrNull()
                if (action != null && action.hasWork()) {
                    reportQueue.trySend {
                        runEventReportCancellably(action)
                    }
                }
            }
        }
        jobs += scope.launch {
            for (task in reportQueue) {
                runCatchingNonCancel { task() }.onFailure { err ->
                    reportPlayerEventFailure(err)
                }
            }
        }
        jobs += scope.launch { readDealer(ws) }
        jobs += scope.launch { while (true) { delay(30_000); runCatching { ws.send("""{"type":"ping"}""") } } }

        val correlation = tokens.clientToken().deviceId
        val reg = sp.send(
            "POST", "$spclient/track-playback/v1/devices", HeaderProfile.PLAYER,
            body = Bodies.register(config, deviceId, connectionId, correlation).toString().encodeToByteArray(),
            contentType = "application/json",
        )
        val r = EngineJson.decodeFromString(RegisterResponse.serializer(), reg.text())
        seq = r.initialSeqNum
        formatFilter = r.fileFormatFilter
        val hobsT0 = env.nowMs()
        val hobs = sp.send(
            "PUT", "$spclient/connect-state/v1/devices/hobs_${deviceId.take(35)}", HeaderProfile.PLAYER,
            body = Bodies.hobs().toString().encodeToByteArray(),
            accept = "application/json", contentType = "application/json",
            extra = mapOf("x-spotify-connection-id" to connectionId),
        )
        val hobsT1 = env.nowMs()
        // Its answer is the account's cluster as it stands: the devices before any update comes.
        runCatching { (EngineJson.parseToJsonElement(hobs.text()) as? JsonObject)?.let(Cluster::parse) }.getOrNull()?.let { parsed ->
            updateClockOffsetFromHttp(hobsT0, hobsT1, parsed.clusterTimestampMs)
            _cluster.value = parsed.withClockOffset(clockOffsetMs)
        }
        emit(EngineEvent.Registered(deviceId))
    }

    /** Leaves like a closing tab: finalizes the current track, then the two DELETEs of SESSION.md. */
    suspend fun close() {
        dealerJob?.cancel()
        activeEventHttpJob?.cancel()
        reportQueue.close()
        jobs.forEach { it.cancel() }
        val outgoing = stateLock.withLock {
            trackGeneration++
            current.also {
                current = null
                publishNow(null, 0, false)
            }
        }
        reportLock.withLock {
            // A report the server refuses must not keep the device registered and the socket open.
            outgoing?.let {
                if (it.started && it.msPlayed > 0L) {
                    runCatchingNonCancel { finalize(it, "endplay", null) }
                }
                player.stop()
            }
            runCatchingNonCancel { flushTimeline() }
            runCatchingNonCancel {
                val delSeq = stateLock.withLock { ++seq }
                sp.send(
                    "DELETE", "$spclient/track-playback/v1/devices/$deviceId", HeaderProfile.PLAYER,
                    body = buildJsonObject {
                        put("seq_num", delSeq); put("state_ref", kotlinx.serialization.json.JsonNull)
                        put("sub_state", buildJsonObject {}); put("previous_position", 0); put("debug_source", "deregister")
                    }.toString().encodeToByteArray(),
                    contentType = "application/json",
                )
                sp.send("DELETE", "$spclient/connect-state/v1/devices/hobs_${deviceId.take(35)}", HeaderProfile.PLAYER)
            }
            socket?.close()
        }
    }

    private fun dealerHeaders() = mapOf(
        "user-agent" to config.userAgent,
        "origin" to EngineConfig.ORIGIN,
        "accept-language" to config.acceptLanguage,
    ) + config.clientHints

    /** The first dealer frame names the connection: `hm://pusher/v1/connections/...` with `Spotify-Connection-Id`. */
    private suspend fun awaitConnectionId(ws: WebSocketConnection): String {
        while (true) {
            val text = ws.receive() ?: throw EngineException.Protocol("dealer closed before its connection id")
            val f = runCatchingNonCancel { EngineJson.decodeFromString(DealerFrame.serializer(), text) }.getOrNull() ?: continue
            val id = f.headers?.entries?.firstOrNull { it.key.equals("spotify-connection-id", true) }?.value
            if (id != null) return id
        }
    }

    private suspend fun readDealer(ws: WebSocketConnection) {
        while (true) {
            val text = ws.receive() ?: break
            val f = runCatchingNonCancel { EngineJson.decodeFromString(DealerFrame.serializer(), text) }
                .onFailure { emit(EngineEvent.Failure("dealer: ${it.message}")) }
                .getOrNull() ?: continue
            if (applyClusterFrame(f)) continue
            if (f.type != "message" || f.uri != "hm://track-playback/v1/command") continue
            val cmds = runCatchingNonCancel {
                f.payloads.orEmpty().map { EngineJson.decodeFromJsonElement(TrackPlaybackCommand.serializer(), it.jsonObject) }
            }.onFailure { emit(EngineEvent.Failure("dealer: ${it.message}")) }.getOrNull() ?: continue

            val hasReplace = cmds.any { it.type == "replace_state" }
            if (hasReplace) {
                cmds.forEach { cmd ->
                    if (cmd.type == "replace_state") {
                        val sid = cmd.stateRef?.stateIndex?.let { cmd.stateMachine?.states?.getOrNull(it)?.id }
                        val sTo = cmd.seekTo?.toLong()
                        if (sid != null && sTo != null) pendingSeek = sid to sTo
                    }
                }
                val singleReplace = cmds.singleOrNull()?.takeIf { it.type == "replace_state" }
                val targetStateId = singleReplace?.stateRef?.stateIndex?.let { singleReplace.stateMachine?.states?.getOrNull(it)?.id }
                val activeJob = dealerJob
                if (targetStateId != null && targetStateId == activeReplaceStateId && activeJob?.isActive == true) {
                    val targetPaused = singleReplace.stateRef!!.paused
                    pendingPaused = targetStateId to targetPaused
                    val sm = singleReplace.stateMachine!!
                    val idx = singleReplace.stateRef.stateIndex!!
                    val sTo = singleReplace.seekTo?.toLong()
                    scope.launch {
                        runCatchingNonCancel {
                            val ackCmd = commandLock.withLock { pendingCommands.removeFirstOrNull() }
                            ackCommand(ackCmd, tolerateFailure = true)
                            var sameTrackCur: Playback? = null
                            var sameTrackActed = false
                            var sameTrackGen = 0L
                            stateLock.withLock {
                                val cur = current
                                if (cur != null && cur.stateId == targetStateId) {
                                    sameTrackGen = trackGeneration
                                    sameTrackCur = cur
                                    sameTrackActed = applySameTrackUpdateLocked(cur, sm, idx, targetPaused, sTo)
                                }
                            }
                            sameTrackCur?.let { cur ->
                                if (!sameTrackActed) {
                                    reportLock.withLock {
                                        if (stateLock.withLock { current === cur && trackGeneration == sameTrackGen }) {
                                            putState(cur, "modify_current_state", cur.lastPosition, cur.lastPosition, expectedGen = sameTrackGen)
                                        }
                                    }
                                }
                            }
                        }.onFailure { emit(EngineEvent.Failure("dealer: ${it.message}")) }
                    }
                } else {
                    val mySeq = ++latestReplaceSeq
                    activeReplaceStateId = targetStateId
                    pendingPaused = null
                    val prev = activeJob
                    // Cancel superseded replace_state HTTP/resolve work immediately so the reader never
                    // stalls behind a previous skip or a 429 retry delay.
                    prev?.cancel()
                    dealerJob = scope.launch {
                        prev?.join()
                        if (mySeq != latestReplaceSeq) return@launch
                        try {
                            runCatchingNonCancel { handleCommands(cmds, mySeq) }
                                .onFailure { emit(EngineEvent.Failure("dealer: ${it.message}")) }
                        } finally {
                            if (mySeq == latestReplaceSeq) activeReplaceStateId = null
                        }
                    }
                }
            } else {
                val prev = dealerJob
                dealerJob = scope.launch {
                    prev?.join()
                    runCatchingNonCancel { handleCommands(cmds, mySeq = null) }
                        .onFailure { emit(EngineEvent.Failure("dealer: ${it.message}")) }
                }
            }
        }
        emit(EngineEvent.Disconnected)
    }

    // ---------------------------------------------------------------- user commands

    /** Plays [contextUri] (album, playlist, `spotify:track:...`), optionally from a track of it. */
    suspend fun play(contextUri: String, trackUri: String? = null, trackIndex: Int? = null, feature: String = "harmony", order: List<String>? = null, shuffle: Boolean? = null) {
        stateLock.withLock {
            if (contextUri.isNotBlank() && !contextUri.startsWith("spotify:ad:")) {
                if (contextUri != lastNonAdContextUri) {
                    adNextHint = null
                    adNextProvider = null
                }
                lastNonAdContextUri = contextUri
                lastNonAdTrackUri = trackUri?.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }
            }
        }
        command("play", Bodies.playFields(config, contextUri, trackUri, trackIndex, feature, order, env.nowMs(), shuffle))
    }

    suspend fun pause() = command("pause")
    suspend fun resume() {
        command("resume")
        val resumeLocal = stateLock.withLock {
            val cur = current ?: return@withLock false
            if (cur.loadDispatched && cur.playerPaused && !cur.wantPaused && cur.pending != Playback.Pending.Resume) {
                if (cur.pending !is Playback.Pending.Seek) cur.pending = Playback.Pending.Resume
                true
            } else {
                false
            }
        }
        if (resumeLocal) player.resume()
    }
    suspend fun skipNext() {
        pendingLoadReport?.let { withTimeoutOrNull(2_000L) { it.await() } }
        command("skip_next")
    }

    /**
     * Jumps to [trackUri] in [contextUri]: uses one `play` command when the context is
     * preserved, `skip_next` when one step or a short queued hop away.
     */
    suspend fun skipTo(
        contextUri: String?,
        trackUri: String,
        trackUid: String? = null,
        provider: String? = null,
        order: List<String>? = null,
        shuffle: Boolean? = null,
        steps: Int? = null,
        trackIndex: Int? = null,
    ) {
        if (steps == 1) { skipNext(); return }
        if (contextUri != null && provider != "queue" && provider != "autoplay") {
            play(contextUri, trackUri, trackIndex = trackIndex, order = order, shuffle = shuffle)
            return
        }
        val hops = steps?.takeIf { it in 1..MAX_SKIP_HOPS }
        if (hops != null) {
            repeat(hops) { skipNext() }
            return
        }
        play(contextUri ?: trackUri, trackUri.takeIf { contextUri != null && it != contextUri }, trackIndex = trackIndex, order = order, shuffle = shuffle)
    }

    /** Cancels any in-flight dealer track load when a rapid multi-skip burst has already moved past it. */
    fun abortPendingTrackLoad() {
        latestReplaceSeq++
        activeReplaceStateId = null
        pendingLoadReport?.let { it.complete(Unit); pendingLoadReport = null }
        dealerJob?.cancel()
    }
    /**
     * A seek of the song loaded here. Sent once a load under way is reported (its
     * `before_track_load` answered): the server applies a seek to the state the device last
     * reported, and one sent right after a play, before that report, put the song played before it
     * back (log seen: a mirror's play, its seek 100 ms later, and the account's previous song
     * loaded again at the seek's position, a loop of "the page moved on by itself").
     */
    suspend fun seekTo(positionMs: Long) {
        pendingLoadReport?.let { withTimeoutOrNull(4_000L) { it.await() } }
        command("seek_to", buildJsonObject { put("value", positionMs.toDouble()) })
    }

    /** The engine's clock (the one [Now.atMs] is on). */
    fun clockMs(): Long = env.nowMs()

    /**
     * Waits (at most [timeoutMs]) for a play sent at [sinceMs] ([clockMs]) to be loaded here and
     * reported to the server: [uri] (any song with null) in [now] since then, its load's report
     * answered. True when it was; a seek after it then lands on that song.
     */
    suspend fun awaitLoaded(uri: String?, sinceMs: Long, timeoutMs: Long): Boolean {
        val deadline = env.nowMs() + timeoutMs
        withTimeoutOrNull(timeoutMs) { now.first { it != null && (uri == null || it.uri == uri) && it.atMs >= sinceMs } } ?: return false
        val remaining = (deadline - env.nowMs()).coerceAtLeast(1L)
        pendingLoadReport?.let { withTimeoutOrNull(remaining) { it.await() } }
        return true
    }

    /** The Web Player's previous button: back to 0 past ~3 s, else the previous track (connect capture 2026-10-26). */
    suspend fun previous() {
        val pos = current?.lastPosition ?: 0
        if (pos > 3_000) seekTo(0) else command("skip_prev")
    }

    /** Takes the account's playback onto this device (what a page load with a paused session did). */
    suspend fun transfer(restorePaused: String = "pause") {
        val id = env.randomHex(32)
        val t0 = env.nowMs()
        val r = sp.send(
            "POST", "$spclient/connect-state/v1/connect/transfer/from/$deviceId/to/$deviceId", HeaderProfile.PLAYER,
            body = Bodies.transfer(restorePaused, id).toString().encodeToByteArray(), contentType = "application/json",
        )
        commandLock.withLock { pendingCommands.addLast(SentCommand("transfer", id, t0, env.nowMs() - t0, r.status)) }
    }

    /**
     * Sends a player command (`pause`, `resume`, `seek_to`, `skip_next`, `skip_prev`) directly to
     * another active Connect device ([targetDeviceId], defaulting to [cluster]'s active device)
     * without transferring playback onto this device. Returns true when a remote target accepted
     * the command (HTTP 2xx), false when no remote target is active or the command was refused.
     */
    suspend fun remoteCommand(
        endpoint: String,
        fields: JsonObject = JsonObject(emptyMap()),
        targetDeviceId: String? = cluster.value?.activeDeviceId,
    ): Boolean {
        if (deviceId.isBlank()) return false
        val target = targetDeviceId?.takeIf { it.isNotBlank() && it != deviceId } ?: return false
        val id = env.randomHex(32)
        return sendCommand(endpoint, fields, target, id) in 200..299
    }

    /**
     * Commands another active Connect device to play [contextUri] without transferring playback to
     * this phone.
     */
    suspend fun remotePlay(
        contextUri: String,
        trackUri: String? = null,
        trackIndex: Int? = null,
        feature: String = "harmony",
        order: List<String>? = null,
        shuffle: Boolean? = null,
        targetDeviceId: String? = cluster.value?.activeDeviceId,
    ): Boolean = remoteCommand(
        "play",
        Bodies.playFields(config, contextUri, trackUri, trackIndex, feature, order, env.nowMs(), shuffle),
        targetDeviceId,
    )

    private suspend fun command(endpoint: String, fields: JsonObject = JsonObject(emptyMap())) {
        val id = env.randomHex(32)
        val t0 = env.nowMs()
        val status = sendCommand(endpoint, fields, deviceId, id)
        commandLock.withLock { pendingCommands.addLast(SentCommand(endpoint, id, t0, env.nowMs() - t0, status)) }
    }

    /**
     * Adjusts volume ([level] in `0.0..1.0`) directly on [targetDeviceId] via spclient without
     * transferring playback onto this device. Returns true when Connect accepted the request.
     */
    suspend fun remoteVolume(
        level: Double,
        targetDeviceId: String? = cluster.value?.activeDeviceId,
    ): Boolean {
        val target = targetDeviceId?.takeIf { it.isNotBlank() && it != deviceId } ?: return false
        if (deviceId.isBlank() || spclient.isBlank()) return false
        val v = (level.coerceIn(0.0, 1.0) * 65535).toInt()
        return try {
            sp.send(
                "PUT",
                "$spclient/connect-state/v1/connect/volume/from/$deviceId/to/$target",
                HeaderProfile.PLAYER,
                body = """{"volume":$v}""".encodeToByteArray(),
                contentType = "application/json",
            ).status in 200..299
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun sendCommand(endpoint: String, fields: JsonObject, targetDeviceId: String, commandId: String): Int =
        sp.send(
            "POST", "$spclient/connect-state/v1/player/command/from/$deviceId/to/$targetDeviceId", HeaderProfile.PLAYER,
            body = Bodies.command(endpoint, commandId, fields).toString().encodeToByteArray(), contentType = "application/json",
        ).status

    // ---------------------------------------------------------------- dealer input

    private fun applyClusterFrame(f: DealerFrame): Boolean {
        if (f.type == "message" && f.uri?.startsWith("hm://connect-state/v1/cluster") == true) {
            val receivedMs = env.nowMs()
            decodeClusterPayload(f)?.let(Cluster::parse)?.let { parsed ->
                updateClockOffsetFromDealer(receivedMs, parsed.clusterTimestampMs)
                _cluster.value = parsed.withClockOffset(clockOffsetMs)
            }
            return true
        }
        return false
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeClusterPayload(f: DealerFrame): JsonObject? {
        val first = f.payloads?.firstOrNull() ?: return null
        if (first is JsonObject) return first
        val encoded = (first as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val gzipped = f.headers?.entries?.any { (k, v) ->
            (k.equals("Transfer-Encoding", ignoreCase = true) || k.equals("Content-Encoding", ignoreCase = true)) &&
                "gzip" in v.lowercase()
        } == true
        if (!gzipped) {
            if (!encoded.trimStart().startsWith("{")) return null
            return runCatchingNonCancel { EngineJson.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
        }
        val inflated = runCatchingNonCancel { env.gunzip(Base64.decode(encoded)) }.getOrNull() ?: return null
        return runCatchingNonCancel { EngineJson.parseToJsonElement(inflated.decodeToString()) as? JsonObject }.getOrNull()
    }

    internal suspend fun handleDealerFrame(text: String) {
        val f = EngineJson.decodeFromString(DealerFrame.serializer(), text)
        // The account's cluster changed (a device came, went, took playback, paused): kept as it is now.
        // A payload sent compressed (a string, not an object) is skipped: the next update brings it.
        if (applyClusterFrame(f)) return
        if (f.type != "message" || f.uri != "hm://track-playback/v1/command") return
        val cmds = f.payloads.orEmpty().map { EngineJson.decodeFromJsonElement(TrackPlaybackCommand.serializer(), it.jsonObject) }
        handleCommands(cmds, mySeq = null)
    }

    private suspend fun handleCommands(cmds: List<TrackPlaybackCommand>, mySeq: Long?) {
        for (cmd in cmds) {
            if (mySeq != null && mySeq != latestReplaceSeq && cmd.type == "replace_state") return
            when (cmd.type) {
                "replace_state" -> replaceState(cmd, mySeq)
                "set_volume" -> setVolume(cmd)
            }
        }
    }

    private suspend fun replaceState(cmd: TrackPlaybackCommand, mySeq: Long?) {
        var ackCmd: SentCommand? = null
        var outgoing: Playback? = null
        var stateClear = false
        var sameTrackCur: Playback? = null
        var sameTrackActed = false
        var nextState: SmState? = null
        var nextTrack: SmTrack? = null
        var paused = false
        var startPos = 0L
        var prevPos: Long? = null
        var gen = 0L
        var prevMachine: StateMachine? = null
        var prevStateIndex = -1

        val proceed = stateLock.withLock {
            if (mySeq != null && mySeq != latestReplaceSeq) return@withLock false
            ackCmd = commandLock.withLock { pendingCommands.removeFirstOrNull() }
            val sm = cmd.stateMachine
            val index = cmd.stateRef?.stateIndex
            if (sm == null || sm.states.isEmpty() || index == null || index !in sm.states.indices) {
                // Playback moved to another device: `state_clear` with no state_ref (connect capture).
                outgoing = current
                current = null
                machine = null
                stateIndex = -1
                lastFinalizedPosition = null
                consecutiveAdBreaks = 0
                lastNonAdContextUri = null
                lastNonAdTrackUri = null
                adNextHint = null
                adNextProvider = null
                gen = ++trackGeneration
                _playsElsewhere.value = true
                publishNow(null, 0, false)
                stateClear = true
                return@withLock true
            }
            _playsElsewhere.value = false
            val state = sm.states[index]
            paused = cmd.stateRef.paused
            val seekTo = cmd.seekTo?.toLong()
            val cur = current
            if (cur != null && cur.stateId == state.id) {
                gen = trackGeneration
                sameTrackCur = cur
                sameTrackActed = applySameTrackUpdateLocked(cur, sm, index, paused, seekTo)
                return@withLock true
            }
            val track = sm.tracks.getOrNull(state.track) ?: throw EngineException.Protocol("state ${state.id}: no track ${state.track}")
            // Detach the outgoing song under the lock so a superseded skip does not finalize it twice
            // or stall the lock on HTTP reports; resolve runs outside the lock so it can cancel cleanly.
            outgoing = current
            prevMachine = machine
            prevStateIndex = stateIndex
            prevPos = outgoing?.lastPosition ?: lastFinalizedPosition
            outgoing?.let { lastFinalizedPosition = it.lastPosition }
            current = null
            machine = sm
            stateIndex = index
            gen = ++trackGeneration
            nextState = state
            nextTrack = track
            val rememberedSeek = pendingSeek?.takeIf { it.first == state.id }?.second
            startPos = seekTo ?: rememberedSeek ?: state.initialPlaybackPosition ?: 0L
            if (pendingSeek?.first == state.id) pendingSeek = null
            true
        }
        if (!proceed) return

        // Unblock any stale telemetry HTTP wait (e.g. a 429 started_playing) before taking reportLock.
        if (stateClear || outgoing != null) {
            activeEventHttpJob?.cancel()
        }

        ackCommand(ackCmd, tolerateFailure = mySeq != null)
        if (stateClear) {
            reportLock.withLock {
                outgoing?.let {
                    finalizeSafe(it, "remote", null, keepMachine = false)
                    player.stop()
                }
                if (stateLock.withLock { trackGeneration == gen }) {
                    putState(null, null, false, null, null, "state_clear", expectedGen = gen)
                }
            }
            return
        }
        sameTrackCur?.let { cur ->
            // Options or queue changed around the same track (shuffle, repeat, add to queue).
            if (!sameTrackActed) {
                reportLock.withLock {
                    if (stateLock.withLock { current === cur && trackGeneration == gen }) {
                        putState(cur, "modify_current_state", cur.lastPosition, cur.lastPosition, expectedGen = gen)
                    }
                }
            }
            return
        }

        val state = nextState ?: return
        val track = nextTrack ?: return
        try {
            // The song going out is finalized in the machine it played in, before the new one is taken.
            // A track skipped before it ever started playing has nothing to finalize (Spotify rejects pos=0 with 400).
            outgoing?.takeIf { it.started || it.msPlayed > 0L }?.let { reportLock.withLock { finalizeSafe(it, "remote", state.id, keepMachine = true) } }
            if (mySeq != null && mySeq != latestReplaceSeq) return

            if (isAdTrack(track, state)) {
                stateLock.withLock {
                    if ((mySeq != null && mySeq != latestReplaceSeq) || trackGeneration != gen) return@withLock
                    val hint = findNonAdHintPairForStateLocked(state)
                        ?: prevMachine?.let { pm ->
                            val pState = prevStateIndex.takeIf { it in pm.states.indices }?.let { pm.states[it] }
                                ?: outgoing?.stateId?.let { sid -> pm.states.firstOrNull { it.id == sid } }
                            if (pState != null) findNonAdHintPairInMachineLocked(pm, pState) else null
                        }
                    hint?.let { (hintUri, hintProv) ->
                        if (hintUri != null) {
                            adNextHint = hintUri
                            adNextProvider = hintProv
                        }
                    }
                    handleAdBreak(track)
                }
                return
            }
            val proceedNonAd = stateLock.withLock {
                if ((mySeq != null && mySeq != latestReplaceSeq) || trackGeneration != gen) return@withLock false
                recordNonAdContextLocked(state, track)
                true
            }
            if (!proceedNonAd) return

            emit(EngineEvent.TrackLoading(track.metadata.uri, state.id))
            val file = Resolver.chooseFile(track, formatFilter, lowestQuality) ?: throw EngineException.Protocol("no playable file for ${track.metadata.uri}")
            val t0 = env.nowMs()
            val media = resolver.resolve(track, formatFilter, lowestQuality)
            if (mySeq != null && mySeq != latestReplaceSeq) return

            val latestPause = pendingPaused?.takeIf { it.first == state.id }?.second ?: paused
            val latestSeek = pendingSeek?.takeIf { it.first == state.id }?.second ?: startPos
            if (pendingPaused?.first == state.id) pendingPaused = null
            if (pendingSeek?.first == state.id) pendingSeek = null
            commitLoaded(
                state, track, file, media, latestSeek, latestPause, "remote", prevPos, t0,
                tolerateMelodyFailure = mySeq != null, expectedSeq = mySeq, expectedGen = gen,
            )
        } catch (t: Throwable) {
            var stopPlayer = false
            withContext(NonCancellable) {
                stateLock.withLock {
                    val notSuperseded = mySeq == null || mySeq == latestReplaceSeq
                    if (trackGeneration == gen && current == null && (t !is CancellationException || notSuperseded)) {
                        trackGeneration++
                        publishNow(null, 0, false)
                        stopPlayer = true
                    }
                }
            }
            if (stopPlayer) player.stop()
            throw t
        }
    }

    private fun applySameTrackUpdateLocked(
        cur: Playback,
        sm: StateMachine,
        index: Int,
        paused: Boolean,
        seekTo: Long?,
    ): Boolean {
        machine = sm
        stateIndex = index
        var acted = false
        if (!cur.loadDispatched) {
            if (seekTo != null) {
                cur.pendingSeekMs = seekTo
                cur.lastPosition = seekTo
                acted = true
            }
            if (paused != cur.wantPaused) {
                cur.wantPaused = paused
                acted = true
            }
            if (acted) {
                publishNow(cur, cur.pendingSeekMs ?: cur.lastPosition, false, buffering = !cur.wantPaused)
            }
        } else {
            if (seekTo != null) {
                cur.pending = Playback.Pending.Seek(cur.lastPosition)
                player.seekTo(seekTo)
                acted = true
            }
            if (paused != cur.wantPaused || (!paused && cur.playerPaused) || (paused && cur.playing)) {
                cur.wantPaused = paused
                if (cur.pending !is Playback.Pending.Seek) {
                    cur.pending = if (paused) Playback.Pending.Pause else Playback.Pending.Resume
                }
                if (paused) player.pause() else player.resume()
                acted = true
            }
        }
        return acted
    }

    private fun handleAdBreak(track: SmTrack) {
        // No ad request is made and nothing is reported as played (decision of 2026-10-16).
        trackGeneration++
        current = null
        lastFinalizedPosition = null
        pendingLoadReport?.let { it.complete(Unit); pendingLoadReport = null }
        activeEventHttpJob?.cancel()
        publishNow(null, 0, false)
        player.stop()
        val adCount = ++consecutiveAdBreaks
        emit(EngineEvent.AdBreak(track.metadata.uri, track.metadata.duration))
        if (adCount > 1) {
            emit(EngineEvent.Failure("ad blocked playback: ${track.metadata.uri}"))
            return
        }
        // On to the next song (outside this handler: a command waits for the lock it holds).
        scope.launch { runCatchingNonCancel { command("skip_next") }.onFailure { emit(EngineEvent.Failure("ad not passed: ${it.message}")) } }
    }

    private suspend fun finalizeSafe(p: Playback, reasonEnd: String, nextPlaybackId: String?, keepMachine: Boolean) {
        runCatchingNonCancel { finalize(p, reasonEnd, nextPlaybackId, keepMachine = keepMachine) }
            .onFailure { emit(EngineEvent.Failure("finalize of ${p.track.metadata.uri}: ${it.message}")) }
    }

    private suspend fun setVolume(cmd: TrackPlaybackCommand) {
        val vol = stateLock.withLock {
            val v = (cmd.volume ?: return@withLock null).toInt().coerceIn(0, 65535)
            volume = v
            player.setVolume(v)
            v
        } ?: return
        reportLock.withLock {
            val nextSeq = stateLock.withLock { ++seq }
            sp.send(
                "PUT", "$spclient/track-playback/v1/devices/$deviceId/volume", HeaderProfile.PLAYER,
                body = Bodies.volume(nextSeq, vol, cmd.commandId ?: "").toString().encodeToByteArray(), contentType = "application/json",
            )
        }
    }

    /** `jssdk_connect_command` once the server's push for our command arrived (ack duration = POST to push). */
    private suspend fun ackCommand(c: SentCommand?, tolerateFailure: Boolean) {
        c ?: return
        val msg = Reports.connectCommand(c.type, c.id, env.nowMs() - c.sentAt, c.latency, tokens.accessToken().clientId, deviceId, c.status)
        val batch = listOf(Bodies.melodyMessage("jssdk_connect_command", msg))
        if (tolerateFailure) {
            runCatchingNonCancel { melody(batch, config.harmonyVersion) }
        } else {
            melody(batch, config.harmonyVersion)
        }
    }

    // ---------------------------------------------------------------- loading and finalizing

    /** [track] comes from the same state machine as [state]: a PUT answer in between may renumber tracks. */
    private suspend fun load(
        state: SmState, track: SmTrack, position: Long, paused: Boolean, reasonStart: String, previousPosition: Long?,
        expectedGen: Long? = null,
        crossfadeMs: Long = 0L,
    ) {
        if (expectedGen != null && stateLock.withLock { trackGeneration != expectedGen }) return
        if (isAdTrack(track, state)) {
            stateLock.withLock {
                if (expectedGen == null || trackGeneration == expectedGen) {
                    findNonAdHintPairForStateLocked(state)?.let { (hintUri, hintProv) ->
                        if (hintUri != null) {
                            adNextHint = hintUri
                            adNextProvider = hintProv
                        }
                    }
                    handleAdBreak(track)
                }
            }
            return
        }
        val proceedLoad = stateLock.withLock {
            if (expectedGen != null && trackGeneration != expectedGen) return@withLock false
            recordNonAdContextLocked(state, track)
            true
        }
        if (!proceedLoad) return
        emit(EngineEvent.TrackLoading(track.metadata.uri, state.id))
        try {
            val file = Resolver.chooseFile(track, formatFilter, lowestQuality) ?: throw EngineException.Protocol("no playable file for ${track.metadata.uri}")
            val t0 = env.nowMs()
            val media = resolver.resolve(track, formatFilter, lowestQuality)
            commitLoaded(state, track, file, media, position, paused, reasonStart, previousPosition, t0, tolerateMelodyFailure = false, expectedGen = expectedGen, crossfadeMs = crossfadeMs)
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                var stopPlayer = false
                stateLock.withLock {
                    if ((expectedGen == null || trackGeneration == expectedGen) && current == null) {
                        trackGeneration++
                        publishNow(null, 0, false)
                        stopPlayer = true
                    }
                }
                if (stopPlayer) player.stop()
            }
            throw t
        }
    }

    private suspend fun commitLoaded(
        state: SmState, track: SmTrack, file: ChosenFile, media: PlayableMedia,
        position: Long, paused: Boolean, reasonStart: String, previousPosition: Long?, t0: Long,
        tolerateMelodyFailure: Boolean, expectedSeq: Long? = null, expectedGen: Long? = null,
        crossfadeMs: Long = 0L,
    ) {
        val loadReported = CompletableDeferred<Unit>()
        val provider = state.trackProvider ?: state.endContentInfo?.provider
        val p = Playback(state.id, track, file, media, position, paused, reasonStart, t0, env.nowMs() - t0, provider)
        var loadGen = 0L
        val proceeded = stateLock.withLock {
            if ((expectedSeq != null && expectedSeq != latestReplaceSeq) ||
                (expectedGen != null && expectedGen != trackGeneration)
            ) {
                return@withLock false
            }
            current = p
            consecutiveAdBreaks = 0
            lastFinalizedPosition = null
            pendingLoadReport = loadReported
            loadGen = ++trackGeneration
            timeline.opened(p, volume)
            publishNow(p, position, false, buffering = !paused)
            true
        }
        if (!proceeded) {
            loadReported.complete(Unit)
            return
        }
        player.warmMedia(media, licences.channel())
        var loadDispatched = false
        try {
            reportLock.withLock {
                val stillCurrent = stateLock.withLock {
                    current === p &&
                        (expectedSeq == null || expectedSeq == latestReplaceSeq) &&
                        trackGeneration == loadGen
                }
                if (!stillCurrent) return
                putState(p, "before_track_load", position, previousPosition, speed = 0, expectedGen = loadGen)
                val start = listOf(Bodies.melodyMessage("jssdk_playback_start", Reports.playbackStart(p, sessionId)))
                if (tolerateMelodyFailure) {
                    runCatchingNonCancel { melody(start, "harmony:${config.sdkVersion}") }
                } else {
                    melody(start, "harmony:${config.sdkVersion}")
                }
                var startAt = position
                var playNow = !paused
                val launchLoad = stateLock.withLock {
                    if (current !== p ||
                        (expectedSeq != null && expectedSeq != latestReplaceSeq) ||
                        trackGeneration != loadGen
                    ) {
                        false
                    } else {
                        startAt = p.pendingSeekMs ?: position
                        p.pendingSeekMs = null
                        p.lastPosition = startAt
                        playNow = !p.wantPaused
                        p.playerPaused = p.wantPaused
                        p.pending = null
                        p.loadDispatched = true
                        publishNow(p, startAt, false, buffering = playNow)
                        true
                    }
                }
                if (launchLoad) {
                    loadDispatched = true
                    val liveFadeMs = if (crossfadeMs > 0L && !muted && crossfadeSeconds > 0 && playNow) crossfadeMs else 0L
                    if (liveFadeMs > 0L) {
                        player.loadCrossfade(media, startAt, true, licences.channel(), liveFadeMs)
                    } else {
                        player.load(media, startAt, playNow, licences.channel())
                    }
                }
            }
        } catch (t: Throwable) {
            if (!loadDispatched) {
                var stopPlayer = false
                withContext(NonCancellable) {
                    stateLock.withLock {
                        if (current === p && trackGeneration == loadGen) {
                            current = null
                            trackGeneration++
                            publishNow(null, 0, false)
                            stopPlayer = true
                        }
                    }
                }
                if (stopPlayer) player.stop()
            }
            throw t
        } finally {
            if (pendingLoadReport === loadReported) pendingLoadReport = null
            loadReported.complete(Unit)
        }
    }

    /**
     * End of one track: melody verification + stats, then the `track_data_finalized` PUT (real1 and
     * end-of-track order). [putPosition] is the last position the player reported, 150062 in the
     * PUT while melody said `ms_end_position` 150227 for the same natural end.
     *
     * At a natural end the Web Player sent the next `before_track_load` 10 ms after this PUT with
     * the same `state_machine_id`, i.e. without waiting for its answer, and later reports used the
     * answer to `before_track_load` (end-of-track, 2026-11-10). [keepMachine] does the same.
     */
    private suspend fun finalize(p: Playback, reasonEnd: String, nextPlaybackId: String?, putPosition: Long = p.lastPosition, keepMachine: Boolean = false) {
        val m = player.metrics()
        val now = env.nowMs()
        val gen = stateLock.withLock {
            timeline.position(p, p.lastPosition, if (reasonEnd == "trackdone") 5 else 6)
            trackGeneration
        }
        melody(
            listOf(
                Bodies.melodyMessage("track_stream_verification", Reports.streamVerification(p, sessionId, nextPlaybackId)),
                Bodies.melodyMessage("jssdk_playback_stats", Reports.playbackStats(p, m, sessionId, reasonEnd, licences.lastLicenceLatencyMs, now, config.playbackStrategy)),
            ),
            "harmony:${config.sdkVersion}",
        )
        putState(
            p, "track_data_finalized", putPosition, putPosition,
            stats = Reports.finalizedStats(p, m, licences.lastLicenceLatencyMs, now, config.playbackStrategy),
            duration = p.fileDurationMs, applyAnswer = !keepMachine, expectedGen = gen,
        )
    }

    // ---------------------------------------------------------------- player input

    private class AdvanceTarget(
        val next: StateIndexRef,
        val nextState: SmState,
        val nextTrack: SmTrack,
    )

    private fun crossfadeFadeMsLocked(p: Playback): Long {
        val secs = crossfadeSeconds
        val dur = p.durationMs
        if (secs <= 0 || dur <= 0) return 0L
        return minOf(secs * 1000L, dur / 2L)
    }

    private fun isAdTrack(track: SmTrack, state: SmState? = null): Boolean =
        track.contentType.equals("AD", ignoreCase = true) ||
            track.metadata.uri.startsWith("spotify:ad:") ||
            track.metadata.flaggedAsAd ||
            state?.trackProvider.equals("ad", ignoreCase = true)

    private fun recordNonAdContextLocked(state: SmState, track: SmTrack) {
        val m = track.metadata
        val explicitCtx = m.contextUri?.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }
            ?: state.endContentInfo?.contextUri?.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }
        val trackUri = m.uri.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }
        val candidateCtx = explicitCtx
            ?: lastNonAdContextUri
            ?: m.groupUri?.takeIf { it.isNotBlank() && !it.startsWith("spotify:ad:") }
        if (candidateCtx != null) lastNonAdContextUri = candidateCtx
        if (trackUri != null) lastNonAdTrackUri = trackUri
        val hint = findNonAdHintPairForStateLocked(state)
        adNextHint = hint?.first?.takeIf { it != trackUri }
        adNextProvider = hint?.second
    }

    private fun findNonAdHintPairForStateLocked(state: SmState): Pair<String?, String?>? {
        val sm = machine ?: return null
        return findNonAdHintPairInMachineLocked(sm, state)
    }

    private fun findNonAdHintPairInMachineLocked(sm: StateMachine, state: SmState): Pair<String?, String?>? {
        val curIdx = stateIndex.takeIf { sm === machine && it in sm.states.indices && sm.states[it].id == state.id }
            ?: sm.states.indexOfFirst { it.id == state.id }
        if (curIdx < 0) return null
        fun pairOf(t: AdvanceTarget): Pair<String?, String?> =
            t.nextTrack.metadata.uri to (t.nextState.trackProvider ?: t.nextState.endContentInfo?.provider)
        findNonAdInMachineLocked(sm, curIdx, excludeStateId = state.id)?.let { return pairOf(it) }
        resolveTargetLocked(sm, state.transitions.showNext)
            ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) && it.nextState.id != state.id }
            ?.let { return pairOf(it) }
        val advIdx = state.transitions.advance?.stateIndex?.takeIf { it in sm.states.indices } ?: return null
        return resolveTargetLocked(sm, sm.states[advIdx].transitions.showNext)
            ?.takeIf { !isAdTrack(it.nextTrack, it.nextState) && it.nextState.id != state.id }
            ?.let(::pairOf)
    }

    private fun resolveTargetLocked(sm: StateMachine, ref: StateIndexRef?): AdvanceTarget? {
        val next = ref ?: return null
        val nextState = next.stateIndex?.let { sm.states.getOrNull(it) } ?: return null
        val nextTrack = sm.tracks.getOrNull(nextState.track) ?: return null
        return AdvanceTarget(next, nextState, nextTrack)
    }

    /**
     * Walks real [advance] / [skipNext] edges in [sm] (never display-only `show_next`) up to
     * [MAX_SKIP_HOPS] steps to locate the next playable non-ad track after an ad state.
     */
    private fun findNonAdInMachineLocked(
        sm: StateMachine,
        startIndex: Int,
        excludeStateId: String? = null,
    ): AdvanceTarget? {
        if (startIndex !in sm.states.indices) return null
        val queue = ArrayDeque<StateIndexRef>()
        val visited = HashSet<Int>().apply { add(startIndex) }
        fun enqueue(ref: StateIndexRef?) {
            val idx = ref?.stateIndex ?: return
            if (idx in sm.states.indices && visited.add(idx)) queue.addLast(ref)
        }
        val start = sm.states[startIndex]
        enqueue(start.transitions.advance)
        enqueue(start.transitions.skipNext)
        var hops = 0
        while (queue.isNotEmpty() && hops++ < MAX_SKIP_HOPS) {
            val ref = queue.removeFirst()
            val candidate = resolveTargetLocked(sm, ref) ?: continue
            if (!isAdTrack(candidate.nextTrack, candidate.nextState) && candidate.nextState.id != excludeStateId) {
                return candidate
            }
            enqueue(candidate.nextState.transitions.advance)
            enqueue(candidate.nextState.transitions.skipNext)
        }
        return null
    }

    /**
     * Resolves the next track to advance into at natural track end or preload. When [advance]
     * leads to a server-inserted `AD` and [skipNext] (or a subsequent real transition inside the
     * same state machine) leads directly to a playable track, prefer that track to bypass ads.
     */
    private fun findAdvanceTargetLocked(p: Playback, allowAdFallback: Boolean = true): AdvanceTarget? {
        val sm = machine ?: return null
        val resolvedIdx = stateIndex.takeIf { it in sm.states.indices && sm.states[it].id == p.stateId }
            ?: sm.states.indexOfFirst { it.id == p.stateId }.takeIf { it >= 0 }
            ?: return null
        val state = sm.states[resolvedIdx]
        val adv = resolveTargetLocked(sm, state.transitions.advance)
        if (adv != null && !isAdTrack(adv.nextTrack, adv.nextState)) return adv
        val skip = resolveTargetLocked(sm, state.transitions.skipNext)
        if (skip != null && !isAdTrack(skip.nextTrack, skip.nextState) && skip.nextState.id != state.id) return skip
        findNonAdInMachineLocked(sm, resolvedIdx, excludeStateId = state.id)?.let { return it }
        return if (allowAdFallback) adv else null
    }

    private fun computeResyncPosLocked(
        p: Playback,
        e: PlayerEvent,
        hasOtherStateReport: Boolean,
    ): Long? {
        if (e !is PlayerEvent.Position || hasOtherStateReport) return null
        if (!p.started || !p.startedStateReported || !p.playing || p.wantPaused || p.playerPaused) return null
        if (p.lastReportedWallMs <= 0L || p.lastReportedSpeed <= 0) return null
        val nowWall = env.nowMs()
        val expectedPos = p.lastReportedPosMs + (nowWall - p.lastReportedWallMs).coerceAtLeast(0L)
        val driftMs = kotlin.math.abs(expectedPos - e.positionMs)
        val wasRebuffering = p.pendingRebufferResync
        if (wasRebuffering && driftMs <= 500L) p.pendingRebufferResync = false
        val thresholdMs = if (wasRebuffering) 500L else 1_500L
        if (driftMs < thresholdMs) return null
        if (p.lastResyncSentWallMs > 0L && nowWall - p.lastResyncSentWallMs < 4_000L) return null
        p.pendingRebufferResync = false
        p.lastResyncSentWallMs = nowWall
        return e.positionMs
    }

    private class EventReport(
        val p: Playback,
        val gen: Long,
        val flushFirstPlay: Boolean = false,
        val resumePos: Long? = null,
        val pausePos: Long? = null,
        val seekPos: Long? = null,
        val seekFrom: Long? = null,
        val seekSpeed: Int = 1,
        val startedPos: Long? = null,
        val shouldPreload: Boolean = false,
        val thresholdPos: Long? = null,
        val preloadLead: Boolean = false,
        val sequentialFadeMs: Long? = null,
        val crossfadeTriggerPos: Long? = null,
        val crossfadeFadeMs: Long = 0L,
        val crossfadeNext: StateIndexRef? = null,
        val crossfadeNextState: SmState? = null,
        val crossfadeNextTrack: SmTrack? = null,
        val crossfadePreloaded: Triple<String, ChosenFile, PlayableMedia>? = null,
        val endedShownPos: Long? = null,
        val endedNext: StateIndexRef? = null,
        val endedNextState: SmState? = null,
        val endedNextTrack: SmTrack? = null,
        val endedNeedsStop: Boolean = false,
        val endedCrossfadeMs: Long = 0L,
        val periodicFlush: Boolean = false,
        val resyncPos: Long? = null,
    ) {
        fun hasWork(): Boolean =
            flushFirstPlay || resumePos != null || pausePos != null || seekPos != null ||
                startedPos != null || thresholdPos != null || preloadLead ||
                sequentialFadeMs != null || crossfadeTriggerPos != null ||
                endedShownPos != null || periodicFlush || resyncPos != null
    }

    private fun reportPlayerEventFailure(err: Throwable) {
        if (err is EngineException.Unauthorized) emit(EngineEvent.Unauthorized(err.url))
        else emit(EngineEvent.Failure("player event: ${err.message}"))
    }

    private suspend fun runEventReportCancellably(action: EventReport) {
        var thrown: Throwable? = null
        val job = scope.launch {
            try {
                runEventReport(action)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                thrown = t
            }
        }
        activeEventHttpJob = job
        try {
            job.join()
        } finally {
            if (activeEventHttpJob === job) activeEventHttpJob = null
        }
        thrown?.let { throw it }
    }

    internal suspend fun handlePlayerEvent(e: PlayerEvent) {
        val action = stateLock.withLock { applyPlayerEventStateLocked(e) } ?: return
        if (action.hasWork()) {
            runEventReport(action)
        }
    }

    private fun applyPlayerEventStateLocked(e: PlayerEvent): EventReport? {
        val p = current ?: return null
        if (!p.loadDispatched && e !is PlayerEvent.Error) return null
        val gen = trackGeneration
        when (e) {
            is PlayerEvent.Playing -> publishNow(p, e.positionMs, true)
            is PlayerEvent.Paused -> publishNow(p, e.positionMs, false)
            is PlayerEvent.Seeked -> publishNow(p, e.positionMs, p.playing)
            is PlayerEvent.Buffering -> publishNow(p, e.positionMs, false, buffering = true)
            is PlayerEvent.Ended -> publishNow(p, e.positionMs, false)
            else -> {}
        }
        var flushFirstPlayNow = false
        var resumePos: Long? = null
        var pausePos: Long? = null
        var seekPos: Long? = null
        var seekFrom: Long? = null
        var seekSpeed = 1
        var startedPos: Long? = null
        var shouldPreload = false
        var thresholdPos: Long? = null
        var preloadLead = false
        var sequentialFadeMs: Long? = null
        var crossfadeTriggerPos: Long? = null
        var crossfadeFadeMs = 0L
        var crossfadeNext: StateIndexRef? = null
        var crossfadeNextState: SmState? = null
        var crossfadeNextTrack: SmTrack? = null
        var crossfadePreloaded: Triple<String, ChosenFile, PlayableMedia>? = null
        var endedShownPos: Long? = null
        var endedNext: StateIndexRef? = null
        var endedNextState: SmState? = null
        var endedNextTrack: SmTrack? = null
        var endedNeedsStop = false
        var endedCrossfadeMs = 0L

        fun updateProgress(pos: Long) {
            if (p.playing) timeline.tick(p, pos)
            if (!p.started && p.msPlayed >= STARTED_AFTER_MS) {
                p.started = true
                startedPos = pos
                if (config.preloadBeforeEndMs == null) shouldPreload = true
            }
            if (p.started && !p.thresholdSent && p.msPlayed >= p.track.msPlayedUntilUpdate) {
                p.thresholdSent = true
                thresholdPos = pos
            }
            val lead = config.preloadBeforeEndMs
            if (lead != null && p.started && p.durationMs - pos <= lead && !p.preloaded) preloadLead = true

            val window = crossfadeFadeMsLocked(p)
            val remaining = p.durationMs - pos
            if (window >= MIN_CROSSFADE_MS &&
                !muted &&
                p.playing &&
                !p.wantPaused &&
                p.started &&
                p.loadDispatched &&
                p.pending == null &&
                !p.crossfadeSuppressed &&
                !p.crossfadeTriggered &&
                remaining in 1..window
            ) {
                val adv = findAdvanceTargetLocked(p, allowAdFallback = false)
                val nextProv = adv?.let { it.nextState.trackProvider ?: it.nextState.endContentInfo?.provider }
                val isStandaloneTrackCtx = lastNonAdContextUri?.startsWith("spotify:track:") == true
                val allowAutoplayCrossfade = autoplayEnabled || isStandaloneTrackCtx || !nextProv.equals("autoplay", ignoreCase = true)
                if (adv != null &&
                    allowAutoplayCrossfade &&
                    !isAdTrack(adv.nextTrack, adv.nextState) &&
                    adv.nextState.id != p.stateId &&
                    adv.nextTrack.metadata.uri != p.track.metadata.uri
                ) {
                    if (player.supportsConcurrentPlayback) {
                        if (remaining >= MIN_CROSSFADE_MS) {
                            p.crossfadeTriggered = true
                            p.crossfadeConcurrent = true
                            crossfadeTriggerPos = pos
                            crossfadeFadeMs = minOf(window, remaining)
                            crossfadeNext = adv.next
                            crossfadeNextState = adv.nextState
                            crossfadeNextTrack = adv.nextTrack
                            crossfadePreloaded = p.preloadedMedia?.takeIf { it.first == adv.nextState.id }
                        }
                    } else {
                        val sequentialWindow = minOf(window, MAX_SEQUENTIAL_FADE_MS)
                        if (remaining in MIN_CROSSFADE_MS..sequentialWindow) {
                            p.crossfadeTriggered = true
                            p.crossfadeConcurrent = false
                            sequentialFadeMs = remaining
                        }
                    }
                }
            }
        }

        when (e) {
            is PlayerEvent.Loaded -> p.durationMs = e.durationMs
            is PlayerEvent.Playing -> {
                p.playerPaused = false
                p.advanceTo(e.positionMs)
                p.playing = true
                p.countedFrom = e.positionMs
                if (p.firstPlayWallMs == 0L) {
                    p.firstPlayWallMs = env.nowMs()
                    timeline.firstPlay(p, e.positionMs)
                    // The timeline went out right after the next track's first audio following a
                    // natural end, and 120 s after that (end-of-track: +0.45 s, then +120 s).
                    if (flushOnFirstPlay) { flushOnFirstPlay = false; flushFirstPlayNow = true }
                }
                if (p.pending == Playback.Pending.Resume) {
                    p.pending = null
                    // Before `started_playing` the first audio is reported by that one instead.
                    if (p.started) {
                        timeline.pausedChanged(p, false, e.positionMs)
                        resumePos = e.positionMs
                    }
                }
                updateProgress(e.positionMs)
            }
            is PlayerEvent.Position -> {
                p.advanceTo(e.positionMs)
                updateProgress(e.positionMs)
            }
            is PlayerEvent.Buffering -> {
                p.playerPaused = false
                p.advanceTo(e.positionMs)
                if (p.startedStateReported && !p.wantPaused) p.pendingRebufferResync = true
            }
            is PlayerEvent.Paused -> {
                p.playerPaused = true
                p.advanceTo(e.positionMs)
                p.playing = false
                p.countedFrom = null
                if (p.pending == Playback.Pending.Pause) {
                    p.pending = null
                    timeline.pausedChanged(p, true, e.positionMs)
                    pausePos = e.positionMs
                }
            }
            is PlayerEvent.Seeked -> {
                val from = (p.pending as? Playback.Pending.Seek)?.from ?: p.lastPosition
                p.pending = null
                if (e.positionMs < from) p.seekBack++ else p.seekForward++
                p.lastPosition = e.positionMs
                p.countedFrom = if (p.playing) e.positionMs else null
                val window = crossfadeFadeMsLocked(p)
                p.crossfadeSuppressed = window > 0 && p.durationMs - e.positionMs <= window
                timeline.seek(p, from, e.positionMs)
                seekPos = e.positionMs
                seekFrom = from
                seekSpeed = if (p.playing) 1 else 0
            }
            is PlayerEvent.Ended -> {
                val shown = p.lastPosition
                // Played time runs to the file's end: ms_played 29475 = 150227 - 120752 (end-of-track)
                // and 151238 = 153889 - 2651 (real1), although the last position report was earlier.
                // A player whose measured duration overshoots the file's (150274) is clamped to it.
                val end = if (p.fileDurationMs in shown..e.positionMs) p.fileDurationMs else e.positionMs
                p.advanceTo(end)
                p.playing = false
                current = null
                if (p.crossfadeTriggered && p.crossfadeConcurrent) {
                    p.endedBeforeSwap = true
                } else {
                    endedShownPos = shown
                    val adv = findAdvanceTargetLocked(p)
                    if (adv == null) {
                        lastFinalizedPosition = null
                        endedNeedsStop = true
                    } else {
                        lastFinalizedPosition = shown
                        if (isAdTrack(adv.nextTrack, adv.nextState)) {
                            machine?.let { sm ->
                                val outIdx = stateIndex.takeIf { it in sm.states.indices && sm.states[it].id == p.stateId }
                                    ?: sm.states.indexOfFirst { it.id == p.stateId }
                                if (outIdx >= 0) {
                                    findNonAdHintPairForStateLocked(sm.states[outIdx])?.let { (hintUri, hintProv) ->
                                        if (hintUri != null) {
                                            adNextHint = hintUri
                                            adNextProvider = hintProv
                                        }
                                    }
                                }
                            }
                        }
                        stateIndex = machine?.states?.indexOfFirst { it.id == adv.nextState.id } ?: -1
                        flushOnFirstPlay = true
                        endedNext = adv.next
                        endedNextState = adv.nextState
                        endedNextTrack = adv.nextTrack
                        if (!muted && crossfadeSeconds > 0 && !p.crossfadeSuppressed && !player.supportsConcurrentPlayback) {
                            endedCrossfadeMs = minOf(crossfadeFadeMsLocked(p), 1_000L)
                        }
                    }
                }
            }
            is PlayerEvent.Error -> {
                current = null
                trackGeneration++
                lastFinalizedPosition = null
                pendingLoadReport?.let { it.complete(Unit); pendingLoadReport = null }
                activeEventHttpJob?.cancel()
                publishNow(null, 0, false)
                player.stop()
                emit(EngineEvent.Failure("player: ${e.message} at ${e.positionMs} ms"))
            }
        }
        if (timeline.lastFlushMs == 0L) timeline.lastFlushMs = env.nowMs()
        val periodicFlush = env.nowMs() - timeline.lastFlushMs >= 120_000
        return EventReport(
            p = p,
            gen = gen,
            flushFirstPlay = flushFirstPlayNow,
            resumePos = resumePos,
            pausePos = pausePos,
            seekPos = seekPos,
            seekFrom = seekFrom,
            seekSpeed = seekSpeed,
            startedPos = startedPos,
            shouldPreload = shouldPreload,
            thresholdPos = thresholdPos,
            preloadLead = preloadLead,
            sequentialFadeMs = sequentialFadeMs,
            crossfadeTriggerPos = crossfadeTriggerPos,
            crossfadeFadeMs = crossfadeFadeMs,
            crossfadeNext = crossfadeNext,
            crossfadeNextState = crossfadeNextState,
            crossfadeNextTrack = crossfadeNextTrack,
            crossfadePreloaded = crossfadePreloaded,
            endedShownPos = endedShownPos,
            endedNext = endedNext,
            endedNextState = endedNextState,
            endedNextTrack = endedNextTrack,
            endedNeedsStop = endedNeedsStop,
            endedCrossfadeMs = endedCrossfadeMs,
            periodicFlush = periodicFlush,
            resyncPos = computeResyncPosLocked(
                p = p,
                e = e,
                hasOtherStateReport = resumePos != null || pausePos != null || seekPos != null ||
                    startedPos != null || endedShownPos != null,
            ),
        )
    }

    private suspend fun runEventReport(action: EventReport) {
        val p = action.p
        val gen = action.gen
        suspend fun stillCurrent(): Boolean = stateLock.withLock { current === p && trackGeneration == gen }
        var preloadPending = false

        reportLock.withLock {
            if (action.flushFirstPlay && stillCurrent()) flushTimeline()
            action.resumePos?.let { pos ->
                if (stillCurrent()) putState(p, "resume", pos, pos, expectedGen = gen)
            }
            action.pausePos?.let { pos ->
                if (stillCurrent()) putState(p, "pause", pos, pos, speed = 0, expectedGen = gen)
            }
            action.seekPos?.let { pos ->
                if (stillCurrent()) putState(p, "seek", pos, action.seekFrom ?: pos, speed = action.seekSpeed, expectedGen = gen)
            }
            action.startedPos?.let { pos ->
                val livePos = stateLock.withLock {
                    if (current !== p || trackGeneration != gen) null
                    else {
                        val curNow = _now.value
                        if (p.playing && !p.playerPaused && curNow != null && curNow.uri == p.track.metadata.uri) {
                            (curNow.positionMs + (env.nowMs() - curNow.atMs).coerceAtLeast(0L))
                                .coerceIn(pos, p.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
                        } else pos
                    }
                }
                if (livePos != null) {
                    putState(p, "started_playing", livePos, livePos, expectedGen = gen)
                    if (action.shouldPreload) preloadPending = true
                }
            }
            action.thresholdPos?.let { pos ->
                if (stillCurrent()) putState(p, "played_threshold_reached", pos, pos, expectedGen = gen)
            }
            if (action.preloadLead) preloadPending = true
        }

        if (preloadPending && stillCurrent()) {
            preloadNext(p, gen)
        }

        action.sequentialFadeMs?.let { dur ->
            if (stillCurrent()) player.fadeOutCurrent(dur)
        }

        if (action.crossfadeTriggerPos != null) {
            runCrossfadeAdvance(action)
        }

        action.endedShownPos?.let { shown ->
            var abortAdvance = false
            reportLock.withLock {
                if (stateLock.withLock { trackGeneration != gen }) {
                    abortAdvance = true
                    return@withLock
                }
                finalize(p, "trackdone", action.endedNextState?.id, shown, keepMachine = true)
                if (stateLock.withLock { trackGeneration != gen }) {
                    abortAdvance = true
                    return@withLock
                }
                if (action.endedNeedsStop) {
                    player.stop()
                    emit(EngineEvent.ContextEnded)
                    return@withLock
                }
            }
            if (!abortAdvance && !action.endedNeedsStop) {
                val next = action.endedNext ?: return@let
                val nextState = action.endedNextState ?: return@let
                val nextTrack = action.endedNextTrack ?: return@let
                load(
                    nextState, nextTrack, nextState.initialPlaybackPosition ?: 0, next.paused, "trackdone", shown,
                    expectedGen = gen, crossfadeMs = action.endedCrossfadeMs,
                )
            }
        }

        if (action.periodicFlush) {
            reportLock.withLock {
                if (stateLock.withLock { trackGeneration == gen }) flushTimeline()
            }
        }

        action.resyncPos?.let { pos ->
            reportLock.withLock {
                if (stateLock.withLock { trackGeneration == gen && current === p && p.started && !p.wantPaused }) {
                    val livePos = stateLock.withLock { if (p.pendingSeekMs == null) p.lastPosition else pos }
                    putState(p, "modify_current_state", livePos, livePos, expectedGen = gen)
                }
            }
        }
    }

    /** Finalizes the current track early when crossfade begins and commits the overlapping next track. */
    private suspend fun runCrossfadeAdvance(action: EventReport) {
        val p = action.p
        val gen = action.gen
        val next = action.crossfadeNext ?: return
        val nextState = action.crossfadeNextState ?: return
        val nextTrack = action.crossfadeNextTrack ?: return
        val triggerPos = action.crossfadeTriggerPos ?: return
        try {
            val cached = action.crossfadePreloaded?.takeIf {
                it.first == nextState.id && it.third.expiresAtMs > env.nowMs() + 10_000L
            }
            val t0 = env.nowMs()
            val (file, media) = if (cached != null) {
                cached.second to cached.third
            } else {
                val stillAlive = stateLock.withLock { trackGeneration == gen && ((current === p && !p.wantPaused) || p.endedBeforeSwap) }
                if (!stillAlive) return
                val f = Resolver.chooseFile(nextTrack, formatFilter, lowestQuality)
                    ?: throw EngineException.Protocol("no playable file for ${nextTrack.metadata.uri}")
                val m = resolver.resolve(nextTrack, formatFilter, lowestQuality)
                f to m
            }
            var shown = triggerPos
            var liveFadeMs = 0L
            var abort = false
            reportLock.withLock {
                val canCommit = stateLock.withLock {
                    if (trackGeneration != gen || muted || crossfadeSeconds <= 0) {
                        if (current === p && trackGeneration == gen) p.crossfadeTriggered = false
                        false
                    } else if (p.endedBeforeSwap) {
                        shown = p.lastPosition
                        lastFinalizedPosition = shown
                        stateIndex = machine?.states?.indexOfFirst { it.id == nextState.id } ?: -1
                        flushOnFirstPlay = true
                        liveFadeMs = 0L
                        true
                    } else if (current === p && !p.wantPaused) {
                        shown = p.lastPosition
                        val rem = (p.durationMs - shown).coerceAtLeast(0L)
                        val maxFade = crossfadeFadeMsLocked(p)
                        liveFadeMs = minOf(maxFade, rem)
                        lastFinalizedPosition = shown
                        stateIndex = machine?.states?.indexOfFirst { it.id == nextState.id } ?: -1
                        flushOnFirstPlay = true
                        true
                    } else {
                        if (current === p && trackGeneration == gen) p.crossfadeTriggered = false
                        false
                    }
                }
                if (!canCommit) {
                    abort = true
                    return@withLock
                }
                finalize(p, "trackdone", nextState.id, shown, keepMachine = true)
                if (stateLock.withLock { trackGeneration != gen }) {
                    abort = true
                    return@withLock
                }
            }
            if (abort) return
            emit(EngineEvent.TrackLoading(nextTrack.metadata.uri, nextState.id))
            commitLoaded(
                nextState, nextTrack, file, media,
                nextState.initialPlaybackPosition ?: 0L, next.paused, "trackdone", shown, t0,
                tolerateMelodyFailure = false, expectedGen = gen, crossfadeMs = liveFadeMs,
            )
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                var needsHardLoad = false
                stateLock.withLock {
                    if (trackGeneration == gen) {
                        if (current === p) p.crossfadeSuppressed = true
                        else if (p.endedBeforeSwap) needsHardLoad = true
                    }
                }
                emit(EngineEvent.Failure("crossfade: ${t.message}"))
                if (needsHardLoad) {
                    runCatchingNonCancel {
                        load(nextState, nextTrack, nextState.initialPlaybackPosition ?: 0L, next.paused, "trackdone", triggerPos, expectedGen = gen)
                    }
                }
            } else {
                throw t
            }
        }
    }

    /** Resolves and licenses the non-AD track `advance` (or `skip_next` when `advance` is an ad) leads to, so a track transition starts at once. */
    private suspend fun preloadNext(p: Playback, expectedGen: Long) {
        val nextInfo = stateLock.withLock {
            if (current !== p || trackGeneration != expectedGen || p.preloaded) return@withLock null
            p.preloaded = true
            val target = findAdvanceTargetLocked(p, allowAdFallback = false) ?: return@withLock null
            target.nextState.id to target.nextTrack
        } ?: return
        val (nextId, track) = nextInfo
        runCatchingNonCancel {
            val file = Resolver.chooseFile(track, formatFilter, lowestQuality) ?: return@runCatchingNonCancel
            val media = resolver.resolve(track, formatFilter, lowestQuality)
            val saved = stateLock.withLock {
                if (current !== p || trackGeneration != expectedGen) return@withLock false
                p.preloadedMedia = Triple(nextId, file, media)
                true
            }
            if (!saved) return
            player.preload(media, licences.channel())
            emit(EngineEvent.Preloaded(track.metadata.uri))
        }.onFailure { emit(EngineEvent.Failure("preload: ${it.message}")) }
    }

    // ---------------------------------------------------------------- sending

    private suspend fun putState(
        p: Playback, debugSource: String, position: Long, previousPosition: Long?, speed: Int? = null,
        stats: JsonObject? = null, duration: Long = p.durationMs, applyAnswer: Boolean = true,
        expectedGen: Long? = null,
    ) {
        val s = speed ?: if (p.wantPaused) 0 else 1
        val sentWall = env.nowMs()
        val sub = Bodies.SubState(s, position, duration, p.file.bitrate, p.file.format, p.file.audioQuality)
        putState(machine?.id, p.stateId, p.wantPaused, sub, previousPosition, debugSource, stats, applyAnswer, expectedGen)
        if (debugSource != "track_data_finalized") {
            p.lastReportedPosMs = position
            p.lastReportedWallMs = sentWall
            p.lastReportedSpeed = s
            if (debugSource == "before_track_load") {
                p.startedStateReported = false
            } else {
                p.startedStateReported = true
                if (s == 0 || debugSource == "seek" || debugSource == "resume" ||
                    debugSource == "started_playing" || debugSource == "modify_current_state"
                ) {
                    p.pendingRebufferResync = false
                }
            }
        }
    }

    private suspend fun putState(
        machineId: String?, stateId: String?, paused: Boolean, sub: Bodies.SubState?, previousPosition: Long?,
        debugSource: String, stats: JsonObject? = null, applyAnswer: Boolean = true,
        expectedGen: Long? = null,
    ) {
        val (nextSeq, gen) = stateLock.withLock { (++seq) to (expectedGen ?: trackGeneration) }
        val body = Bodies.state(nextSeq, machineId, stateId, paused, sub, previousPosition, debugSource, stats)
        val r = sp.send(
            "PUT", "$spclient/track-playback/v1/devices/$deviceId/state", HeaderProfile.PLAYER,
            body = body.toString().encodeToByteArray(), contentType = "application/json",
        )
        emit(EngineEvent.StateReported(debugSource, sub?.position))
        if (!applyAnswer) return
        // The answer is the server's next state machine; later reports refer to it.
        val next = runCatching { EngineJson.decodeFromString(PutStateResponse.serializer(), r.text()) }.getOrNull() ?: return
        val sm = next.stateMachine ?: return
        stateLock.withLock {
            if (gen != trackGeneration) return@withLock
            machine = sm
            val idx = next.updatedStateRef?.stateIndex ?: sm.states.indexOfFirst { it.id == stateId }
            stateIndex = idx
            sm.states.getOrNull(idx)?.let { st ->
                val tr = sm.tracks.getOrNull(st.track)
                if (tr != null && !isAdTrack(tr, st)) {
                    recordNonAdContextLocked(st, tr)
                    val adv = resolveTargetLocked(sm, st.transitions.advance)
                    if (adv != null && isAdTrack(adv.nextTrack, adv.nextState)) {
                        findNonAdHintPairForStateLocked(st)?.let { (hintUri, hintProv) ->
                            if (hintUri != null) {
                                adNextHint = hintUri
                                adNextProvider = hintProv
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun melody(messages: List<JsonObject>, sdkId: String) {
        sp.send(
            "POST", "$spclient/melody/v1/msg/batch", HeaderProfile.AUTH,
            body = Bodies.melodyBatch(messages, sdkId, config).toString().encodeToByteArray(),
            contentType = "text/plain;charset=UTF-8",
        )
    }

    private suspend fun flushTimeline() {
        val msg = stateLock.withLock { timeline.drain() } ?: return
        melody(listOf(Bodies.melodyMessage("media_playback_event_v2", msg)), config.harmonyVersion)
    }

    internal val currentMachineId get() = machine?.id
    internal val seqNum get() = seq

    companion object {
        /** `started_playing` came 1066-1245 ms into real playback in every capture (four tracks). */
        const val STARTED_AFTER_MS = 1_000L
        private const val MAX_SKIP_HOPS = 8
        private const val MAX_CROSSFADE_SECONDS = 12
        private const val MIN_CROSSFADE_MS = 500L
        private const val MAX_SEQUENTIAL_FADE_MS = 2_500L
        private const val MAX_CLOCK_RTT_MS = 5_000L
        private const val MAX_PLAUSIBLE_CLOCK_SKEW_MS = 300_000L
        private const val CLOCK_RESYNC_GAP_MS = 2_000L
    }
}
