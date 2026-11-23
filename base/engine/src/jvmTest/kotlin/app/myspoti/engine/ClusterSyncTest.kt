package app.myspoti.engine

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClusterSyncTest {
    @Test
    fun parse_preserves_buffering_and_zero_speed_when_active_device_is_loading() {
        val raw = """
            {
              "cluster": {
                "timestamp": "1794268055000",
                "active_device_id": "tv-1",
                "devices": { "tv-1": { "name": "Living Room TV", "device_type": "tv", "volume": 32768 } },
                "player_state": {
                  "track": { "uri": "spotify:track:t1" },
                  "is_playing": true,
                  "is_paused": false,
                  "is_buffering": false,
                  "playback_speed": 0.0,
                  "position_as_of_timestamp": "0",
                  "timestamp": "1794268055000",
                  "duration": "200000"
                }
              }
            }
        """.trimIndent()
        val parsed = Cluster.parse(Json.parseToJsonElement(raw).jsonObject)
        assertNotNull(parsed)
        assertFalse(parsed!!.paused)
        assertTrue(parsed.isBuffering)
        assertEquals(0.0, parsed.speed)
    }

    @Test
    fun parse_keeps_paused_separate_from_buffering() {
        val raw = """
            {
              "cluster": {
                "timestamp": "1794268055000",
                "active_device_id": "tv-1",
                "devices": { "tv-1": { "name": "TV", "device_type": "tv", "volume": 32768 } },
                "player_state": {
                  "track": { "uri": "spotify:track:t1" },
                  "is_playing": true,
                  "is_paused": true,
                  "is_buffering": true,
                  "playback_speed": 0.0,
                  "position_as_of_timestamp": "45000",
                  "timestamp": "1794268055000",
                  "duration": "200000"
                }
              }
            }
        """.trimIndent()
        val parsed = Cluster.parse(Json.parseToJsonElement(raw).jsonObject)!!
        assertTrue(parsed.paused)
        assertFalse(parsed.isBuffering)
        assertEquals(1.0, parsed.speed)
    }

    @Test
    fun native_engine_adjusts_cluster_timestamps_for_phone_clock_skew_from_dealer() = runTest {
        val rig = Rig(backgroundScope)
        val serverTimeMs = 1_794_268_055_000L
        // Phone wall clock runs 700 ms ahead of Spotify's Connect server.
        rig.env.now = serverTimeMs + 700L
        val frame = """
            {
              "type": "message",
              "uri": "hm://connect-state/v1/cluster",
              "payloads": [{
                "cluster": {
                  "timestamp": "$serverTimeMs",
                  "active_device_id": "tv-1",
                  "devices": { "tv-1": { "name": "TV", "device_type": "tv", "volume": 32768 } },
                  "player_state": {
                    "track": { "uri": "spotify:track:t1" },
                    "is_playing": true,
                    "is_paused": false,
                    "playback_speed": 1.0,
                    "position_as_of_timestamp": "12000",
                    "timestamp": "$serverTimeMs",
                    "duration": "200000"
                  }
                }
              }]
            }
        """.trimIndent()

        rig.engine.handleDealerFrame(frame)
        val c = rig.engine.cluster.value!!
        assertEquals(700L, c.clockOffsetMs)
        // Translating server timestamp to local clock cancels the +700ms phone skew.
        assertEquals(serverTimeMs + 700L, c.localTimestampMs(rig.env.now))
    }

    @Test
    fun dealer_ignores_unrealistic_clock_skew_against_test_fixtures() = runTest {
        val rig = Rig(backgroundScope)
        rig.env.now = 1_794_268_055_580L
        val frame = """
            {
              "type": "message",
              "uri": "hm://connect-state/v1/cluster",
              "payloads": [{
                "cluster": {
                  "timestamp": "1700000000000",
                  "active_device_id": "tv-1",
                  "devices": { "tv-1": { "name": "TV", "device_type": "tv", "volume": 32768 } },
                  "player_state": {
                    "track": { "uri": "spotify:track:t1" },
                    "is_playing": true,
                    "is_paused": false,
                    "position_as_of_timestamp": "12000",
                    "timestamp": "1700000000000",
                    "duration": "200000"
                  }
                }
              }]
            }
        """.trimIndent()

        rig.engine.handleDealerFrame(frame)
        val c = rig.engine.cluster.value!!
        assertEquals(0L, c.clockOffsetMs)
    }

    @Test
    fun start_measures_clock_offset_from_hobs_midpoint_and_cancels_skew() = runTest {
        val serverNow = 1_794_268_055_000L
        val env = FakeEnv(now = serverNow + 680L)
        val delegate = FakeHttp()
        val hobsJson = """
            {
              "timestamp": "$serverNow",
              "active_device_id": "tv-1",
              "devices": { "tv-1": { "name": "TV", "device_type": "tv", "volume": 32768 } },
              "player_state": {
                "track": { "uri": "spotify:track:t1" },
                "is_playing": true,
                "is_paused": false,
                "playback_speed": 1.0,
                "position_as_of_timestamp": "25000",
                "timestamp": "$serverNow",
                "duration": "200000"
              }
            }
        """.trimIndent()
        val http = object : Http {
            override suspend fun request(request: HttpRequest): HttpResponse {
                if ("/connect-state/v1/devices/hobs_" in request.url) {
                    env.now += 40L
                    return HttpResponse(200, emptyMap(), hobsJson.encodeToByteArray())
                }
                return delegate.request(request)
            }
        }
        val engine = NativeEngine(http, FakeTokens(), FakeSockets(), FakePlayer(), env, EngineConfig(), backgroundScope)
        engine.start()
        val c = engine.cluster.value!!
        // t0 = serverNow + 680, t1 = serverNow + 720 -> midpoint = serverNow + 700 -> offset = +700.
        assertEquals(700L, c.clockOffsetMs)
        assertEquals(serverNow + 700L, c.localTimestampMs(env.now))
        engine.close()
    }
}
