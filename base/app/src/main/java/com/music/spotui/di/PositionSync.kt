package com.music.spotui.di

import kotlin.math.abs

/**
 * Pure synchronization policy that keeps UI playback clocks aligned with authoritative remote
 * Connect updates and seek completions without jittering on small local poll noise.
 */
object PositionSync {
    /** Snap any Connect position discrepancy of at least this many ms during continuous playback. */
    const val REMOTE_DRIFT_SNAP_MS = 80L

    /** Ignore minor JS-bridge poll jitter below this threshold during steady local WebView playback. */
    const val LOCAL_DRIFT_SNAP_MS = 400L

    /**
     * Returns whether `SongPlayer` should adopt [reportedMs] for its running clock on an incoming
     * player state update.
     */
    fun shouldUpdatePosition(
        seeking: Boolean,
        wasSeeking: Boolean,
        wasPlaying: Boolean,
        playing: Boolean,
        wasBuffering: Boolean,
        isBuffering: Boolean,
        trackChanged: Boolean,
        remote: Boolean,
        reportedMs: Long,
        predictedMs: Long,
    ): Boolean {
        if (seeking) return false
        if (wasSeeking || trackChanged || !wasPlaying || !playing || wasBuffering || isBuffering) return true
        val threshold = if (remote) REMOTE_DRIFT_SNAP_MS else LOCAL_DRIFT_SNAP_MS
        return abs(reportedMs - predictedMs) >= threshold
    }

    /**
     * Checks whether a received state reflects completion of an outstanding seek, rejecting
     * identical pre-seek snapshots from before the player updated.
     */
    fun seekLanded(
        itemUri: String?,
        seekUri: String,
        statePosMs: Long,
        stateTsMs: Long,
        preSeekPosMs: Long,
        preSeekTsMs: Long,
        projectedAtMs: Long,
        seekTargetMs: Long,
        elapsedSinceSeekMs: Long,
        nearMs: Long,
    ): Boolean {
        if (itemUri != seekUri) return false
        if (stateTsMs > 0L && stateTsMs == preSeekTsMs && statePosMs == preSeekPosMs) return false
        return projectedAtMs >= seekTargetMs - nearMs &&
            projectedAtMs <= seekTargetMs + elapsedSinceSeekMs.coerceAtLeast(0L) + nearMs
    }
}
