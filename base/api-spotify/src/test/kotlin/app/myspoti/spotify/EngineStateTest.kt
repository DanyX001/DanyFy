package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.Repeat
import app.myspoti.engine.NativeEngine
import app.myspoti.spotify.bridge.AdSchedule
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The account's player while the native engine plays its songs: the state shown is the engine's
 * own playback (live) laid over the page's (late), and with no page at all the engine's alone.
 */
class EngineStateTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val calls = mutableListOf<String>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2]
            val value = if (m.groupValues[2] == "media.state") """{"paused":true,"muted":false,"level":1,"own":1,"time":0,"active":false}""" else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private class FakeEngine : AccountAudio {
        override var on = true
        override val deviceId = "engine"
        override val now = MutableStateFlow<NativeEngine.Now?>(null)
        override val cluster = MutableStateFlow<app.myspoti.engine.Cluster?>(null)
        override val holdsPlayback get() = now.value != null
        override var mirrors = false
        val calls = mutableListOf<String>()
        val crossfadeUpdates = mutableListOf<Int>()
        override fun setCrossfadeSeconds(seconds: Int) { crossfadeUpdates += seconds }
        var onPlay: (suspend () -> Unit)? = null
        var onResume: (suspend () -> Unit)? = null
        var onNext: (suspend () -> Unit)? = null
        var onSkipTo: (suspend () -> Unit)? = null
        override suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean?) {
            calls += "play $trackUri"
            onPlay?.invoke()
        }
        var remotePlayOk = true
        override suspend fun remotePlay(
            contextUri: String,
            trackUri: String?,
            index: Int?,
            positionMs: Long?,
            order: List<String>?,
            shuffle: Boolean?,
            targetDeviceId: String?,
        ): Boolean {
            calls += "remotePlay $contextUri $trackUri idx=$index target=$targetDeviceId"
            if (!remotePlayOk) return false
            return cluster.value?.activeDeviceId?.let { it != deviceId } == true || targetDeviceId != null
        }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume"; onResume?.invoke() }
        override suspend fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override suspend fun next() { calls += "next"; onNext?.invoke() }
        override suspend fun previous() { calls += "previous" }
        override suspend fun skipTo(contextUri: String?, trackUri: String, trackUid: String?, provider: String?, order: List<String>?, shuffle: Boolean?, steps: Int?) {
            calls += "skipTo $contextUri $trackUri steps=$steps"
            onSkipTo?.invoke()
        }
        override suspend fun remotePause(targetDeviceId: String?): Boolean {
            calls += "remotePause $targetDeviceId"
            return cluster.value?.activeDeviceId?.let { it != deviceId } == true || targetDeviceId != null
        }
        override suspend fun remoteResume(targetDeviceId: String?): Boolean {
            calls += "remoteResume $targetDeviceId"
            return cluster.value?.activeDeviceId?.let { it != deviceId } == true || targetDeviceId != null
        }
        override suspend fun remoteSeek(positionMs: Long, targetDeviceId: String?): Boolean {
            calls += "remoteSeek $positionMs $targetDeviceId"
            return cluster.value?.activeDeviceId?.let { it != deviceId } == true || targetDeviceId != null
        }
        override suspend fun remoteNext(targetDeviceId: String?): Boolean {
            calls += "remoteNext $targetDeviceId"
            return cluster.value?.activeDeviceId?.let { it != deviceId } == true || targetDeviceId != null
        }
        override suspend fun remotePrevious(positionMs: Long, targetDeviceId: String?): Boolean {
            calls += "remotePrevious $positionMs $targetDeviceId"
            return cluster.value?.activeDeviceId?.let { it != deviceId } == true || targetDeviceId != null
        }
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var player: SpotifyPlayer
    private lateinit var engine: FakeEngine

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost()
        player = SpotifyPlayer(Bridge(host, scope, time::now), scope)
        engine = FakeEngine()
        player.audio = engine
    }

    @AfterEach fun down() { AdSchedule.clear(); scope.cancel() }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","artists":[{"uri":"spotify:artist:X","name":"Artist $name"}],"durationMs":200000}"""

    private fun pageState(item: String, paused: Boolean, positionMs: Long, next: String = track("N")) =
        """{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":$next,"isPaused":$paused,"isBuffering":false,
            |"shuffle":true,"repeat":1,"positionMs":$positionMs,"timestamp":${time.now()},"restrictions":{}}}""".trimMargin()

    private fun now(name: String, playing: Boolean, positionMs: Long) = NativeEngine.Now(
        uri = "spotify:track:$name", name = "$name (engine)", artists = "Artist $name", album = "Album", imageUrl = null,
        durationMs = 201_000, playing = playing, positionMs = positionMs, atMs = time.now(), contextUri = "spotify:playlist:C",
        authors = listOf("spotify:artist:X" to "Artist $name"), albumUri = "spotify:album:Y",
    )

    private suspend fun waitFor(what: String, cond: () -> Boolean) = time.waitFor(what, 4_000, cond)

    @Test fun `the engine's song shows at once with no page up`() = time.test {
        engine.now.value = now("A", playing = true, positionMs = 1_000)
        waitFor("engine state") { player.state.value != null }
        val s = player.state.value!!
        assertEquals("spotify:track:A", s.item?.uri)
        assertEquals("A (engine)", s.item?.name)
        assertEquals(listOf("Artist A"), s.item?.artists?.map { it.name })
        assertEquals("spotify:album:Y", s.item?.album?.uri)
        assertFalse(s.isPaused)
        assertEquals(1_000, s.positionMs)
        assertNull(player.pageState.value)
    }

    @Test fun `the page's late state of the same song keeps its details and takes the engine's place and pause`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = false, positionMs = 42_000)
        // The page still says it plays, six seconds back.
        host.post(pageState(track("A"), paused = false, positionMs = 36_000))
        waitFor("page state") { player.pageState.value != null }
        val s = player.state.value!!
        assertEquals("A", s.item?.name)
        assertTrue(s.isPaused)
        assertEquals(42_000, s.positionMs)
        assertEquals(201_000, s.item?.durationMs)
        assertTrue(s.shuffle)
        assertEquals(Repeat.CONTEXT, s.repeat)
        assertEquals("spotify:track:N", s.next?.uri)
    }

    @Test fun `the engine moved on to the page's next song, shown before the page says so`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = true, positionMs = 0)
        host.post(pageState(track("A"), paused = false, positionMs = 0))
        waitFor("page state") { player.pageState.value != null }
        engine.now.value = now("N", playing = true, positionMs = 300)
        waitFor("next song shown") { player.state.value?.item?.uri == "spotify:track:N" }
        val s = player.state.value!!
        assertEquals("spotify:track:A", s.previous?.uri)
        assertEquals("uN", s.item?.uid)
        assertTrue(s.shuffle)
        assertEquals("spotify:track:A", player.pageState.value?.item?.uri)
    }

    @Test fun `the engine playing the listen mirror is not shown as the account's`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(pageState(track("A"), paused = true, positionMs = 5_000))
        waitFor("page state") { player.pageState.value != null }
        engine.mirrors = true
        engine.now.value = now("M", playing = true, positionMs = 0)
        runCurrent()
        assertEquals("spotify:track:A", player.state.value?.item?.uri)
        assertTrue(player.state.value!!.isPaused)
    }

    @Test fun `the engine off or left aside, the page's state as it is`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.on = false
        engine.now.value = now("A", playing = true, positionMs = 9_000)
        host.post(pageState(track("A"), paused = true, positionMs = 3_000))
        waitFor("page state") { player.pageState.value != null }
        assertTrue(player.state.value!!.isPaused)
        assertEquals(3_000, player.state.value!!.positionMs)
    }

    @Test fun `the engine stops, the page's state shows again`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = true, positionMs = 9_000)
        host.post(pageState(track("A"), paused = true, positionMs = 3_000))
        waitFor("page state") { player.pageState.value != null }
        assertFalse(player.state.value!!.isPaused)
        engine.now.value = null
        waitFor("page shown") { player.state.value?.isPaused == true }
        assertEquals(3_000, player.state.value!!.positionMs)
    }

    @Test fun `commands go to the engine while it is on`() = time.test {
        engine.now.value = now("A", playing = true, positionMs = 0)
        player.pause()
        player.resume()
        player.seek(10_000)
        player.next()
        assertEquals(listOf("pause", "resume", "seek 10000", "next"), engine.calls)
    }

    @Test fun `skipTo goes to the engine instead of WebView while the engine holds playback`() = time.test {
        host.post("""{"type":"ready"}""")
        engine.now.value = now("A", playing = true, positionMs = 0)
        host.post(pageState(track("A"), paused = false, positionMs = 0))
        host.post("""{"type":"queue","queue":{"current":${track("A")},"queued":[],"nextUp":[${track("N")},${track("N2")}]}}""")
        waitFor("queue") { player.queue.value?.nextUp?.size == 2 }
        host.calls.clear()

        val target = NowPlaying(
            kind = ItemKind.TRACK,
            uri = "spotify:track:N2",
            uid = "uN2",
            name = "N2",
            artists = emptyList(),
            album = null,
            imageUrl = null,
            durationMs = 200_000L,
            provider = "context",
        )
        player.skipTo(target)
        assertEquals(listOf("skipTo spotify:playlist:C spotify:track:N2 steps=2"), engine.calls)
        assertFalse("queue.skipTo" in host.calls)
    }

    @Test fun `cancelled outside play rethrows without falling back to the page`() = time.test {
        host.post("""{"type":"ready"}""")
        val started = CompletableDeferred<Unit>()
        engine.onPlay = {
            started.complete(Unit)
            delay(10_000)
        }
        val job = scope.launch { player.play("spotify:playlist:C", "spotify:track:A") }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse("player.play" in host.calls)
    }

    @Test fun `transport errors on next skipTo and empty-engine resume fall back cleanly`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(pageState(track("A"), paused = true, positionMs = 5_000))
        waitFor("page state") { player.pageState.value != null }

        // When engine has no loaded track (holdsPlayback=false) and resume() throws, replay last track via engine.play().
        engine.onResume = { error("no active track on native engine") }
        player.resume()
        assertEquals(listOf("resume", "play spotify:track:A"), engine.calls)

        // When engine is set aside or play also fails, resume falls back to the WebView page.
        engine.calls.clear()
        host.calls.clear()
        engine.onPlay = { error("429") }
        player.resume()
        assertTrue("player.resume" in host.calls)

        // Failing next / skipTo outside falls back to the page instead of wedging.
        host.calls.clear()
        engine.now.value = now("A", playing = true, positionMs = 0)
        engine.onNext = { error("ad blocked") }
        player.next()
        assertTrue("player.next" in host.calls)

        host.calls.clear()
        engine.onSkipTo = { error("ad blocked") }
        val target = NowPlaying(
            kind = ItemKind.TRACK, uri = "spotify:track:N", uid = "uN", name = "N",
            artists = emptyList(), album = null, imageUrl = null, durationMs = 200_000L, provider = "context",
        )
        player.skipTo(target)
        assertTrue("queue.skipTo" in host.calls)
    }

    @Test fun `crossfade setting propagates to current and newly assigned AccountAudio`() = time.test {
        assertEquals(listOf(0), engine.crossfadeUpdates)

        player.crossfadeSeconds = 6
        assertEquals(listOf(0, 6), engine.crossfadeUpdates)

        player.crossfadeSeconds = 19 // clamped to CROSSFADE_MAX_SECONDS (12)
        assertEquals(listOf(0, 6, 12), engine.crossfadeUpdates)

        val nextEngine = FakeEngine()
        player.audio = nextEngine
        assertEquals(listOf(12), nextEngine.crossfadeUpdates)

        player.crossfadeSeconds = 0
        assertEquals(listOf(12, 0), nextEngine.crossfadeUpdates)
    }

    @Test fun `remote Connect cluster removes page lag and keeps transport on connected player`() = time.test {
        host.post("""{"type":"ready"}""")
        // WebView page is ~7s behind (paused at 10s on A while TV cluster already resumed at 17s on A)
        host.post(pageState(track("A"), paused = true, positionMs = 10_000))
        waitFor("page state") { player.pageState.value != null }

        engine.cluster.value = app.myspoti.engine.Cluster(
            activeDeviceId = "tv-device",
            devices = listOf(app.myspoti.engine.ClusterDevice("tv-device", "Living Room TV", "TV", volume = 0.5)),
            trackUri = "spotify:track:A",
            contextUri = "spotify:playlist:C",
            paused = false,
            positionMs = 17_000L,
            timestampMs = 0L, // exercises clock fallback too
            durationMs = 200_000L,
        )
        runCurrent()
        waitFor("cluster overlay") { player.state.value?.positionMs == 17_000L }

        val shown = player.state.value!!
        assertEquals("A", shown.item!!.name)
        assertFalse(shown.isPaused)
        assertEquals(17_000L, shown.positionMs)
        assertTrue(shown.timestampMs > 0L)
        assertTrue(player.playsElsewhere.value)

        // Pause, resume, seek, next, previous stay on tv-device without calling local resume/play/next/previous
        player.pause()
        player.resume()
        player.seek(55_000L)
        player.next()
        player.previous()
        player.play("spotify:playlist:C", trackUri = "spotify:track:B", index = 1, positionMs = null, order = null)
        assertEquals(
            listOf(
                "remotePause tv-device",
                "remoteResume tv-device",
                "remoteSeek 55000 tv-device",
                "remoteNext tv-device",
                "remotePrevious 17000 tv-device",
                "remotePlay spotify:playlist:C spotify:track:B idx=1 target=tv-device",
            ),
            engine.calls,
        )
        assertFalse("resume" in engine.calls)
        assertFalse(engine.calls.any { it.startsWith("play ") })
        assertFalse("next" in engine.calls)
        assertFalse("previous" in engine.calls)
        assertFalse("player.resume" in host.calls)
        assertFalse("player.play" in host.calls)

        // When cluster reports playback returned to self device (or idle), playsElsewhere clears even if WebView lagged.
        engine.cluster.value = app.myspoti.engine.Cluster(
            activeDeviceId = "engine",
            devices = listOf(app.myspoti.engine.ClusterDevice("engine", "Phone", "SMARTPHONE", volume = 1.0)),
            trackUri = "spotify:track:A",
            contextUri = "spotify:playlist:C",
            paused = false,
            positionMs = 17_000L,
            timestampMs = 0L,
            durationMs = 200_000L,
        )
        runCurrent()
        assertFalse(player.playsElsewhere.value)
    }

    @Test fun `remote play does not fall back to phone playback when remotePlay is refused`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(pageState(track("A"), paused = false, positionMs = 10_000))
        waitFor("page state") { player.pageState.value != null }

        engine.cluster.value = app.myspoti.engine.Cluster(
            activeDeviceId = "tv-device",
            devices = listOf(app.myspoti.engine.ClusterDevice("tv-device", "Living Room TV", "TV", volume = 0.5)),
            trackUri = "spotify:track:A",
            contextUri = "spotify:playlist:C",
            paused = false,
            positionMs = 10_000L,
            timestampMs = time.now(),
            durationMs = 200_000L,
        )
        runCurrent()
        engine.remotePlayOk = false

        player.play("spotify:album:X", trackUri = "spotify:track:Z", index = 0, positionMs = null, order = null)

        assertEquals(listOf("remotePlay spotify:album:X spotify:track:Z idx=0 target=tv-device"), engine.calls)
        assertFalse(engine.calls.any { it.startsWith("play ") })
        assertFalse("player.play" in host.calls)
    }

    @Test fun `cluster spotify ad flips adPlaying immediately before lagging WebView and clears on track`() = time.test {
        host.post("""{"type":"ready"}""")
        host.post(pageState(track("A"), paused = false, positionMs = 10_000))
        waitFor("page state") { player.pageState.value != null }

        fun tvCluster(trackUri: String) = app.myspoti.engine.Cluster(
            activeDeviceId = "tv-device",
            devices = listOf(app.myspoti.engine.ClusterDevice("tv-device", "Living Room TV", "TV", volume = 0.5)),
            trackUri = trackUri,
            contextUri = "spotify:playlist:C",
            paused = false,
            positionMs = 5_000L,
            timestampMs = time.now(),
            durationMs = 30_000L,
        )

        // TV starts playing an ad via dealer Cluster ~7s before WebView catches up.
        engine.cluster.value = tvCluster("spotify:ad:tvad1")
        runCurrent()
        assertTrue(player.adPlaying.value)
        assertTrue(player.playsElsewhere.value)
        assertTrue(player.state.value?.isBuffering == true)
        assertEquals("spotify:track:A", player.state.value?.item?.uri)
        assertFalse("media.quiet" in host.calls)

        // Lagging WebView position tick on track A must not clear the cluster-detected ad.
        host.post(pageState(track("A"), paused = false, positionMs = 12_000))
        runCurrent()
        assertTrue(player.adPlaying.value)

        // Lagging WebView finally catches up to ad, then TV Cluster jumps straight back to track B.
        val adItem = """{"kind":"ad","uri":"spotify:ad:tvad1","name":"ad","durationMs":30000}"""
        host.post(pageState(adItem, paused = false, positionMs = 1_000))
        runCurrent()
        assertTrue(player.adPlaying.value)

        engine.cluster.value = tvCluster("spotify:track:B")
        runCurrent()
        assertFalse(player.adPlaying.value)
        assertEquals("spotify:track:B", player.state.value?.item?.uri)
    }
}
