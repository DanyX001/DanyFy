package com.music.spotui.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRecoveryPolicyTest {
    @Test
    fun canRecoverCapsAttemptsInsideRollingWindowAndResetsAfterWindow() {
        val now = 100_000L
        assertTrue(AdRecoveryPolicy.canRecover(listOf(now - 5_000L, now - 10_000L), now))
        assertFalse(AdRecoveryPolicy.canRecover(listOf(now - 5_000L, now - 10_000L, now - 20_000L), now))
        assertTrue(AdRecoveryPolicy.canRecover(listOf(now - 70_000L, now - 10_000L, now - 20_000L), now))
    }

    @Test
    fun pickRecoveryTargetSkipsAdUrisAndPrefersHintAndAppNextTrack() {
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:ctx1",
            hintTrack = "spotify:track:nextFromSm",
            appTarget = AdRecoveryTarget("spotify:playlist:ctx1", "spotify:track:nextFromQueue", shuffle = true),
            lastPlayed = AdRecoveryTarget("spotify:playlist:old", "spotify:track:oldTrack", shuffle = false),
        )!!
        assertEquals("spotify:playlist:ctx1", target.contextUri)
        assertEquals("spotify:track:nextFromSm", target.trackUri)
        assertEquals(true, target.shuffle)

        val fallback = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:ad:bad",
            hintTrack = "spotify:ad:ad1",
            appTarget = AdRecoveryTarget("spotify:playlist:ctx2", "spotify:track:B", order = listOf("spotify:track:A", "spotify:track:B"), shuffle = false),
            lastPlayed = null,
        )!!
        assertEquals("spotify:playlist:ctx2", fallback.contextUri)
        assertEquals("spotify:track:B", fallback.trackUri)
        assertEquals(listOf("spotify:track:A", "spotify:track:B"), fallback.order)

        assertNull(AdRecoveryPolicy.pickRecoveryTarget("spotify:ad:x", "spotify:ad:y", null, null))
    }

    @Test
    fun rejectsStaleQueueGhostAndNonPlaylistHintThenAdvancesPlaylistTrack() {
        val playlist = (1..10).map { "spotify:track:t$it" }
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:P1",
            hintTrack = "spotify:track:nonPlaylistHint",
            appTarget = AdRecoveryTarget(
                contextUri = "spotify:playlist:P1",
                trackUri = "spotify:track:staleGhost",
                playlistTracks = playlist,
                shuffle = false,
            ),
            lastPlayed = AdRecoveryTarget("spotify:playlist:P1", "spotify:track:t1", shuffle = false),
            hintCurrentTrack = "spotify:track:t5",
            hintNextProvider = "context",
        )!!
        assertEquals("spotify:playlist:P1", target.contextUri)
        assertEquals("spotify:track:t6", target.trackUri)
    }

    @Test
    fun defendsAgainstAlbumGroupUriPoisoning() {
        val playlist = (1..6).map { "spotify:track:t$it" }
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:album:poisonAlbum",
            hintTrack = "spotify:track:t6",
            appTarget = AdRecoveryTarget(
                contextUri = "spotify:playlist:P1",
                trackUri = "spotify:track:t6",
                playlistTracks = playlist,
                shuffle = false,
            ),
            lastPlayed = AdRecoveryTarget("spotify:playlist:P1", "spotify:track:t1", shuffle = false),
            hintCurrentTrack = "spotify:track:t5",
        )!!
        assertEquals("spotify:playlist:P1", target.contextUri)
        assertEquals("spotify:track:t6", target.trackUri)
    }

    @Test
    fun preservesLargePlaylistIndexWithDuplicateTracksForwardScan() {
        val playlist = MutableList(120) { i -> "spotify:track:t$i" }.apply {
            this[5] = "spotify:track:dup"
            this[106] = "spotify:track:dup"
        }
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:Big",
            hintTrack = "spotify:track:dup",
            appTarget = AdRecoveryTarget(
                contextUri = "spotify:playlist:Big",
                trackUri = null,
                playlistTracks = playlist,
                shuffle = false,
            ),
            lastPlayed = AdRecoveryTarget("spotify:playlist:Big", "spotify:track:t105", index = 105, shuffle = false),
            hintCurrentTrack = "spotify:track:t105",
        )!!
        assertEquals("spotify:playlist:Big", target.contextUri)
        assertEquals("spotify:track:dup", target.trackUri)
        assertEquals(106, target.index)
    }

    @Test
    fun playsAutoplayTrackStandaloneWithoutPlaylistContext() {
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:P1",
            hintTrack = "spotify:track:autoSuggested",
            appTarget = AdRecoveryTarget("spotify:playlist:P1", null, order = listOf("spotify:track:t1"), shuffle = false),
            lastPlayed = null,
            hintCurrentTrack = "spotify:track:t10",
            hintNextProvider = "autoplay",
        )!!
        assertEquals("spotify:track:autoSuggested", target.contextUri)
        assertNull(target.trackUri)
        assertNull(target.order)
    }

    @Test
    fun repeatAllWrapsAtEndWhereasRepeatOffStopsCleanly() {
        val playlist = listOf("spotify:track:t1", "spotify:track:t2")
        val app = AdRecoveryTarget("spotify:playlist:P1", null, shuffle = false, playlistTracks = playlist)

        val wrapped = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:P1",
            hintTrack = null,
            appTarget = app,
            lastPlayed = null,
            hintCurrentTrack = "spotify:track:t2",
            repeatAll = true,
        )!!
        assertEquals("spotify:playlist:P1", wrapped.contextUri)
        assertEquals("spotify:track:t1", wrapped.trackUri)

        val stopped = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:P1",
            hintTrack = null,
            appTarget = app,
            lastPlayed = null,
            hintCurrentTrack = "spotify:track:t2",
            repeatAll = false,
        )
        assertNull(stopped)
    }

    @Test
    fun neverRecoversOntoRecentlySkippedFromTrack() {
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:playlist:P1",
            hintTrack = null,
            appTarget = AdRecoveryTarget("spotify:playlist:P1", "spotify:track:t1", shuffle = true),
            lastPlayed = AdRecoveryTarget("spotify:playlist:P1", "spotify:track:t1", shuffle = true),
            hintCurrentTrack = "spotify:track:t1",
            skippedFromTrack = "spotify:track:t1",
        )!!
        assertEquals("spotify:playlist:P1", target.contextUri)
        assertNull(target.trackUri)
    }

    @Test
    fun standaloneTrackNaturalEndAdBreakAdvancesToNextRadioTrackStandalone() {
        val target = AdRecoveryPolicy.pickRecoveryTarget(
            hintCtx = "spotify:track:tATuEnded",
            hintTrack = null,
            appTarget = AdRecoveryTarget(contextUri = "spotify:track:radioNext1", trackUri = null, shuffle = false),
            lastPlayed = AdRecoveryTarget(contextUri = "spotify:track:tATuEnded", trackUri = "spotify:track:tATuEnded", shuffle = false),
            hintCurrentTrack = "spotify:track:tATuEnded",
        )!!
        assertEquals("spotify:track:radioNext1", target.contextUri)
        assertNull(target.trackUri)
    }
}
