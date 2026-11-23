package com.music.spotui.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSyncTest {
    @Test
    fun remoteConnectCorrectsSubSecondAheadDriftInsteadOfKeepingItLocked() {
        assertTrue(
            PositionSync.shouldUpdatePosition(
                seeking = false,
                wasSeeking = false,
                wasPlaying = true,
                playing = true,
                wasBuffering = false,
                isBuffering = false,
                trackChanged = false,
                remote = true,
                reportedMs = 45_000L,
                predictedMs = 45_700L,
            )
        )
    }

    @Test
    fun remoteConnectIgnoresTinyMicroJitterBelowSnapThreshold() {
        assertFalse(
            PositionSync.shouldUpdatePosition(
                seeking = false,
                wasSeeking = false,
                wasPlaying = true,
                playing = true,
                wasBuffering = false,
                isBuffering = false,
                trackChanged = false,
                remote = true,
                reportedMs = 45_000L,
                predictedMs = 45_050L,
            )
        )
    }

    @Test
    fun landedSeekAlwaysAcceptsReportedPositionEvenForSmallLead() {
        assertTrue(
            PositionSync.shouldUpdatePosition(
                seeking = false,
                wasSeeking = true,
                wasPlaying = true,
                playing = true,
                wasBuffering = false,
                isBuffering = false,
                trackChanged = false,
                remote = true,
                reportedMs = 90_000L,
                predictedMs = 90_700L,
            )
        )
    }

    @Test
    fun localPlaybackFiltersMinorBridgeNoiseButCorrectsLargerDrift() {
        assertFalse(
            PositionSync.shouldUpdatePosition(
                seeking = false,
                wasSeeking = false,
                wasPlaying = true,
                playing = true,
                wasBuffering = false,
                isBuffering = false,
                trackChanged = false,
                remote = false,
                reportedMs = 30_000L,
                predictedMs = 30_250L,
            )
        )
        assertTrue(
            PositionSync.shouldUpdatePosition(
                seeking = false,
                wasSeeking = false,
                wasPlaying = true,
                playing = true,
                wasBuffering = false,
                isBuffering = false,
                trackChanged = false,
                remote = false,
                reportedMs = 30_000L,
                predictedMs = 30_700L,
            )
        )
    }

    @Test
    fun seekLandedRejectsUnchangedPreSeekSnapshotAndAcceptsConfirmedTarget() {
        assertFalse(
            PositionSync.seekLanded(
                itemUri = "spotify:track:1",
                seekUri = "spotify:track:1",
                statePosMs = 40_000L,
                stateTsMs = 1_000_000L,
                preSeekPosMs = 40_000L,
                preSeekTsMs = 1_000_000L,
                projectedAtMs = 40_800L,
                seekTargetMs = 40_900L,
                elapsedSinceSeekMs = 200L,
                nearMs = 2_000L,
            )
        )
        assertTrue(
            PositionSync.seekLanded(
                itemUri = "spotify:track:1",
                seekUri = "spotify:track:1",
                statePosMs = 40_900L,
                stateTsMs = 1_000_300L,
                preSeekPosMs = 40_000L,
                preSeekTsMs = 1_000_000L,
                projectedAtMs = 40_920L,
                seekTargetMs = 40_900L,
                elapsedSinceSeekMs = 350L,
                nearMs = 2_000L,
            )
        )
    }
}
