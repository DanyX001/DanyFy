package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * Tracks real-time `AudioTrack` buffer lead between [EqualizerProcessor.queueInput] (which sees
 * decoded PCM 0.5-2.0 seconds ahead of playback) and the audible speaker output reported by
 * Media3's [AudioSink]. Pure-Kotlin time math keeps JVM unit tests independent of Android Stubs.
 */
class AudioSyncClock {
    val sourceToken: Long = NEXT_TOKEN.getAndIncrement()

    @Volatile private var hasSink: Boolean = false
    @Volatile private var sinkPlaying: Boolean = false
    @Volatile private var pausedBySink: Boolean = false
    @Volatile private var speed: Float = 1.0f

    @Volatile private var currentBufferPtsUs: Long = C.TIME_UNSET
    @Volatile private var audiblePtsUs: Long = C.TIME_END_OF_SOURCE
    @Volatile private var prefillStartPtsUs: Long = C.TIME_UNSET
    @Volatile private var prefillStartMonoNs: Long = 0L
    @Volatile private var pausedAtMonoNs: Long = 0L

    internal fun attachSink() {
        hasSink = true
    }

    internal fun onPlay() {
        val now = System.nanoTime()
        if (pausedBySink && pausedAtMonoNs > 0L && prefillStartMonoNs > 0L) {
            // Shift prefill start forward by the pause duration so prefill delay stays accurate.
            prefillStartMonoNs += (now - pausedAtMonoNs).coerceAtLeast(0L)
        } else if (prefillStartPtsUs != C.TIME_UNSET && prefillStartMonoNs == 0L) {
            prefillStartMonoNs = now
        }
        pausedBySink = false
        pausedAtMonoNs = 0L
        sinkPlaying = true
    }

    internal fun onPause() {
        sinkPlaying = false
        pausedBySink = true
        pausedAtMonoNs = System.nanoTime()
    }

    internal fun onFlush() {
        currentBufferPtsUs = C.TIME_UNSET
        audiblePtsUs = C.TIME_END_OF_SOURCE
        prefillStartPtsUs = C.TIME_UNSET
        prefillStartMonoNs = 0L
        pausedAtMonoNs = 0L
    }

    internal fun onReset() {
        onFlush()
        sinkPlaying = false
        pausedBySink = false
    }

    internal fun onDiscontinuity() {
        prefillStartPtsUs = C.TIME_UNSET
        prefillStartMonoNs = 0L
    }

    internal fun setPlaybackSpeed(newSpeed: Float) {
        if (newSpeed > 0.1f && newSpeed <= 8.0f) {
            speed = newSpeed
        }
    }

    internal fun onBeforeSinkBuffer(presentationTimeUs: Long, currentAudibleUs: Long, currentSpeed: Float) {
        hasSink = true
        if (currentSpeed > 0.1f && currentSpeed <= 8.0f) speed = currentSpeed
        if (presentationTimeUs != C.TIME_UNSET && presentationTimeUs != C.TIME_END_OF_SOURCE) {
            currentBufferPtsUs = presentationTimeUs
            if (prefillStartPtsUs == C.TIME_UNSET) {
                prefillStartPtsUs = presentationTimeUs
                prefillStartMonoNs = if (pausedBySink) 0L else System.nanoTime()
            }
        }
        audiblePtsUs = currentAudibleUs
    }

    /**
     * Returns milliseconds from "now" until [frameOffset] inside the current PCM buffer leaves
     * the device speaker, or `-1L` when the attached sink is paused and should not vibrate.
     */
    fun estimateDelayMs(frameOffset: Int, sampleRate: Int): Long {
        if (hasSink && (!sinkPlaying || pausedBySink)) return -1L
        val sr = sampleRate.coerceAtLeast(1)
        val offsetMediaUs = (frameOffset.toLong().coerceAtLeast(0L) * 1_000_000L) / sr
        val curSpeed = speed.coerceIn(0.25f, 4.0f)

        if (!hasSink || currentBufferPtsUs == C.TIME_UNSET) {
            return (offsetMediaUs.toDouble() / curSpeed / 1000.0).roundToLong().coerceIn(0L, MAX_DELAY_MS)
        }

        val eventPtsUs = currentBufferPtsUs + offsetMediaUs
        val audible = audiblePtsUs
        val mediaQueueUs = if (audible != C.TIME_END_OF_SOURCE && audible != C.TIME_UNSET) {
            (eventPtsUs - audible).coerceIn(0L, MAX_MEDIA_LEAD_US)
        } else {
            // AudioTrack hasn't started hardware clock yet (initial ~250-500 ms prefill):
            // estimate elapsed time since the first prefill buffer arrived.
            val anchorPts = prefillStartPtsUs
            val anchorNs = prefillStartMonoNs
            if (anchorPts != C.TIME_UNSET && anchorNs > 0L) {
                val elapsedWallUs = ((System.nanoTime() - anchorNs) / 1000L).coerceAtLeast(0L)
                val estAudibleUs = anchorPts + (elapsedWallUs * curSpeed).roundToLong()
                (eventPtsUs - estAudibleUs).coerceIn(0L, MAX_MEDIA_LEAD_US)
            } else {
                0L
            }
        }
        return (mediaQueueUs.toDouble() / curSpeed / 1000.0).roundToLong().coerceIn(0L, MAX_DELAY_MS)
    }

    private companion object {
        const val MAX_MEDIA_LEAD_US = 2_500_000L
        const val MAX_DELAY_MS = 2_500L
        val NEXT_TOKEN = AtomicLong(1L)
    }
}

/**
 * Wraps a Media3 [AudioSink] to keep [AudioSyncClock] synchronized with the underlying
 * `AudioTrack` hardware playhead and cancel queued haptics immediately on pause, seek, or release.
 */
@UnstableApi
class BeatSyncSink(
    sink: AudioSink,
    private val clock: AudioSyncClock,
) : ForwardingAudioSink(sink) {

    init {
        clock.attachSink()
    }

    override fun play() {
        clock.onPlay()
        super.play()
    }

    override fun pause() {
        clock.onPause()
        BeatHapticsEngine.clearPending(clock.sourceToken)
        super.pause()
    }

    override fun handleDiscontinuity() {
        clock.onDiscontinuity()
        super.handleDiscontinuity()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        clock.setPlaybackSpeed(playbackParameters.speed)
        super.setPlaybackParameters(playbackParameters)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (BeatHapticsEngine.needsPcm) {
            val currentAudible = runCatching { super.getCurrentPositionUs(false) }.getOrDefault(C.TIME_END_OF_SOURCE)
            val curSpeed = runCatching { super.getPlaybackParameters().speed }.getOrDefault(1.0f)
            clock.onBeforeSinkBuffer(presentationTimeUs, currentAudible, curSpeed)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        clock.onFlush()
        BeatHapticsEngine.clearPending(clock.sourceToken)
        super.flush()
    }

    override fun reset() {
        clock.onReset()
        BeatHapticsEngine.clearPending(clock.sourceToken)
        super.reset()
    }

    override fun release() {
        clock.onReset()
        BeatHapticsEngine.clearPending(clock.sourceToken)
        super.release()
    }
}
