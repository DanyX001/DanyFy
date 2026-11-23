package app.myspoti.engine

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeEngineCrossfadeTest {

    @Test
    fun crossfadeTriggersEarlyOverlapAndReusesPreloadedMedia() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.crossfadeSeconds = 6

        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_500)

        val nextUri = rig.trackUriOf(rig.advanceOf(2))
        assertTrue("preload $nextUri" in rig.player.calls)
        val resolvesBeforeFade = rig.http.count("/storage-resolve/")

        // Advance well past 1s into the 6s crossfade window (< 153_889 - 6_000 = 147_889 -> say 148_500).
        rig.playFor(1_500, 146_000)
        assertFalse(rig.player.calls.any { it.startsWith("loadCrossfade ") })

        rig.env.now += 2_500
        rig.engine.handlePlayerEvent(PlayerEvent.Position(148_500))

        val crossfadeCall = rig.player.calls.lastOrNull { it.startsWith("loadCrossfade ") }
        assertTrue(crossfadeCall != null, "Expected loadCrossfade call, got ${rig.player.calls}")
        assertTrue(crossfadeCall!!.startsWith("loadCrossfade $nextUri @0 play=true fadeMs=5389"))
        assertEquals(resolvesBeforeFade, rig.http.count("/storage-resolve/"))
        assertEquals(
            listOf("before_track_load", "started_playing", "played_threshold_reached", "track_data_finalized", "before_track_load"),
            rig.http.debugSources(),
        )

        // Late Ended from the fading outgoing track before/after swap must never finalize twice.
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(180_000))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        assertEquals(5, rig.http.debugSources().size)
    }

    @Test
    fun seekingIntoTailSuppressesCrossfadeUntilEnded() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.crossfadeSeconds = 6

        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 1_500)

        rig.engine.handlePlayerEvent(PlayerEvent.Seeked(149_000))
        rig.playFor(149_000, 153_500)
        assertFalse(rig.player.calls.any { it.startsWith("loadCrossfade ") })

        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        val nextUri = rig.trackUriOf(rig.advanceOf(2))
        assertTrue("load $nextUri @0 play=true" in rig.player.calls)
    }

    @Test
    fun mutedMirrorAndDisablingCrossfadeNeverCrossfade() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.engine.crossfadeSeconds = 6
        rig.engine.setMuted(true)
        assertTrue("cancelCrossfade" in rig.player.calls)

        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 152_000)
        assertFalse(rig.player.calls.any { it.startsWith("loadCrossfade ") || it.startsWith("fadeOut ") })

        rig.engine.crossfadeSeconds = 0
        assertEquals(2, rig.player.calls.count { it == "cancelCrossfade" })
    }

    @Test
    fun sequentialFadeFallbackRunsWhenConcurrentUnsupported() = runTest {
        val rig = Rig(backgroundScope)
        rig.start()
        rig.player.concurrentSupported = false
        rig.engine.crossfadeSeconds = 8

        rig.engine.handleDealerFrame(rig.replaceState(2, paused = false, seekTo = 0))
        rig.engine.handlePlayerEvent(PlayerEvent.Loaded(153_889))
        rig.engine.handlePlayerEvent(PlayerEvent.Playing(0))
        rig.playFor(0, 150_000)

        assertFalse(rig.player.calls.any { it.startsWith("loadCrossfade ") })
        rig.env.now += 2_000
        rig.engine.handlePlayerEvent(PlayerEvent.Position(152_000))
        assertTrue(rig.player.calls.any { it.startsWith("fadeOut ") })

        rig.engine.handlePlayerEvent(PlayerEvent.Ended(153_889))
        val nextUri = rig.trackUriOf(rig.advanceOf(2))
        val loadIn = rig.player.calls.lastOrNull { it.startsWith("loadCrossfade $nextUri") }
        assertTrue(loadIn != null, "Expected sequential fade-in loadCrossfade, got ${rig.player.calls}")
    }
}
