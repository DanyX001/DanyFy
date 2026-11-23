package app.myspoti.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The playback session state machine against the sequences measured in the captures: which
 * state PUTs (debug_source) and melody messages go out, in which order, and only when the
 * player reported real playback.
 */
class SessionTest {
    private fun kotlinx.serialization.json.JsonObject.str(vararg path: String): String {
        var e: kotlinx.serialization.json.JsonElement = this
        path.forEach { e = e.jsonObject[it]!! }
        return e.jsonPrimitive.content
    }

    @Test
    fun play_to_the_end_then_advance() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        assertEquals(listOf("apresolve", "devices", "hobs"), rig.http.requests.map {
            when { "apresolve" in it.url -> "apresolve"; it.url.endsWith("/devices") -> "devices"; else -> "hobs" }
        })

        // Server push after our play command: state 2, playing, from 0.
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        assertEquals(listOf("before_track_load"), rig.http.debugSources())
        assertEquals(listOf("load ${rig.trackUriOf(2)} @0 play=true"), rig.player.calls)
        val btl = rig.http.statePuts()[0]
        assertEquals("2", btl.str("seq_num"))
        assertEquals("ChTdealer2false0", btl.str("state_ref", "state_machine_id"))
        assertEquals(rig.stateId(2), btl.str("state_ref", "state_id"))
        assertEquals("0", btl.str("sub_state", "playback_speed"))
        assertEquals(listOf(listOf("jssdk_playback_start")), rig.http.melodyTypes())

        // Nothing more is reported until the player says audio plays.
        rig.env.now += 5_000
        assertEquals(1, rig.http.statePuts().size)

        rig.player.licence!!.licence(Fixtures.bytes("licence-challenge.bin"))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_000)
        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        val started = rig.http.statePuts()[1]
        assertEquals("1", started.str("sub_state", "playback_speed"))
        assertEquals("1000", started.str("previous_position"))
        assertEquals("153889", started.str("sub_state", "duration"))
        // The answer of the first PUT is the machine the next one refers to.
        assertEquals("ChTresponse1", started.str("state_ref", "state_machine_id"))
        // Next track preloaded once playing.
        assertEquals("preload ${rig.trackUriOf(rig.advanceOf(2))}", rig.player.calls[1])

        rig.playFor(1_000, 29_500)
        assertEquals(2, rig.http.statePuts().size)
        rig.playFor(29_500, 30_000)
        assertEquals(listOf("before_track_load", "started_playing", "played_threshold_reached"), rig.http.debugSources())

        rig.playFor(30_000, 153_500)
        val resolvesBefore = rig.http.count("/storage-resolve/")
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        assertEquals(
            listOf("before_track_load", "started_playing", "played_threshold_reached", "track_data_finalized", "before_track_load"),
            rig.http.debugSources(),
        )
        val puts = rig.http.statePuts()
        assertEquals((2..6).map { it.toString() }, puts.map { it.str("seq_num") })
        val fin = puts[3]
        assertEquals(rig.stateId(2), fin.str("state_ref", "state_id"))
        assertEquals("153889", fin.str("playback_stats", "ms_played_per_audio_format", "mp4a.40.2;128000"))
        val next = puts[4]
        assertEquals(rig.stateId(rig.advanceOf(2)), next.str("state_ref", "state_id"))
        assertEquals("false", next.str("state_ref", "paused"))
        assertEquals("0", next.str("sub_state", "position"))
        // previous_position is the last position the player reported, not the end (real1: 153699 of 153889).
        assertEquals("153500", next.str("previous_position"))
        assertEquals(fin.str("state_ref", "state_machine_id"), next.str("state_ref", "state_machine_id"))
        // Melody at the end: verification + stats in one batch (real1 order), then the next start.
        val types = rig.http.melodyTypes()
        assertTrue(types.contains(listOf("track_stream_verification", "jssdk_playback_stats")))
        assertEquals(listOf("jssdk_playback_start"), types.last())
        // 120 s of play flushed one media_playback_event_v2 timeline.
        assertTrue(types.contains(listOf("media_playback_event_v2")))
        // The preloaded file is loaded without resolving it again.
        assertEquals(resolvesBefore, rig.http.count("/storage-resolve/"))
        assertSame(rig.player.preloaded.single(), rig.player.loaded)
    }

    @Test
    fun natural_end_matches_the_firefox_end_of_track_capture() = runTest {
        // end-of-track (Firefox 159, 2026-11-10): paused session restored at 120752, resumed, played
        // to the natural end of a 150.2266 s file (the fixture seektable), then the next state.
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.transfer()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 120_752))
        rig.engine.resume()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(150_274))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(120_752))
        rig.playFor(120_752, 150_062)
        val before = rig.http.requests.size
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(150_274))

        val fxFin = Fixtures.json("state-track-data-finalized-firefox.json")
        val fxNext = Fixtures.json("state-before-track-load-after-end.json")
        val fxMelody = Fixtures.json("melody-end-of-track-firefox.json")["messages"]!!.jsonArray.map { it.jsonObject }

        // Order as captured: melody verification + stats, finalized PUT, next before_track_load.
        val after = rig.http.requests.drop(before)
        assertTrue("/melody/v1/msg/batch" in after[0].url)
        assertEquals(listOf("track_stream_verification", "jssdk_playback_stats"),
            rig.http.json(after[0])["messages"]!!.jsonArray.map { it.jsonObject.str("type") })
        val fin = rig.http.json(after[1])
        val next = rig.http.json(after[2])
        assertEquals("track_data_finalized", fin.str("debug_source"))
        assertEquals("before_track_load", next.str("debug_source"))
        assertEquals(keyPaths(fxFin), keyPaths(fin))
        assertEquals(keyPaths(fxNext), keyPaths(next))

        // The capture's numbers: last reported position in the PUT, the file's duration, played time to the end.
        for (path in listOf(arrayOf("sub_state", "position"), arrayOf("previous_position"), arrayOf("sub_state", "duration"),
            arrayOf("sub_state", "playback_speed"), arrayOf("state_ref", "paused"),
            arrayOf("playback_stats", "ms_total_est"), arrayOf("playback_stats", "start_offset_ms"),
            arrayOf("playback_stats", "ms_played_per_audio_format", "mp4a.40.2;128000"))) {
            assertEquals(fxFin.str(*path), fin.str(*path), path.joinToString("."))
        }
        // Sent together: same machine as the finalized PUT, not the machine of its answer.
        assertEquals(fin.str("state_ref", "state_machine_id"), next.str("state_ref", "state_machine_id"))
        assertEquals(rig.stateId(rig.advanceOf(2)), next.str("state_ref", "state_id"))
        for (path in listOf(arrayOf("sub_state", "position"), arrayOf("sub_state", "playback_speed"), arrayOf("previous_position"), arrayOf("state_ref", "paused"))) {
            assertEquals(fxNext.str(*path), next.str(*path), path.joinToString("."))
        }

        val melody = rig.http.json(after[0])["messages"]!!.jsonArray.map { it.jsonObject }
        for ((i, keys) in listOf(
            0 to listOf("ms_played", "ms_nominal_played", "sequence_id", "playback_service"),
            1 to listOf("ms_played", "ms_file_duration", "ms_actual_duration", "ms_start_position", "ms_end_position",
                "reason_start", "reason_end", "initially_paused", "audio_format", "key_system"),
        )) {
            assertEquals(keyPaths(fxMelody[i]), keyPaths(melody[i]))
            keys.forEach { k -> assertEquals(fxMelody[i].str("message", k), melody[i].str("message", k), k) }
        }
        assertEquals(rig.stateId(rig.advanceOf(2)), melody[0].str("message", "next_playback_id"))

        // The next track's first audio flushes the timeline (+0.45 s in the capture) ...
        assertFalse(rig.http.melodyTypes().contains(listOf("media_playback_event_v2")))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        assertEquals(listOf("media_playback_event_v2"), rig.http.melodyTypes().last())
        // ... and the next report refers to the answer of before_track_load.
        rig.playFor(0, 1_000)
        val started = rig.http.statePuts().last()
        assertEquals("started_playing", started.str("debug_source"))
        assertTrue(started.str("state_ref", "state_machine_id") != next.str("state_ref", "state_machine_id"))
    }

    @Test
    fun loaded_paused_then_resumed_reports_started_playing_not_resume() = runTest {
        // The play-bodies sequence: transfer -> paused load at 60271 -> resume -> started_playing.
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.transfer()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true, seekTo = 60_271))
        val btl = rig.http.statePuts().single()
        assertEquals("true", btl.str("state_ref", "paused"))
        assertEquals("60271", btl.str("sub_state", "position"))
        assertTrue(rig.http.melodyTypes().contains(listOf("jssdk_connect_command")))
        assertEquals("load ${rig.trackUriOf(2)} @60271 play=false", rig.player.calls.single())

        rig.engine.resume()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        assertEquals("resume", rig.player.calls.last())
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(60_271))
        rig.playFor(60_271, 61_337)
        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        rig.playFor(61_337, 90_270)
        assertEquals(listOf("before_track_load", "started_playing"), rig.http.debugSources())
        rig.playFor(90_270, 90_271)
        // 30 s of audio from the start position (90514 in the capture, reported on a 200 ms tick).
        assertEquals(listOf("before_track_load", "started_playing", "played_threshold_reached"), rig.http.debugSources())
        assertEquals("90271", rig.http.statePuts().last().str("sub_state", "position"))
    }

    @Test
    fun pause_and_resume_are_reported_when_the_player_did_them() = runTest {
        val rig = playing(this)
        rig.engine.pause()
        assertTrue(rig.http.requests.last().url.contains("/player/command/from/${rig.engine.deviceId}/to/${rig.engine.deviceId}"))
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true))
        assertEquals("pause", rig.player.calls.last())
        assertFalse(rig.http.debugSources().contains("pause")) // not before the player confirms
        rig.engine.handlePlayerEvent(PlayerEvent.Paused(10_000))
        val pause = rig.http.statePuts().last()
        assertEquals("pause", pause.str("debug_source"))
        assertEquals("true", pause.str("state_ref", "paused"))
        assertEquals("0", pause.str("sub_state", "playback_speed"))
        assertEquals("10000", pause.str("sub_state", "position"))

        // Time paused is not played time.
        rig.env.now += 60_000
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        assertEquals("resume", rig.player.calls.last())
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(10_000))
        val resume = rig.http.statePuts().last()
        assertEquals("resume", resume.str("debug_source"))
        assertEquals("1", resume.str("sub_state", "playback_speed"))
        rig.playFor(10_000, 29_500)
        assertFalse(rig.http.debugSources().contains("played_threshold_reached"))
        rig.playFor(29_500, 30_000)
        assertTrue(rig.http.debugSources().contains("played_threshold_reached"))
    }

    @Test
    fun seek_reports_old_and_new_position() = runTest {
        val rig = playing(this)
        rig.engine.seekTo(121_190)
        val cmd = rig.http.json(rig.http.requests.last())["command"]!!.jsonObject
        assertEquals("seek_to", cmd.str("endpoint"))
        assertEquals("121190.0", cmd.str("value"))
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 121_190))
        assertEquals("seek 121190", rig.player.calls.last())
        rig.engine.handlePlayerEvent(PlayerEvent.Seeked(121_190))
        val seek = rig.http.statePuts().last()
        assertEquals("seek", seek.str("debug_source"))
        assertEquals("121190", seek.str("sub_state", "position"))
        assertEquals("10000", seek.str("previous_position"))
        // A jump is not listening time: still under the 30 s threshold after the seek.
        rig.playFor(121_190, 125_000)
        assertFalse(rig.http.debugSources().contains("played_threshold_reached"))
    }

    @Test
    fun previous_seeks_to_zero_past_three_seconds() = runTest {
        val rig = playing(this)
        rig.engine.previous()
        val cmd = rig.http.json(rig.http.requests.last())["command"]!!.jsonObject
        assertEquals("seek_to", cmd.str("endpoint"))
        assertEquals("0.0", cmd.str("value"))
    }

    @Test
    fun skip_to_the_preloaded_next_track() = runTest {
        val rig = playing(this)
        val resolves = rig.http.count("/storage-resolve/")
        rig.engine.skipNext()
        val skipTo = rig.states[2].jsonObject["transitions"]!!.jsonObject["skip_next"]!!.jsonObject["state_index"]!!.jsonPrimitive.content.toInt()
        rig.engine.handleDealerFrame(rig.replaceState(skipTo, paused = false))
        assertEquals(listOf("before_track_load", "started_playing", "track_data_finalized", "before_track_load"), rig.http.debugSources())
        val fin = rig.http.statePuts()[2]
        assertEquals(rig.stateId(2), fin.str("state_ref", "state_id"))
        val stats = rig.http.requests.filter { "/melody/v1/msg/batch" in it.url }.map { rig.http.json(it) }
            .flatMap { it["messages"]!!.let { m -> (m as kotlinx.serialization.json.JsonArray).map { x -> x.jsonObject } } }
            .first { it.str("type") == "jssdk_playback_stats" }["message"]!!.jsonObject
        assertEquals("remote", stats.str("reason_end"))
        assertEquals("10000", stats.str("ms_played"))
        val verification = rig.http.requests.filter { "/melody/v1/msg/batch" in it.url }.map { rig.http.json(it) }
            .flatMap { (it["messages"] as kotlinx.serialization.json.JsonArray).map { x -> x.jsonObject } }
            .first { it.str("type") == "track_stream_verification" }["message"]!!.jsonObject
        assertEquals(rig.stateId(skipTo), verification.str("next_playback_id"))
        assertEquals(rig.stateId(skipTo), rig.http.statePuts()[3].str("state_ref", "state_id"))
        // No network round trip for the new track's media: it was preloaded.
        assertEquals(resolves, rig.http.count("/storage-resolve/"))
        assertSame(rig.player.preloaded.single(), rig.player.loaded)
        assertTrue(rig.player.calls.last().startsWith("load ${rig.trackUriOf(skipTo)} @0"))
    }

    @Test
    fun a_401_refreshes_the_token_once_then_surfaces() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.http.failNext("/storage-resolve/", 401)
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        assertEquals(1, rig.tokens.refreshes)
        val resolves = rig.http.requests.filter { "/storage-resolve/" in it.url }
        assertEquals(listOf("Bearer test-token-1", "Bearer test-token-2"), resolves.map { it.headers["authorization"] })
        assertEquals(listOf("before_track_load"), rig.http.debugSources())

        // Twice in a row: the session is gone, the caller is told.
        rig.http.failNext("/state", 401, times = 2)
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        val e = runCatching { rig.playFor(0, 1_000) }.exceptionOrNull()
        assertTrue(e is EngineException.Unauthorized, "$e")
        assertEquals(2, rig.tokens.refreshes) // refreshed once more before giving up
    }

    @Test
    fun a_429_waits_retry_after_then_surfaces() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()
        rig.http.failNext("/storage-resolve/", 429, mapOf("retry-after" to "2"))
        val job = launch { rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0)) }
        runCurrent()
        assertEquals(1, rig.http.count("/storage-resolve/"))
        assertTrue(rig.http.debugSources().isEmpty())
        advanceTimeBy(1_999); runCurrent()
        assertEquals(1, rig.http.count("/storage-resolve/"))
        advanceTimeBy(2); runCurrent()
        job.join()
        assertEquals(2, rig.http.count("/storage-resolve/"))
        assertEquals(listOf("before_track_load"), rig.http.debugSources())
        assertTrue(events.any { it is EngineEvent.RateLimited && it.retryAfterMs == 2_000L && it.attempt == 1 })

        // Without Retry-After: 1 s, 2 s, 4 s, then the error reaches the caller.
        rig.http.failNext("/melody/v1/msg/batch", 429, times = 4)
        var failure: Throwable? = null
        val failing = launch { failure = runCatching { rig.engine.handlePlayerEvent(PlayerEvent.Ended(0)) }.exceptionOrNull() }
        advanceTimeBy(7_001); runCurrent()
        failing.join()
        assertTrue(failure is EngineException.RateLimited, "$failure")
        assertEquals(listOf(1_000L, 2_000L, 4_000L), events.filterIsInstance<EngineEvent.RateLimited>().drop(1).map { it.retryAfterMs })
    }

    @Test
    fun an_ad_is_neither_fetched_nor_reported() = runTest {
        val rig = Rig(backgroundScope)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()
        val before = rig.http.requests.size
        val frame = rig.replaceState(2, paused = false, seekTo = 0).replace("\"content_type\":\"TRACK\"", "\"content_type\":\"AD\"")
        rig.engine.handleDealerFrame(frame)
        runCurrent()
        // Nothing is asked for the ad and nothing reported of it: the one request is the command that passes it.
        assertEquals(before + 1, rig.http.requests.size)
        val sent = rig.http.requests.last()
        assertTrue("/connect-state/v1/player/command/" in sent.url)
        assertTrue("skip_next" in sent.body!!.decodeToString())
        assertEquals(listOf("stop"), rig.player.calls)
        assertTrue(events.any { it is EngineEvent.AdBreak })
    }

    @Test
    fun clear_state_finalizes_and_reports_state_clear() = runTest {
        val rig = playing(this)
        rig.engine.handleDealerFrame(rig.clearState())
        assertEquals(listOf("before_track_load", "started_playing", "track_data_finalized", "state_clear"), rig.http.debugSources())
        assertEquals("stop", rig.player.calls.last())
    }

    @Test
    fun rapid_dealer_replace_state_cancels_superseded_retry_and_loads_latest() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.sockets.socket.incoming.trySend(rig.replaceState(2, paused = false, seekTo = 0))
        runCurrent()
        assertEquals(listOf("load ${rig.trackUriOf(2)} @0 play=true"), rig.player.calls)
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 5_000)

        // State 8 hits a 10 s 429 retry wait; another replace_state on the socket cancels it at once.
        rig.http.failNext("/storage-resolve/", 429, mapOf("retry-after" to "10"))
        rig.sockets.socket.incoming.trySend(rig.replaceState(8, paused = false, seekTo = 0))
        runCurrent()
        assertFalse(rig.player.calls.any { it.contains(rig.trackUriOf(8)) })

        rig.sockets.socket.incoming.trySend(rig.replaceState(4, paused = false, seekTo = 1_500))
        advanceTimeBy(100); runCurrent()
        assertEquals("load ${rig.trackUriOf(4)} @1500 play=true", rig.player.calls.last())
        assertEquals(1, rig.http.debugSources().count { it == "track_data_finalized" })
        assertEquals("before_track_load", rig.http.debugSources().last())
    }

    @Test
    fun pause_and_resume_convergence_handles_local_pause_and_playing_state() = runTest {
        val rig = playing(this)
        // Player paused locally while wantPaused stayed false: server replace_state(paused=false) must resume.
        rig.engine.handlePlayerEvent(PlayerEvent.Paused(10_000))
        val beforeResume = rig.player.calls.size
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false))
        assertEquals(listOf("resume"), rig.player.calls.drop(beforeResume))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(10_000))
        assertEquals("resume", rig.http.debugSources().last())

        // Player still playing after wantPaused=true: duplicate replace_state(paused=true) must pause again.
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(10_500))
        val beforePause = rig.player.calls.size
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = true))
        assertEquals(listOf("pause"), rig.player.calls.drop(beforePause))
        rig.engine.handlePlayerEvent(PlayerEvent.Paused(10_500))
        assertEquals("pause", rig.http.debugSources().last())
    }

    @Test
    fun skip_to_routes_by_steps_context_and_provider() = runTest {
        val rig = playing(this)
        val beforeCmds = rig.http.count("/connect-state/v1/player/command/")

        rig.engine.skipTo("spotify:playlist:C", "spotify:track:X", provider = "context", steps = 1)
        rig.engine.skipTo("spotify:playlist:C", "spotify:track:Y", provider = "context", order = listOf("spotify:track:Y"), shuffle = false, steps = 3)
        rig.engine.skipTo("spotify:playlist:C", "spotify:track:Q2", provider = "queue", shuffle = true, steps = 2)

        val cmds = rig.http.requests.filter { "/connect-state/v1/player/command/" in it.url }.drop(beforeCmds)
            .map { rig.http.json(it)["command"]!!.jsonObject.str("endpoint") }
        assertEquals(listOf("skip_next", "play", "skip_next", "skip_next"), cmds)
    }

    @Test
    fun player_inputs_loop_survives_http_failure_and_keeps_processing_events() = runTest {
        val rig = Rig(backgroundScope, EngineConfig(maxRateLimitRetries = 0))
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
        rig.start()
        rig.sockets.socket.incoming.trySend(rig.replaceState(2, paused = false, seekTo = 0))
        runCurrent()
        assertEquals(listOf("load ${rig.trackUriOf(2)} @0 play=true"), rig.player.calls)

        rig.http.failNext("/state", 500)
        rig.player.onEvent!!.invoke(PlayerEvent.Playing(0))
        runCurrent()
        rig.env.now += 1_500
        rig.player.onEvent!!.invoke(PlayerEvent.Position(1_500))
        runCurrent()
        assertTrue(events.any { it is EngineEvent.Failure && "player event" in it.message })

        rig.player.onEvent!!.invoke(PlayerEvent.Paused(1_500))
        runCurrent()
        assertEquals(false, rig.engine.now.value?.playing)

        rig.sockets.socket.incoming.trySend(rig.replaceState(4, paused = false, seekTo = 0))
        runCurrent()
        assertEquals("load ${rig.trackUriOf(4)} @0 play=true", rig.player.calls.last())
        rig.player.onEvent!!.invoke(PlayerEvent.Playing(0))
        runCurrent()
        assertEquals(rig.trackUriOf(4), rig.engine.now.value?.uri)
        assertEquals(true, rig.engine.now.value?.playing)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stalled_player_report_does_not_block_pause_commands_or_superseded_skip() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.sockets.socket.incoming.trySend(rig.replaceState(2, paused = false, seekTo = 0))
        runCurrent()
        assertEquals("load ${rig.trackUriOf(2)} @0 play=true", rig.player.calls.last())

        val startedGate = CompletableDeferred<Unit>()
        rig.http.gates["started_playing"] = startedGate
        rig.player.onEvent!!.invoke(PlayerEvent.Playing(0))
        rig.env.now += 1_500
        rig.player.onEvent!!.invoke(PlayerEvent.Position(1_500))
        runCurrent()
        assertTrue(rig.http.requests.any { it.url.endsWith("/state") && rig.http.json(it)["debug_source"]?.jsonPrimitive?.content == "started_playing" })

        // Even while started_playing is stuck in HTTP I/O, pause updates now immediately and skipNext/replaceState proceed.
        rig.player.onEvent!!.invoke(PlayerEvent.Paused(1_500))
        runCurrent()
        assertEquals(false, rig.engine.now.value?.playing)

        rig.engine.skipNext()
        rig.sockets.socket.incoming.trySend(rig.replaceState(4, paused = false, seekTo = 0))
        runCurrent()
        assertEquals("load ${rig.trackUriOf(4)} @0 play=true", rig.player.calls.last())
        assertEquals(rig.trackUriOf(4), rig.engine.now.value?.uri)
        startedGate.complete(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun long_retry_after_exceeding_max_rate_limit_wait_fails_fast() = runTest {
        val rig = Rig(backgroundScope, EngineConfig(maxRateLimitRetries = 3, maxRateLimitWaitMs = 4_000L))
        rig.start()
        val t0 = currentTime
        rig.http.failNext("/state", 429, mapOf("retry-after" to "60"))
        val err = kotlin.runCatching {
            rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        }.exceptionOrNull()
        assertTrue(err is EngineException.RateLimited, "expected RateLimited but was $err")
        assertEquals(60_000L, (err as EngineException.RateLimited).retryAfterMs)
        assertTrue(currentTime - t0 < 1_000L, "should fail immediately without sleeping 60s (elapsed=${currentTime - t0})")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ad_break_clears_now_and_consecutive_or_refused_ad_emits_failure() = runTest {
        val rig = playing(this)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }

        val ad1 = rig.replaceState(4, paused = false, seekTo = 0).replace("\"content_type\":\"TRACK\"", "\"content_type\":\"AD\"")
        rig.engine.handleDealerFrame(ad1)
        runCurrent()
        assertNull(rig.engine.now.value)
        assertTrue(events.any { it is EngineEvent.AdBreak })

        // A second consecutive ad loops no further skip_next and surfaces an ad-blocked failure.
        val reqsBefore = rig.http.requests.size
        val ad2 = rig.replaceState(6, paused = false, seekTo = 0).replace("\"content_type\":\"TRACK\"", "\"content_type\":\"AD\"")
        rig.engine.handleDealerFrame(ad2)
        runCurrent()
        assertFalse(rig.http.requests.drop(reqsBefore).any { "/player/command/" in it.url })
        assertTrue(events.any { it is EngineEvent.Failure && it.message.startsWith("ad blocked playback:") })

        // Normal track resets consecutive-ad counter; a subsequent single ad rejecting skip_next emits "ad not passed:".
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        runCurrent()
        rig.http.failNext("/connect-state/v1/player/command/", 403)
        val ad3 = rig.replaceState(4, paused = false, seekTo = 0).replace("\"content_type\":\"TRACK\"", "\"content_type\":\"AD\"")
        rig.engine.handleDealerFrame(ad3)
        runCurrent()
        assertNull(rig.engine.now.value)
        assertTrue(events.any { it is EngineEvent.Failure && it.message.startsWith("ad not passed:") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun player_error_clears_now_and_stops_player_without_wedging_next_track() = runTest {
        val rig = playing(this)
        val events = ArrayList<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }

        rig.player.onEvent!!.invoke(PlayerEvent.Error("ERROR_CODE_IO_UNSPECIFIED", 1_200L))
        runCurrent()
        assertNull(rig.engine.now.value)
        assertEquals("stop", rig.player.calls.last())
        assertTrue(events.any { it is EngineEvent.Failure && it.message.startsWith("player: ERROR_CODE_IO_UNSPECIFIED") })

        rig.engine.handleDealerFrame(rig.replaceState(4, paused = false, seekTo = 0))
        runCurrent()
        assertEquals(rig.trackUriOf(4), rig.engine.now.value?.uri)
        assertEquals("load ${rig.trackUriOf(4)} @0 play=true", rig.player.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failed_before_track_load_rolls_back_now_and_stops_player() = runTest {
        val rig = Rig(backgroundScope, EngineConfig(maxRateLimitRetries = 0, maxRateLimitWaitMs = 0L))
        rig.start()
        rig.http.failNext("/state", 500)
        val err = kotlin.runCatching {
            rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        }.exceptionOrNull()
        assertTrue(err != null)
        assertNull(rig.engine.now.value)
        assertEquals("stop", rig.player.calls.last())

        rig.engine.handleDealerFrame(rig.replaceState(4, paused = false, seekTo = 0))
        assertEquals("load ${rig.trackUriOf(4)} @0 play=true", rig.player.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pause_arriving_during_before_track_load_starts_player_paused() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        val gate = CompletableDeferred<Unit>()
        rig.http.gates["before_track_load"] = gate

        rig.sockets.socket.incoming.trySend(rig.replaceState(2, paused = false, seekTo = 0))
        runCurrent()
        assertEquals(listOf("before_track_load"), rig.http.debugSources())
        assertTrue(rig.player.calls.isEmpty())

        // User pauses while before_track_load is still in flight; player must not pause prematurely and must load paused.
        rig.sockets.socket.incoming.trySend(rig.replaceState(2, paused = true, seekTo = null))
        runCurrent()
        assertTrue(rig.player.calls.isEmpty())
        assertEquals(false, rig.engine.now.value?.buffering)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("load ${rig.trackUriOf(2)} @0 play=false"), rig.player.calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun preload_and_natural_end_skip_ad_transition_when_skip_next_points_to_track() = runTest {
        val rig = Rig(backgroundScope)
        val existingTracks = rig.machine["tracks"]!!.jsonArray
        val adTrackIdx = existingTracks.size
        val sampleTrack = existingTracks[2].jsonObject
        val adTrack = kotlinx.serialization.json.JsonObject(
            sampleTrack + mapOf(
                "content_type" to kotlinx.serialization.json.JsonPrimitive("AD"),
                "metadata" to kotlinx.serialization.json.JsonObject(
                    sampleTrack["metadata"]!!.jsonObject + mapOf(
                        "uri" to kotlinx.serialization.json.JsonPrimitive("spotify:ad:testad1"),
                    ),
                ),
            ),
        )
        val advanceStateIdx = rig.advanceOf(2)
        val updatedStates = kotlinx.serialization.json.JsonArray(
            rig.states.mapIndexed { idx, el ->
                if (idx == advanceStateIdx) kotlinx.serialization.json.JsonObject(el.jsonObject + ("track" to kotlinx.serialization.json.JsonPrimitive(adTrackIdx)))
                else el
            },
        )
        val modifiedMachine = kotlinx.serialization.json.JsonObject(
            rig.machine + mapOf(
                "state_machine_id" to kotlinx.serialization.json.JsonPrimitive("ChTadskip"),
                "tracks" to kotlinx.serialization.json.JsonArray(existingTracks + adTrack),
                "states" to updatedStates,
            ),
        )
        rig.http.machine = modifiedMachine
        val framePayload = kotlinx.serialization.json.JsonObject(
            rig.payload + mapOf(
                "state_machine" to modifiedMachine,
                "state_ref" to kotlinx.serialization.json.buildJsonObject {
                    put("state_index", kotlinx.serialization.json.JsonPrimitive(2))
                    put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                    put("active_alias", kotlinx.serialization.json.JsonNull)
                },
                "seek_to" to kotlinx.serialization.json.JsonPrimitive(0),
            ),
        )
        val rawFrame = Fixtures.json("dealer-replace-state.json")
        val initialFrame = kotlinx.serialization.json.JsonObject(
            rawFrame + ("payloads" to kotlinx.serialization.json.JsonArray(listOf(framePayload))),
        ).toString()

        rig.start()
        rig.engine.handleDealerFrame(initialFrame)
        rig.player.licence!!.licence(Fixtures.bytes("licence-challenge.bin"))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_000)

        val skipNextTrackUri = rig.trackUriOf(6)
        assertTrue(rig.player.calls.contains("preload $skipNextTrackUri"), "expected preload of $skipNextTrackUri, got ${rig.player.calls}")
        assertFalse(rig.player.calls.any { "spotify:ad:testad1" in it })

        rig.playFor(1_000, 153_500)
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        assertEquals(skipNextTrackUri, rig.engine.now.value?.uri)
        assertFalse(rig.player.calls.any { "spotify:ad:testad1" in it })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun chained_ad_transitions_walk_to_next_playable_track_and_capture_recovery_hint() = runTest {
        val rig = Rig(backgroundScope)
        val existingTracks = rig.machine["tracks"]!!.jsonArray
        val adTrackIdx = existingTracks.size
        val sampleTrack = existingTracks[2].jsonObject
        val adTrack = kotlinx.serialization.json.JsonObject(
            sampleTrack + mapOf(
                "content_type" to kotlinx.serialization.json.JsonPrimitive("AD"),
                "metadata" to kotlinx.serialization.json.JsonObject(
                    sampleTrack["metadata"]!!.jsonObject + mapOf(
                        "uri" to kotlinx.serialization.json.JsonPrimitive("spotify:ad:chain1"),
                    ),
                ),
            ),
        )
        val targetTrackUri = rig.trackUriOf(4)
        // Make BOTH advance (state 3) and skip_next (state 6) of state 2 point to AD states,
        // and wire state 3's advance directly to non-AD state 4.
        val updatedStates = kotlinx.serialization.json.JsonArray(
            rig.states.mapIndexed { idx, el ->
                val obj = el.jsonObject
                when (idx) {
                    3 -> {
                        val tr = obj["transitions"]!!.jsonObject
                        val patchedTr = kotlinx.serialization.json.JsonObject(
                            tr + ("advance" to kotlinx.serialization.json.buildJsonObject {
                                put("state_index", kotlinx.serialization.json.JsonPrimitive(4))
                                put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                                put("active_alias", kotlinx.serialization.json.JsonNull)
                            }),
                        )
                        kotlinx.serialization.json.JsonObject(
                            obj + mapOf(
                                "track" to kotlinx.serialization.json.JsonPrimitive(adTrackIdx),
                                "transitions" to patchedTr,
                            ),
                        )
                    }
                    6 -> kotlinx.serialization.json.JsonObject(obj + ("track" to kotlinx.serialization.json.JsonPrimitive(adTrackIdx)))
                    else -> el
                }
            },
        )
        val modifiedMachine = kotlinx.serialization.json.JsonObject(
            rig.machine + mapOf(
                "state_machine_id" to kotlinx.serialization.json.JsonPrimitive("ChTadchain"),
                "tracks" to kotlinx.serialization.json.JsonArray(existingTracks + adTrack),
                "states" to updatedStates,
            ),
        )
        rig.http.machine = modifiedMachine
        val framePayload = kotlinx.serialization.json.JsonObject(
            rig.payload + mapOf(
                "state_machine" to modifiedMachine,
                "state_ref" to kotlinx.serialization.json.buildJsonObject {
                    put("state_index", kotlinx.serialization.json.JsonPrimitive(2))
                    put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                    put("active_alias", kotlinx.serialization.json.JsonNull)
                },
                "seek_to" to kotlinx.serialization.json.JsonPrimitive(0),
            ),
        )
        val initialFrame = kotlinx.serialization.json.JsonObject(
            Fixtures.json("dealer-replace-state.json") + ("payloads" to kotlinx.serialization.json.JsonArray(listOf(framePayload))),
        ).toString()

        rig.start()
        rig.engine.handleDealerFrame(initialFrame)
        rig.player.licence!!.licence(Fixtures.bytes("licence-challenge.bin"))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_000)

        assertTrue(rig.player.calls.contains("preload $targetTrackUri"), "expected preload of chained non-AD track $targetTrackUri, got ${rig.player.calls}")
        rig.playFor(1_000, 153_500)
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        assertEquals(targetTrackUri, rig.engine.now.value?.uri)

        // Direct dealer push to AD state 3 also exposes track 4 as the adRecoveryHint.
        val adPayload = kotlinx.serialization.json.JsonObject(
            rig.payload + mapOf(
                "state_machine" to modifiedMachine,
                "state_ref" to kotlinx.serialization.json.buildJsonObject {
                    put("state_index", kotlinx.serialization.json.JsonPrimitive(3))
                    put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                    put("active_alias", kotlinx.serialization.json.JsonNull)
                },
                "seek_to" to kotlinx.serialization.json.JsonPrimitive(0),
            ),
        )
        val adFrame = kotlinx.serialization.json.JsonObject(
            Fixtures.json("dealer-replace-state.json") + ("payloads" to kotlinx.serialization.json.JsonArray(listOf(adPayload))),
        ).toString()
        rig.http.failNext("/connect-state/v1/player/command/", 403)
        rig.engine.handleDealerFrame(adFrame)
        runCurrent()
        assertEquals(targetTrackUri, rig.engine.adRecoveryHint().second)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun metadata_and_provider_flagged_ads_are_detected_without_fetching_audio() = runTest {
        val metaVariants = listOf(
            """"is_advertisement":true""",
            """"ad_id":"ad-99"""",
            null, // track_provider = "ad" variant
        )
        for (metaField in metaVariants) {
            val rig = Rig(backgroundScope)
            val events = ArrayList<EngineEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.events.collect { events += it } }
            rig.start()
            val before = rig.http.requests.size

            val existingTracks = rig.machine["tracks"]!!.jsonArray
            val flaggedIdx = existingTracks.size
            val sampleTrack = existingTracks[2].jsonObject
            val sampleMeta = sampleTrack["metadata"]!!.jsonObject
            val patchedMeta = if (metaField != null) {
                val (k, v) = metaField.split(':', limit = 2)
                kotlinx.serialization.json.JsonObject(
                    sampleMeta + mapOf(
                        "uri" to kotlinx.serialization.json.JsonPrimitive("spotify:track:flagged$flaggedIdx"),
                        k.trim('"') to kotlinx.serialization.json.Json.parseToJsonElement(v),
                    ),
                )
            } else {
                kotlinx.serialization.json.JsonObject(
                    sampleMeta + ("uri" to kotlinx.serialization.json.JsonPrimitive("spotify:track:providerAd")),
                )
            }
            val flaggedTrack = kotlinx.serialization.json.JsonObject(
                sampleTrack + mapOf(
                    "content_type" to kotlinx.serialization.json.JsonPrimitive("TRACK"),
                    "metadata" to patchedMeta,
                ),
            )
            val updatedStates = kotlinx.serialization.json.JsonArray(
                rig.states.mapIndexed { idx, el ->
                    if (idx == 2) {
                        kotlinx.serialization.json.JsonObject(
                            el.jsonObject + buildMap {
                                put("track", kotlinx.serialization.json.JsonPrimitive(flaggedIdx))
                                if (metaField == null) put("track_provider", kotlinx.serialization.json.JsonPrimitive("ad"))
                            },
                        )
                    } else {
                        el
                    }
                },
            )
            val modifiedMachine = kotlinx.serialization.json.JsonObject(
                rig.machine + mapOf(
                    "state_machine_id" to kotlinx.serialization.json.JsonPrimitive("ChTflaggedAd"),
                    "tracks" to kotlinx.serialization.json.JsonArray(existingTracks + flaggedTrack),
                    "states" to updatedStates,
                ),
            )
            val framePayload = kotlinx.serialization.json.JsonObject(
                rig.payload + mapOf(
                    "state_machine" to modifiedMachine,
                    "state_ref" to kotlinx.serialization.json.buildJsonObject {
                        put("state_index", kotlinx.serialization.json.JsonPrimitive(2))
                        put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                        put("active_alias", kotlinx.serialization.json.JsonNull)
                    },
                    "seek_to" to kotlinx.serialization.json.JsonPrimitive(0),
                ),
            )
            val frame = kotlinx.serialization.json.JsonObject(
                Fixtures.json("dealer-replace-state.json") + ("payloads" to kotlinx.serialization.json.JsonArray(listOf(framePayload))),
            ).toString()

            rig.engine.handleDealerFrame(frame)
            runCurrent()
            assertEquals(before + 1, rig.http.requests.size)
            assertTrue("skip_next" in rig.http.requests.last().body!!.decodeToString())
            assertEquals(listOf("stop"), rig.player.calls)
            assertTrue(events.any { it is EngineEvent.AdBreak }, "expected AdBreak for variant $metaField")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ended_track_preserves_playlist_context_and_show_next_across_null_ad_and_dealer_ad_push() = runTest {
        val rig = Rig(backgroundScope)
        val existingTracks = rig.machine["tracks"]!!.jsonArray
        val adTrackIdx = existingTracks.size
        val track5Idx = rig.states[2].jsonObject["track"]!!.jsonPrimitive.content.toInt()
        val sampleTrack = existingTracks[track5Idx].jsonObject
        val track5Meta = kotlinx.serialization.json.JsonObject(
            sampleTrack["metadata"]!!.jsonObject - "context_uri" + mapOf(
                "group_uri" to kotlinx.serialization.json.JsonPrimitive("spotify:album:poison"),
            ),
        )
        val track5WithoutCtx = kotlinx.serialization.json.JsonObject(sampleTrack + ("metadata" to track5Meta))
        val adTrack = kotlinx.serialization.json.JsonObject(
            sampleTrack + mapOf(
                "content_type" to kotlinx.serialization.json.JsonPrimitive("AD"),
                "metadata" to kotlinx.serialization.json.JsonObject(
                    sampleTrack["metadata"]!!.jsonObject - "context_uri" + mapOf(
                        "uri" to kotlinx.serialization.json.JsonPrimitive("spotify:ad:break1"),
                    ),
                ),
            ),
        )
        val targetTrackUri = rig.trackUriOf(4)
        val track5Uri = rig.trackUriOf(2)
        val playlistCtx = "spotify:playlist:UserPlaylist1"
        val refNull = kotlinx.serialization.json.JsonNull

        val updatedStates = kotlinx.serialization.json.JsonArray(
            rig.states.mapIndexed { idx, el ->
                val obj = el.jsonObject
                when (idx) {
                    2 -> {
                        val tr = obj["transitions"]!!.jsonObject
                        val eci = obj["end_content_info"]?.jsonObject?.let {
                            kotlinx.serialization.json.JsonObject(it - "context_uri")
                        }
                        kotlinx.serialization.json.JsonObject(
                            obj + buildMap {
                                if (eci != null) put("end_content_info", eci)
                                put(
                                    "transitions",
                                    kotlinx.serialization.json.JsonObject(
                                        tr + mapOf(
                                            "advance" to kotlinx.serialization.json.buildJsonObject {
                                                put("state_index", kotlinx.serialization.json.JsonPrimitive(3))
                                                put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                                                put("active_alias", kotlinx.serialization.json.JsonNull)
                                            },
                                            "skip_next" to kotlinx.serialization.json.buildJsonObject {
                                                put("state_index", kotlinx.serialization.json.JsonPrimitive(3))
                                                put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                                                put("active_alias", kotlinx.serialization.json.JsonNull)
                                            },
                                            "show_next" to kotlinx.serialization.json.buildJsonObject {
                                                put("state_index", kotlinx.serialization.json.JsonPrimitive(4))
                                                put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                                                put("active_alias", kotlinx.serialization.json.JsonNull)
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    3 -> {
                        kotlinx.serialization.json.JsonObject(
                            obj + mapOf(
                                "track" to kotlinx.serialization.json.JsonPrimitive(adTrackIdx),
                                "transitions" to kotlinx.serialization.json.buildJsonObject {
                                    put("advance", refNull)
                                    put("skip_next", refNull)
                                    put("show_next", refNull)
                                },
                            ),
                        )
                    }
                    4 -> kotlinx.serialization.json.JsonObject(obj + ("track_provider" to kotlinx.serialization.json.JsonPrimitive("context")))
                    else -> el
                }
            },
        )
        val replacedTracks = kotlinx.serialization.json.JsonArray(
            existingTracks.mapIndexed { i, e -> if (i == track5Idx) track5WithoutCtx else e } + adTrack,
        )
        val modifiedMachine = kotlinx.serialization.json.JsonObject(
            rig.machine + mapOf(
                "state_machine_id" to kotlinx.serialization.json.JsonPrimitive("ChTnullAdPreserves"),
                "tracks" to replacedTracks,
                "states" to updatedStates,
            ),
        )
        rig.http.machine = modifiedMachine
        rig.start()
        // Seed playlist context as NativeAudio / SongPlayer does on play().
        rig.engine.play(playlistCtx, track5Uri)

        val framePayload = kotlinx.serialization.json.JsonObject(
            rig.payload + mapOf(
                "state_machine" to modifiedMachine,
                "state_ref" to kotlinx.serialization.json.buildJsonObject {
                    put("state_index", kotlinx.serialization.json.JsonPrimitive(2))
                    put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                    put("active_alias", kotlinx.serialization.json.JsonNull)
                },
                "seek_to" to kotlinx.serialization.json.JsonPrimitive(0),
            ),
        )
        val initialFrame = kotlinx.serialization.json.JsonObject(
            Fixtures.json("dealer-replace-state.json") + ("payloads" to kotlinx.serialization.json.JsonArray(listOf(framePayload))),
        ).toString()

        rig.engine.handleDealerFrame(initialFrame)
        rig.player.licence!!.licence(Fixtures.bytes("licence-challenge.bin"))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 153_500)

        // Prevent skip_next from succeeding when entering null-transition AD state 3.
        rig.http.failNext("/connect-state/v1/player/command/", 403)
        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        runCurrent()

        // Subsequent dealer push for the same AD state 3 must not wipe the captured show_next hint.
        val adPushPayload = kotlinx.serialization.json.JsonObject(
            rig.payload + mapOf(
                "state_machine" to modifiedMachine,
                "state_ref" to kotlinx.serialization.json.buildJsonObject {
                    put("state_index", kotlinx.serialization.json.JsonPrimitive(3))
                    put("paused", kotlinx.serialization.json.JsonPrimitive(false))
                    put("active_alias", kotlinx.serialization.json.JsonNull)
                },
                "seek_to" to kotlinx.serialization.json.JsonPrimitive(0),
            ),
        )
        val adPushFrame = kotlinx.serialization.json.JsonObject(
            Fixtures.json("dealer-replace-state.json") + ("payloads" to kotlinx.serialization.json.JsonArray(listOf(adPushPayload))),
        ).toString()
        rig.engine.handleDealerFrame(adPushFrame)
        runCurrent()

        val snap = rig.engine.adRecoverySnapshot()
        assertEquals(playlistCtx, snap.contextUri)
        assertEquals(track5Uri, snap.trackUri)
        assertEquals(targetTrackUri, snap.nextTrackUri)
        assertEquals("context", snap.nextProvider)
    }

    /** A rig playing state 2 from 0, 10 s in (started_playing sent, next track preloaded). */
    private suspend fun playing(scope: kotlinx.coroutines.test.TestScope): Rig {
        val rig = Rig(scope.backgroundScope)
        rig.start()
        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 10_000)
        return rig
    }
}
