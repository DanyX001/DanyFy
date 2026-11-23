// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a listen mirror reads from the engine when it plays an unheard copy of an extension's song
 * (EngineSyncPage): the smallest file, the device's state as another Connect client sees it, and
 * another device taking playback.
 */
class MirrorModeTest {
    private fun file(id: Char, format: Int, bitrate: Int) =
        ManifestFile(bitrate = bitrate, fileId = id.toString().repeat(40), format = format.toString())

    private val track = SmTrack(
        TrackMetadata("spotify:track:x"),
        manifest = mapOf("file_ids_mp4" to listOf(file('a', 10, 128_000), file('b', 12, 64_000), file('c', 11, 256_000))),
    )

    @Test
    fun lowest_takes_the_smallest_file_the_account_may_play() {
        val filter = (1L shl 10) or (1L shl 12)
        assertEquals(10, Resolver.chooseFile(track, filter)!!.format)
        assertEquals(12, Resolver.chooseFile(track, filter, lowest = true)!!.format)
        // Format 11 is the smallest of all, but not allowed by the account's filter.
        val all = SmTrack(track.metadata, mapOf("file_ids_mp4" to track.manifest["file_ids_mp4"]!! + file('d', 11, 32_000)))
        assertEquals(12, Resolver.chooseFile(all, filter, lowest = true)!!.format)
    }

    @Test
    fun now_follows_load_play_pause_and_seek() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        assertNull(rig.engine.now.value)
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        val loading = rig.engine.now.value!!
        assertEquals(rig.trackUriOf(2), loading.uri)
        assertTrue(loading.buffering)
        assertFalse(loading.playing)

        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.engine.now.value!!.let { assertTrue(it.playing); assertFalse(it.buffering) }

        rig.env.now += 4_000
        rig.engine.handlePlayerEvent(PlayerEvent.Paused(4_000))
        rig.engine.now.value!!.let { assertFalse(it.playing); assertFalse(it.buffering); assertEquals(4_000, it.positionMs); assertEquals(rig.env.now, it.atMs) }
        rig.engine.handlePlayerEvent(PlayerEvent.Seeked(60_000))
        assertEquals(60_000, rig.engine.now.value!!.positionMs)
        rig.engine.handlePlayerEvent(PlayerEvent.Buffering(60_000))
        assertTrue(rig.engine.now.value!!.buffering)
    }

    @Test
    fun another_device_taking_playback_is_seen_at_once() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        assertFalse(rig.engine.playsElsewhere.value)
        rig.engine.handleDealerFrame(rig.clearState())
        assertTrue(rig.engine.playsElsewhere.value)
        assertNull(rig.engine.now.value)
        // Playback back here: a state again.
        rig.engine.handleDealerFrame(rig.replaceState(3, paused = false, seekTo = 0))
        assertFalse(rig.engine.playsElsewhere.value)
        assertEquals(rig.trackUriOf(3), rig.engine.now.value!!.uri)
    }

    @Test
    fun the_cluster_names_the_devices_and_what_the_active_one_plays() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val frame = """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"content-type":"application/json"},"payloads":[{"cluster":{
            "timestamp":"1700000000500","active_device_id":"phone1",
            "player_state":{"timestamp":"1700000000000","context_uri":"spotify:playlist:p","track":{"uri":"spotify:track:t","uid":"u"},
              "position_as_of_timestamp":"61234","duration":"200000","is_playing":true,"is_paused":true},
            "devices":{"phone1":{"name":"Phone of Sam","device_type":"SMARTPHONE","volume":65535},
                       "web1":{"name":"Web Player (Chrome)","device_type":"COMPUTER"}}},
            "update_reason":"DEVICE_STATE_CHANGED","devices_that_changed":["phone1"]}]}"""
        rig.engine.handleDealerFrame(frame)
        val c = rig.engine.cluster.value!!
        assertEquals("phone1", c.activeDeviceId)
        assertEquals(listOf(ClusterDevice("phone1", "Phone of Sam", "SMARTPHONE", volume = 1.0), ClusterDevice("web1", "Web Player (Chrome)", "COMPUTER")), c.devices)
        assertEquals("spotify:track:t", c.trackUri)
        assertEquals("spotify:playlist:p", c.contextUri)
        // is_playing stays true while paused.
        assertTrue(c.paused)
        assertEquals(61_234, c.positionMs)
        assertEquals(1_700_000_000_000, c.timestampMs)
        assertEquals(200_000, c.durationMs)
        // A compressed payload (a string) leaves the cluster as it was.
        rig.engine.handleDealerFrame("""{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"Transfer-Encoding":"gzip"},"payloads":["H4sI"]}""")
        assertEquals(c, rig.engine.cluster.value)
    }

    /**
     * Log seen: the mirror played its song, sent its seek 100 ms later, before the engine's
     * `before_track_load` of that song was answered, and the server put the account's previous
     * song back at the seek's position. The seek now waits for the load's report.
     */
    @Test
    fun a_seek_right_after_a_play_waits_for_the_load_to_be_reported() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 30_000)
        // The mirror's play lands: the server is slow to answer the new song's report.
        val gate = CompletableDeferred<Unit>()
        rig.http.gates["before_track_load"] = gate
        val since = rig.engine.clockMs()
        launch { rig.engine.handleDealerFrame(rig.replaceState(3, paused = false, seekTo = 0)) }
        var loaded = false
        val seek = launch { loaded = rig.engine.awaitLoaded(rig.trackUriOf(3), since, 6_000); if (loaded) rig.engine.seekTo(12_000) }
        runCurrent()
        assertEquals(rig.trackUriOf(3), rig.engine.now.value!!.uri)
        assertTrue(rig.http.requests.none { it.method == "POST" && "/player/command/" in it.url }, "no seek before the report is answered")
        gate.complete(Unit)
        seek.join()
        assertTrue(loaded)
        val report = rig.http.answered.indexOfLast { it.method == "PUT" && it.url.endsWith("/state") && rig.http.json(it)["debug_source"]!!.jsonPrimitive.content == "before_track_load" }
        val sent = rig.http.requests.indexOfFirst { it.method == "POST" && "/player/command/" in it.url && "seek_to" in it.body!!.decodeToString() }
        assertTrue(sent > rig.http.requests.indexOf(rig.http.answered[report]), "the seek goes after the new song's report")
        // A play that never loads: nothing waits for good, no seek.
        assertFalse(rig.engine.awaitLoaded("spotify:track:none", rig.engine.clockMs(), 1_000))
    }

    @Test
    fun mute_reaches_the_player() = runTest {
        val rig = Rig(backgroundScope)
        rig.engine.setMuted(true)
        assertEquals("muted true", rig.player.calls.last())
    }

    @Test
    fun remote_command_targets_active_device_and_gzipped_cluster_updates_cluster() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val clusterJson = """{"cluster":{"timestamp":"1700000002000","active_device_id":"tv-1",""" +
            """"player_state":{"timestamp":"1700000001500","context_uri":"spotify:album:a","track":{"uri":"spotify:track:t2","uid":"u2"},""" +
            """"position_as_of_timestamp":"42000","duration":"190000","is_playing":true,"is_paused":false},""" +
            """"devices":{"tv-1":{"name":"Living Room TV","device_type":"TV","volume":32768}}}}"""
        val gzippedBytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { gz -> gz.write(clusterJson.encodeToByteArray()) }
            out.toByteArray()
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(gzippedBytes)
        rig.engine.handleDealerFrame(
            """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"Transfer-Encoding":"gzip"},"payloads":["$b64"]}""",
        )
        val c = rig.engine.cluster.value!!
        assertEquals("tv-1", c.activeDeviceId)
        assertEquals("spotify:track:t2", c.trackUri)
        assertFalse(c.paused)
        assertEquals(42_000L, c.positionMs)

        // Remote commands go to /to/<targetDeviceId>, never self /to/<deviceId>
        assertFalse(rig.engine.remoteCommand("pause", targetDeviceId = rig.engine.deviceId))
        assertTrue(rig.engine.remoteCommand("pause"))
        val sent = rig.http.requests.last { it.method == "POST" && "/player/command/" in it.url }
        assertTrue(sent.url.endsWith("/player/command/from/${rig.engine.deviceId}/to/tv-1"))
        assertEquals("pause", rig.http.json(sent)["command"]!!.jsonObject["endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun remote_volume_puts_connect_volume_and_clamps_level() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val clusterJson = """{"cluster":{"active_device_id":"tv-1","devices":{"tv-1":{"name":"TV","device_type":"TV","volume":32768}}}}"""
        val gzippedBytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { gz -> gz.write(clusterJson.encodeToByteArray()) }
            out.toByteArray()
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(gzippedBytes)
        rig.engine.handleDealerFrame(
            """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"Transfer-Encoding":"gzip"},"payloads":["$b64"]}""",
        )

        assertFalse(rig.engine.remoteVolume(0.5, targetDeviceId = rig.engine.deviceId))
        assertTrue(rig.engine.remoteVolume(0.5))
        val volReq = rig.http.requests.last { it.method == "PUT" && "/connect/volume/" in it.url }
        assertTrue(volReq.url.endsWith("/connect/volume/from/${rig.engine.deviceId}/to/tv-1"))
        assertEquals(32767, rig.http.json(volReq)["volume"]!!.jsonPrimitive.content.toInt())

        assertTrue(rig.engine.remoteVolume(1.5))
        val maxReq = rig.http.requests.last { it.method == "PUT" && "/connect/volume/" in it.url }
        assertEquals(65535, rig.http.json(maxReq)["volume"]!!.jsonPrimitive.content.toInt())

        assertTrue(rig.engine.remoteVolume(-0.2))
        val minReq = rig.http.requests.last { it.method == "PUT" && "/connect/volume/" in it.url }
        assertEquals(0, rig.http.json(minReq)["volume"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun content_encoding_gzip_and_plain_json_string_cluster_payloads_decode() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val clusterJson = """{"cluster":{"active_device_id":"tv-2","devices":{"tv-2":{"name":"Bedroom TV","device_type":"TV","volume":16384}}}}"""
        val gzippedBytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { gz -> gz.write(clusterJson.encodeToByteArray()) }
            out.toByteArray()
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(gzippedBytes)
        rig.engine.handleDealerFrame(
            """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"Content-Encoding":"gzip"},"payloads":["$b64"]}""",
        )
        assertEquals("tv-2", rig.engine.cluster.value?.activeDeviceId)

        val plainCluster = """{"cluster":{"active_device_id":"tv-3","devices":{"tv-3":{"name":"Kitchen TV","device_type":"TV"}}}}"""
        val escaped = kotlinx.serialization.json.JsonPrimitive(plainCluster).toString()
        rig.engine.handleDealerFrame(
            """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"content-type":"application/json"},"payloads":[$escaped]}""",
        )
        assertEquals("tv-3", rig.engine.cluster.value?.activeDeviceId)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun started_playing_uses_live_position_after_timeline_flush() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_500)
        // Advance naturally to track 3 so flushOnFirstPlay runs melody media_playback_event_v2 while playing starts.
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(150_226))
        val flushEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val flushRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.http.onRequest = { req ->
            if ("/melody/v1/msg/batch" in req.url && "media_playback_event_v2" in (req.body?.decodeToString().orEmpty())) {
                flushEntered.complete(Unit)
                flushRelease.await()
            }
        }
        val firstPlay = launch { rig.engine.handlePlayerEvent(PlayerEvent.Playing(0)) }
        flushEntered.await()
        rig.env.now += 500
        rig.engine.handlePlayerEvent(PlayerEvent.Position(500))
        rig.env.now += 600
        val startedReport = launch { rig.engine.handlePlayerEvent(PlayerEvent.Position(1_100)) }
        runCurrent()
        // Telemetry flush stalls 1.5 s while playback advances; started_playing must sample the live position.
        rig.env.now += 1_500L
        rig.http.onRequest = null
        flushRelease.complete(Unit)
        firstPlay.join()
        startedReport.join()
        val started = rig.http.statePuts().last { it["debug_source"]?.jsonPrimitive?.content == "started_playing" }
        val reportedPos = started["sub_state"]!!.jsonObject["position"]!!.jsonPrimitive.content.toLong()
        assertEquals(2_600L, reportedPos)
    }

    @Test
    fun mid_track_buffering_lag_sends_modify_current_state_with_real_position() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.env.now += 1_000L
        rig.engine.handlePlayerEvent(PlayerEvent.Position(1_000L))
        runCurrent()
        // Smooth start reports before_track_load + started_playing, no spurious resync.
        assertFalse(rig.http.statePuts().any { it["debug_source"]?.jsonPrimitive?.content == "modify_current_state" })

        // Player stalls buffering for 4.5s while Connect clock kept ticking ahead.
        rig.engine.handlePlayerEvent(PlayerEvent.Buffering(1_000L))
        rig.env.now += 4_500L
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(1_000L))
        rig.engine.handlePlayerEvent(PlayerEvent.Position(1_000L))
        runCurrent()

        val resync = rig.http.statePuts().last { it["debug_source"]?.jsonPrimitive?.content == "modify_current_state" }
        assertEquals(1_000L, resync["sub_state"]!!.jsonObject["position"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun remote_play_commands_target_device_without_transferring_to_self() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val clusterJson = """{"cluster":{"active_device_id":"tv-1","devices":{"tv-1":{"name":"Living Room TV","device_type":"TV","volume":32768}}}}"""
        val escaped = kotlinx.serialization.json.JsonPrimitive(clusterJson).toString()
        rig.engine.handleDealerFrame(
            """{"type":"message","uri":"hm://connect-state/v1/cluster","headers":{"content-type":"application/json"},"payloads":[$escaped]}""",
        )

        val beforeCount = rig.http.requests.size
        assertFalse(rig.engine.remotePlay("spotify:album:a", trackUri = "spotify:track:t3", targetDeviceId = rig.engine.deviceId))
        assertEquals(beforeCount, rig.http.requests.size)

        assertTrue(
            rig.engine.remotePlay(
                contextUri = "spotify:album:a",
                trackUri = "spotify:track:t3",
                trackIndex = 2,
                shuffle = false,
            ),
        )
        val sent = rig.http.requests.last { it.method == "POST" && "/player/command/" in it.url }
        assertTrue(sent.url.endsWith("/player/command/from/${rig.engine.deviceId}/to/tv-1"), sent.url)
        assertFalse(rig.http.requests.drop(beforeCount).any { "/transfer/" in it.url || it.url.endsWith("/to/${rig.engine.deviceId}") })
        val cmd = rig.http.json(sent)["command"]!!.jsonObject
        assertEquals("play", cmd["endpoint"]!!.jsonPrimitive.content)
        assertEquals("spotify:album:a", cmd["context"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        val options = cmd["options"]!!.jsonObject
        assertEquals("spotify:track:t3", options["skip_to"]!!.jsonObject["track_uri"]!!.jsonPrimitive.content)
        assertEquals(2, options["skip_to"]!!.jsonObject["track_index"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", options["player_options_override"]!!.jsonObject["shuffling_context"]!!.jsonPrimitive.content)
    }
}
