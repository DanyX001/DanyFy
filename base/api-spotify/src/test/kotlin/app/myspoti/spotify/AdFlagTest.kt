package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdSchedule
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The page's state push that ends an ad break never arrives (dropped on the way): the screen must
 * not stay on "Advertisement" through the song that plays.
 */
class AdFlagTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        /** What the page answers to "player.state": the song it really plays. */
        @Volatile var pageState = "null"
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += m.groupValues[2] to m.groupValues[3]
            val value = if (m.groupValues[2] == "player.state") pageState else "null"
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
        suspend fun post(json: String) = messages.emit(json)
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdSchedule.clear()
        app.myspoti.spotify.bridge.PageDevice.clearIdsForTest()
        scope = time.scope
        host = FakeHost()
        bridge = Bridge(host, scope, time::now)
        player = SpotifyPlayer(bridge, scope)
    }

    @AfterEach fun down() {
        AdSchedule.clear()
        app.myspoti.spotify.bridge.PageDevice.clearIdsForTest()
        scope.cancel()
    }

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun stateOf(item: String) =
        """{"track":$item,"contextUri":"spotify:playlist:C","next":null,"isPaused":false,"isBuffering":false,
            |"positionMs":0,"timestamp":${time.now()},"restrictions":{}}""".trimMargin()

    private val ad = """{"kind":"ad","uri":"spotify:ad:1","name":"ad","durationMs":30000}"""

    private suspend fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = time.waitFor(what, ms, cond)

    @Test fun `a read that finds nothing playing does not end the break`() = time.test {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("ad flag") { player.adPlaying.value }
        // Between the ad and the next song the page can say "nothing plays": the ad stays muted.
        host.pageState = """{"track":null,"contextUri":null,"next":null,"isPaused":true,"isBuffering":false,"positionMs":0,"timestamp":0,"restrictions":{}}"""
        delay(8_000)
        assertEquals(true, player.adPlaying.value, "the break is still on")
    }

    @Test fun `an ad break whose end was never pushed is cleared once the page plays a song`() = time.test {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("ad flag") { player.adPlaying.value }
        // The break ends and B plays, but that push is lost: only a read of the page says so.
        host.pageState = stateOf(track("B"))
        waitFor("ad flag cleared", ms = 15_000) { !player.adPlaying.value }
        assertEquals("spotify:track:B", player.state.value?.item?.uri)
    }

    @Test fun `local ad on phone toggles media quiet on during break and off afterwards`() = time.test {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("quiet on during local ad") {
            host.calls.any { it.first == "media.quiet" && "\"on\":true" in it.second }
        }
        assertTrue(player.adPlaying.value)
        host.post("""{"type":"state","state":${stateOf(track("B"))}}""")
        waitFor("quiet off after ad ends") {
            !player.adPlaying.value && host.calls.lastOrNull { it.first == "media.quiet" }?.second?.contains("\"on\":false") == true
        }
    }

    @Test fun `local ad bypass callback runs on phone ad and prepareReplayAfterAdReload clears ad flag`() = time.test {
        var calls = 0
        player.onLocalAdBreak = {
            calls++
            player.prepareReplayAfterAdReload("spotify:playlist:C", "spotify:track:Next")
            true
        }
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("local ad bypass invoked") { calls > 0 && !player.adPlaying.value }
        assertEquals(1, calls)
    }

    @Test fun `registered PageDevice prevents standby engine cluster from suppressing phone ad bypass`() = time.test {
        val standbyCluster = kotlinx.coroutines.flow.MutableStateFlow<app.myspoti.engine.Cluster?>(
            app.myspoti.engine.Cluster(
                activeDeviceId = "webview-phone-id",
                devices = listOf(app.myspoti.engine.ClusterDevice("webview-phone-id", "Phone", "COMPUTER", 1.0)),
                trackUri = "spotify:track:A",
                contextUri = "spotify:playlist:C",
                paused = false,
                positionMs = 0L,
                timestampMs = 0L,
                durationMs = 200_000L,
            ),
        )
        val standbyAudio = object : AccountAudio {
            override val on = true
            override val deviceId = "native-standby-id"
            override val holdsPlayback = false
            override val cluster = standbyCluster
            override suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean?) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun seek(positionMs: Long) {}
            override suspend fun next() {}
            override suspend fun previous() {}
            override suspend fun skipTo(contextUri: String?, trackUri: String, trackUid: String?, provider: String?, order: List<String>?, shuffle: Boolean?, steps: Int?) {}
        }
        app.myspoti.spotify.bridge.PageDevice.joined("webview-phone-id")
        player.audio = standbyAudio
        var bypassCount = 0
        player.onLocalAdBreak = {
            bypassCount++
            player.prepareReplayAfterAdReload("spotify:playlist:C", "spotify:track:Next")
            true
        }
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        assertEquals(false, standbyAudio.hasRemoteActive)
        assertEquals(false, player.playsElsewhere.value)
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("local ad bypass runs despite standby engine") { bypassCount > 0 && !player.adPlaying.value }
        assertEquals(1, bypassCount)
    }

    @Test fun `next jumps directly to show_next track when AdSchedule due matches active local song`() = time.test {
        player.onLocalAdBreak = { true }
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        waitFor("track A active") { player.state.value?.item?.uri == "spotify:track:A" }

        val smBody = """
            {"state_machine":{"state_machine_id":"m","tracks":[
              {"metadata":{"uri":"spotify:track:A"}},
              {"content_type":"AD","metadata":{"uri":"spotify:ad:ad1"}},
              {"metadata":{"uri":"spotify:track:B"}}
            ],"states":[
              {"state_id":"s0","track":0,"transitions":{"advance":{"state_index":1,"paused":false},"skip_next":{"state_index":1,"paused":false},"show_next":{"state_index":2,"paused":false}}},
              {"state_id":"s1","track":1,"transitions":{"advance":null,"skip_next":null,"show_next":null}},
              {"state_id":"s2","track":2,"transitions":{"advance":null,"skip_next":null,"show_next":null}}
            ],"attributes":{}},"updated_state_ref":{"state_index":0,"paused":false}}
        """.trimIndent()
        AdSchedule.record(smBody)
        assertEquals("spotify:track:B", AdSchedule.due.value?.nextUri)

        player.next()
        waitFor("jumped via player.play") { host.calls.any { it.first == "player.play" && "spotify:track:B" in it.second } }
        assertTrue(host.calls.none { it.first == "player.next" })
    }

    @Test fun `skipScheduledAd plays autoplay nextUri standalone instead of inside playlist context`() = time.test {
        player.onLocalAdBreak = { true }
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        waitFor("track A active") { player.state.value?.item?.uri == "spotify:track:A" }

        val smBody = """
            {"state_machine":{"state_machine_id":"m","tracks":[
              {"metadata":{"uri":"spotify:track:A"}},
              {"content_type":"AD","metadata":{"uri":"spotify:ad:ad1"}},
              {"metadata":{"uri":"spotify:track:AutoB"}}
            ],"states":[
              {"state_id":"s0","track":0,"transitions":{"advance":{"state_index":1,"paused":false},"skip_next":{"state_index":1,"paused":false},"show_next":{"state_index":2,"paused":false}}},
              {"state_id":"s1","track":1,"transitions":{"advance":null,"skip_next":null,"show_next":null}},
              {"state_id":"s2","track":2,"track_provider":"autoplay","transitions":{"advance":null,"skip_next":null,"show_next":null}}
            ],"attributes":{}},"updated_state_ref":{"state_index":0,"paused":false}}
        """.trimIndent()
        AdSchedule.record(smBody)
        assertEquals("autoplay", AdSchedule.due.value?.nextProvider)

        player.next()
        waitFor("jumped via standalone player.play") { host.calls.any { it.first == "player.play" && "spotify:track:AutoB" in it.second } }
        val playArgs = host.calls.last { it.first == "player.play" }.second
        assertTrue("spotify:track:AutoB" in playArgs)
        assertTrue("spotify:playlist:C" !in playArgs, "expected autoplay track standalone, got $playArgs")
    }

    @Test fun `watchStalls does not invoke onLocalAdBreak while native engine isRecoveringAd`() = time.test {
        val recoveringAudio = object : AccountAudio {
            override val on = true
            override val deviceId = "native-recovering-id"
            override val holdsPlayback = false
            override val isRecoveringAd = true
            override suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean?) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun seek(positionMs: Long) {}
            override suspend fun next() {}
            override suspend fun previous() {}
            override suspend fun skipTo(contextUri: String?, trackUri: String, trackUid: String?, provider: String?, order: List<String>?, shuffle: Boolean?, steps: Int?) {}
        }
        player.audio = recoveringAudio
        var bypassCount = 0
        player.onLocalAdBreak = { bypassCount++; true }
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        host.post("""{"type":"state","state":${stateOf(track("A"))}}""")
        host.post("""{"type":"state","state":${stateOf(ad)}}""")
        waitFor("ad flag") { player.adPlaying.value }
        delay(6_000)
        assertEquals(0, bypassCount)
    }
}
