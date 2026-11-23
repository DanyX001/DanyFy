package app.myspoti.spotify.sync

import app.myspoti.engine.AccessToken
import app.myspoti.engine.ClientToken
import app.myspoti.engine.DrmPlayer
import app.myspoti.engine.EngineConfig
import app.myspoti.engine.Environment
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.HttpResponse
import app.myspoti.engine.LicenceChannel
import app.myspoti.engine.NativeEngine
import app.myspoti.engine.PlayableMedia
import app.myspoti.engine.PlayerEvent
import app.myspoti.engine.TokenSource
import app.myspoti.engine.WebSocketConnection
import app.myspoti.engine.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [EngineSyncPage.play] realigns playback when initial playback starts at 0 ms but local
 * host position has advanced during load/DRM startup.
 */
class EngineSyncPageTest {
    private class FakeWs : WebSocketConnection {
        val incoming = Channel<String>(Channel.UNLIMITED)

        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

        override suspend fun send(text: String) {}

        override fun close() {
            incoming.close()
        }
    }

    private class FakeHttp(
        private val ws: FakeWs,
        private val onPlayCommand: () -> Unit,
    ) : Http {
        val commandBodies = ArrayList<String>()

        override suspend fun request(request: HttpRequest): HttpResponse {
            val u = request.url
            val body = request.body?.decodeToString().orEmpty()
            val text = when {
                "apresolve.spotify.com" in u ->
                    """{"dealer-g2":["gew4-dealer.g2.spotify.com:443"],"spclient":["gew4-spclient.spotify.com:443"]}"""
                "/track-playback/v1/devices" in u && u.endsWith("/devices") ->
                    """{"initial_seq_num":1,"file_format_filter":5160,"effective_license":"tft"}"""
                "/connect-state/v1/devices/hobs_" in u -> "{}"
                "/connect-state/v1/player/command/" in u -> {
                    commandBodies += body
                    if ("\"endpoint\":\"play\"" in body) {
                        onPlayCommand()
                        ws.incoming.trySend(replaceStateFrame())
                    }
                    "{}"
                }
                "/storage-resolve/" in u ->
                    """{"result":"CDN","cdnurl":["https://cdn.example/audio?1799999999_abc"],"fileid":"$FILE_ID","ttl":3600}"""
                "seektables.spotifycdn.com" in u ->
                    """{"seektableVersion":1,"offset":1000,"timescale":44100,"segments":[{"size":500,"duration":441000}]}"""
                "/track-playback/v1/devices/" in u && u.endsWith("/state") ->
                    """{"state_machine":${stateMachineJson("sm-put")},"updated_state_ref":{"state_index":0,"paused":false}}"""
                else -> "{}"
            }
            return HttpResponse(200, emptyMap(), text.encodeToByteArray())
        }
    }

    private class FakePlayer(
        private val scope: CoroutineScope,
        private val onLoadStarted: () -> Unit,
    ) : DrmPlayer {
        private var listener: ((PlayerEvent) -> Unit)? = null

        override fun setListener(listener: (PlayerEvent) -> Unit) {
            this.listener = listener
        }

        override fun load(media: PlayableMedia, positionMs: Long, playWhenReady: Boolean, licence: LicenceChannel) {
            onLoadStarted()
            scope.launch {
                listener?.invoke(PlayerEvent.Loaded(media.durationMs))
                listener?.invoke(PlayerEvent.Playing(positionMs))
            }
        }

        override fun preload(media: PlayableMedia, licence: LicenceChannel) {}
        override fun pause() {}
        override fun resume() {}
        override fun seekTo(positionMs: Long) {}
        override fun stop() {}
        override fun setVolume(volume: Int) {}
    }

    private class Harness(scope: CoroutineScope, private val onLoadStarted: () -> Unit = {}) {
        val ws = FakeWs()
        var envNow = 1_790_000_000_000L
        val http = FakeHttp(ws) { }
        val player = FakePlayer(scope, onLoadStarted)
        val tokens = object : TokenSource {
            override suspend fun accessToken(forceRefresh: Boolean) =
                AccessToken("tok", 4_000_000_000_000L, "0123456789abcdef0123456789abcdef")

            override suspend fun clientToken() =
                ClientToken("ctok", "00000000-0000-4000-a000-000000000000")
        }
        val env = object : Environment {
            private var n = 0
            override fun nowMs(): Long = envNow
            override fun randomHex(chars: Int): String = (++n).toString(16).padStart(chars, '0').take(chars)
        }
        val engine = NativeEngine(
            http = http,
            tokens = tokens,
            sockets = WebSockets { _, _ ->
                ws.incoming.trySend(
                    """{"type":"message","method":"PUT","uri":"hm://pusher/v1/connections/conn1","headers":{"Spotify-Connection-Id":"conn-1"}}"""
                )
                ws
            },
            player = player,
            env = env,
            config = EngineConfig(),
            scope = scope,
        )

        fun mirror(): MirrorEngine {
            val flow = MutableStateFlow<NativeEngine?>(engine)
            return object : MirrorEngine {
                override val current: StateFlow<NativeEngine?> = flow
                override suspend fun ready(): NativeEngine = engine
            }
        }
    }

    @Test
    fun `play from 0 ms seeks immediately when local position advances during load`() = runTest {
        var localPos = 0L
        val harness = Harness(backgroundScope) {
            // Simulate 1.8 s elapsed on the local player while engine resolves/loads at 0 ms.
            localPos = 1_800L
        }
        harness.engine.start()
        val page = EngineSyncPage(
            engine = harness.mirror(),
            scope = backgroundScope,
            livePositionMs = { localPos },
        )

        page.play(CONTEXT_URI, TRACK_URI, positionMs = 0L)

        assertEquals(2, harness.http.commandBodies.size, "expected play + seek_to commands, got ${harness.http.commandBodies}")
        assertTrue("\"endpoint\":\"play\"" in harness.http.commandBodies[0])
        assertTrue("\"endpoint\":\"seek_to\"" in harness.http.commandBodies[1], harness.http.commandBodies[1])
        assertTrue("1800" in harness.http.commandBodies[1], harness.http.commandBodies[1])
        harness.engine.close()
    }

    @Test
    fun `play from 0 ms without livePositionMs does not issue a spurious seek`() = runTest {
        val harness = Harness(backgroundScope)
        harness.engine.start()
        val page = EngineSyncPage(
            engine = harness.mirror(),
            scope = backgroundScope,
        )

        page.play(CONTEXT_URI, TRACK_URI, positionMs = 0L)

        assertEquals(1, harness.http.commandBodies.size, "expected only play command, got ${harness.http.commandBodies}")
        assertTrue("\"endpoint\":\"play\"" in harness.http.commandBodies[0])
        assertFalse(harness.http.commandBodies.any { "\"endpoint\":\"seek_to\"" in it })
        harness.engine.close()
    }

    private companion object {
        const val CONTEXT_URI = "spotify:playlist:p1"
        const val TRACK_URI = "spotify:track:t1"
        const val FILE_ID = "0123456789abcdef0123456789abcdef01234567"

        fun stateMachineJson(id: String): String = """
            {
              "state_machine_id": "$id",
              "tracks": [{
                "metadata": {
                  "uri": "$TRACK_URI",
                  "name": "Track 1",
                  "duration": 200000,
                  "authors": [{"name": "Artist"}],
                  "images": []
                },
                "manifest": {
                  "file_ids_mp4": [{
                    "bitrate": 128000,
                    "file_id": "$FILE_ID",
                    "format": "10",
                    "audio_quality": "HIGH"
                  }]
                }
              }],
              "states": [{
                "state_id": "st-1",
                "track": 0,
                "transitions": {}
              }]
            }
        """.trimIndent()

        fun replaceStateFrame(): String = """
            {
              "type": "message",
              "uri": "hm://track-playback/v1/command",
              "headers": {},
              "payloads": [{
                "type": "replace_state",
                "command_id": "cmd-play",
                "state_ref": {"state_index": 0, "paused": false},
                "seek_to": 0,
                "state_machine": ${stateMachineJson("sm-dealer")}
              }]
            }
        """.trimIndent()
    }
}
