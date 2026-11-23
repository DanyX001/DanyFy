package com.music.spotui.di

import com.music.spotui.rust.OrpheonRustAudio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class BeatHapticsEngineTest {

    @After
    fun tearDown() {
        BeatHapticsEngine.resetStateForTest()
        if (OrpheonRustAudio.available) {
            OrpheonRustAudio.nativeConfigureBeatDetector(false, 4, 1, true)
        }
    }

    @Test
    fun extensionCommandsUpdateConfigAndHonorOwnershipOnStop() {
        val resp = BeatHapticsEngine.handleExtensionCommand(
            "io.danyfy.beathaptics",
            """{"cmd":"configure","enabled":true,"mode":"rhythm_hybrid","rhythmLock":true,"intensity":"strong","sensitivity":"high","motor":"erm_strong","channel":"alarm"}"""
        )
        val obj = Json.parseToJsonElement(resp).jsonObject
        assertEquals(true, obj["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("rhythm_hybrid", obj["mode"]!!.jsonPrimitive.content)
        assertEquals(true, obj["rhythmLock"]!!.jsonPrimitive.boolean)
        assertEquals("strong", obj["intensity"]!!.jsonPrimitive.content)
        assertEquals("high", obj["sensitivity"]!!.jsonPrimitive.content)
        assertEquals("erm_strong", obj["motor"]!!.jsonPrimitive.content)
        assertEquals("alarm", obj["channel"]!!.jsonPrimitive.content)

        // Stopping an unrelated extension must not shut down BeatHapticsEngine
        BeatHapticsEngine.onExtensionStopped("com.other.unrelated")
        assertTrue(BeatHapticsEngine.enabled)

        // Stopping the owning extension shuts it down cleanly
        BeatHapticsEngine.onExtensionStopped("io.danyfy.beathaptics")
        assertFalse(BeatHapticsEngine.enabled)
    }

    @Test
    fun antiSpamSchedulerDeduplicatesOverlapsPrioritizesBassAndClearsPerToken() {
        var fakeNow = 10_000L
        BeatHapticsEngine.monoClockMs = { fakeNow }
        BeatHapticsEngine.configure(
            enabled = true,
            mode = "rhythm_hybrid",
            intensity = "medium",
            sensitivity = "normal",
            motor = "crisp",
            rhythmLock = true,
            extensionId = "io.danyfy.beathaptics",
        )

        // Schedule a vocal accent 500ms ahead on sink token 101
        assertTrue(BeatHapticsEngine.onPcmEvent(101L, strength = 150, kind = BeatHapticsEngine.KIND_VOCAL, delayMs = 500L, trackedBpm = 120.0))
        assertEquals(1, BeatHapticsEngine.pendingCount(101L))

        // Rapid vocal chatter 80ms later must be suppressed
        assertFalse(BeatHapticsEngine.onPcmEvent(101L, strength = 170, kind = BeatHapticsEngine.KIND_VOCAL, delayMs = 580L, trackedBpm = 120.0))

        // A true bass kick colliding 20ms later replaces the queued vocal
        assertTrue(BeatHapticsEngine.onPcmEvent(101L, strength = 220, kind = BeatHapticsEngine.KIND_BASS, delayMs = 520L, trackedBpm = 120.0))
        assertEquals(1, BeatHapticsEngine.pendingCount(101L))

        // A vocal 70ms after the bass kick collides and must be dropped
        assertFalse(BeatHapticsEngine.onPcmEvent(101L, strength = 180, kind = BeatHapticsEngine.KIND_VOCAL, delayMs = 590L, trackedBpm = 120.0))

        // A clear vocal phrase well outside the cross-kind window succeeds
        assertTrue(BeatHapticsEngine.onPcmEvent(101L, strength = 180, kind = BeatHapticsEngine.KIND_VOCAL, delayMs = 850L, trackedBpm = 120.0))
        assertEquals(2, BeatHapticsEngine.pendingCount(101L))

        // Outgoing/other sink token schedules independently and per-token clear preserves active token
        assertTrue(BeatHapticsEngine.onPcmEvent(202L, strength = 210, kind = BeatHapticsEngine.KIND_BASS, delayMs = 1_200L, trackedBpm = 120.0))
        assertEquals(1, BeatHapticsEngine.pendingCount(202L))

        BeatHapticsEngine.clearPending(101L)
        assertEquals(0, BeatHapticsEngine.pendingCount(101L))
        assertEquals(1, BeatHapticsEngine.pendingCount(202L))
    }

    @Test
    fun audioSyncClockCompensatesAudioTrackQueueDelayAndPause() {
        val clock = AudioSyncClock()
        clock.attachSink()
        clock.onPlay()
        clock.onBeforeSinkBuffer(
            presentationTimeUs = 3_500_000L,
            currentAudibleUs = 2_000_000L,
            currentSpeed = 1.0f,
        )
        // 1500ms sink queue + 100ms frame offset = 1600ms
        assertEquals(1_600L, clock.estimateDelayMs(frameOffset = 4_410, sampleRate = 44_100))

        // At 2x speed, wall-clock delay halves
        clock.onBeforeSinkBuffer(
            presentationTimeUs = 3_500_000L,
            currentAudibleUs = 2_000_000L,
            currentSpeed = 2.0f,
        )
        assertEquals(800L, clock.estimateDelayMs(frameOffset = 4_410, sampleRate = 44_100))

        // Pausing immediately inhibits vibration scheduling
        clock.onPause()
        assertEquals(-1L, clock.estimateDelayMs(frameOffset = 0, sampleRate = 44_100))
    }

    @Test
    fun jniRustDetectorDrainsBassAndCenterVocalsWhileRejectingStereoSynth() {
        if (!OrpheonRustAudio.available) return
        val sr = 44_100
        val chunkFrames = 2048
        val buf = ByteBuffer.allocateDirect(chunkFrames * 4).order(ByteOrder.nativeOrder())
        val drained = LongArray(16)

        // 1. Test Bass+Kick in rhythm_hybrid mode
        val hBass = OrpheonRustAudio.nativeEqCreate()
        assertTrue(hBass != 0L)
        try {
            OrpheonRustAudio.nativeEqConfigure(hBass, 2, sr, false)
            OrpheonRustAudio.nativeEqFlush(hBass, null, 0f)
            OrpheonRustAudio.nativeConfigureBeatDetector(true, 4 /* rhythm_hybrid */, 1, true)

            var bassCount = 0
            val totalFrames = sr * 2
            var frameIdx = 0
            while (frameIdx < totalFrames) {
                buf.clear()
                val n = minOf(chunkFrames, totalFrames - frameIdx)
                for (f in 0 until n) {
                    val t = (frameIdx + f).toDouble() / sr
                    val ramp = (t / 0.3).coerceAtMost(1.0)
                    val bass = sin(2.0 * PI * 55.0 * t) * 0.16 * ramp
                    val kick = if (t >= 1.0) {
                        val phase = (t - 1.0) % 0.5
                        if (phase < 0.14) {
                            val freq = 48.0 + 75.0 * exp(-phase * 35.0)
                            sin(2.0 * PI * freq * phase) * exp(-phase * 20.0) * 0.72
                        } else 0.0
                    } else 0.0
                    val s = ((bass + kick).coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
                    buf.putShort(s)
                    buf.putShort(s)
                }
                OrpheonRustAudio.nativeDetectBeatsDirect(hBass, buf, 0, n)
                val c = OrpheonRustAudio.nativeDrainBeatEvents(hBass, drained)
                for (i in 0 until c) {
                    if (frameIdx >= sr && BeatHapticsEngine.unpackKind(drained[i]) == BeatHapticsEngine.KIND_BASS) {
                        bassCount++
                    }
                }
                frameIdx += n
            }
            assertTrue("Expected drained JNI bass kick events, got $bassCount", bassCount >= 1)
        } finally {
            OrpheonRustAudio.nativeConfigureBeatDetector(false, 4, 1, true)
            OrpheonRustAudio.nativeEqReset(hBass)
            OrpheonRustAudio.nativeEqDestroy(hBass)
        }

        // 2. Test Center Vocal mode (stereo synth rejection + center vocal pickup)
        val hVox = OrpheonRustAudio.nativeEqCreate()
        assertTrue(hVox != 0L)
        try {
            OrpheonRustAudio.nativeEqConfigure(hVox, 2, sr, false)
            OrpheonRustAudio.nativeEqFlush(hVox, null, 0f)
            OrpheonRustAudio.nativeConfigureBeatDetector(true, 5 /* vocals */, 1, true)

            var wideSynthTriggers = 0
            var vocalTriggers = 0
            // 1.5s wide stereo synth -> should produce 0 vocal triggers
            var frameIdx = 0
            while (frameIdx < (sr * 1.5).toInt()) {
                buf.clear()
                val n = minOf(chunkFrames, (sr * 1.5).toInt() - frameIdx)
                for (f in 0 until n) {
                    val t = (frameIdx + f).toDouble() / sr
                    val gate = if ((t % 0.5) < 0.25) 0.45 else 0.05
                    val v = sin(2.0 * PI * 900.0 * t) * gate
                    val l = (v.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
                    val r = ((-v).coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
                    buf.putShort(l)
                    buf.putShort(r)
                }
                OrpheonRustAudio.nativeDetectBeatsDirect(hVox, buf, 0, n)
                wideSynthTriggers += OrpheonRustAudio.nativeDrainBeatEvents(hVox, drained)
                frameIdx += n
            }
            assertEquals("Wide stereo synth must not trigger center vocals", 0, wideSynthTriggers)

            // 2.0s center-panned vocal phrase bursts -> should trigger KIND_VOCAL
            frameIdx = 0
            while (frameIdx < sr * 2) {
                buf.clear()
                val n = minOf(chunkFrames, sr * 2 - frameIdx)
                for (f in 0 until n) {
                    val t = (frameIdx + f).toDouble() / sr
                    val ph = t % 0.5
                    val env = if (ph < 0.16) 0.65 * (1.0 - exp(-ph * 80.0)) else 0.04
                    val v = (
                        sin(2.0 * PI * 240.0 * t) * 0.4 +
                            sin(2.0 * PI * 720.0 * t) * 0.6 +
                            sin(2.0 * PI * 1440.0 * t) * 0.45
                        ) * env
                    val s = (v.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
                    buf.putShort(s)
                    buf.putShort(s)
                }
                OrpheonRustAudio.nativeDetectBeatsDirect(hVox, buf, 0, n)
                val c = OrpheonRustAudio.nativeDrainBeatEvents(hVox, drained)
                for (i in 0 until c) {
                    if (frameIdx >= sr / 2 && BeatHapticsEngine.unpackKind(drained[i]) == BeatHapticsEngine.KIND_VOCAL) {
                        vocalTriggers++
                    }
                }
                frameIdx += n
            }
            assertTrue("Expected JNI center vocal triggers, got $vocalTriggers", vocalTriggers >= 1)
        } finally {
            OrpheonRustAudio.nativeConfigureBeatDetector(false, 4, 1, true)
            OrpheonRustAudio.nativeEqReset(hVox)
            OrpheonRustAudio.nativeEqDestroy(hVox)
        }
    }

    @Test
    fun beatSyncSinkSkipsAudioTrackTimestampQueriesWhenBeatPcmDisabled() {
        var posQueries = 0
        var paramQueries = 0
        val delegate = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.exoplayer.audio.AudioSink::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.audio.AudioSink::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCurrentPositionUs" -> {
                    posQueries++
                    250_000L
                }
                "getPlaybackParameters" -> {
                    paramQueries++
                    androidx.media3.common.PlaybackParameters.DEFAULT
                }
                "handleBuffer" -> true
                else -> null
            }
        } as androidx.media3.exoplayer.audio.AudioSink

        val clock = AudioSyncClock()
        val sink = BeatSyncSink(delegate, clock)
        val pcm = ByteBuffer.allocateDirect(64)

        // Default / Beat Haptics off: handleBuffer must not query AudioTrack timestamps per frame.
        assertFalse(BeatHapticsEngine.needsPcm)
        assertTrue(sink.handleBuffer(pcm, 100_000L, 1))
        assertEquals(0, posQueries)
        assertEquals(0, paramQueries)

        // With Beat Haptics active on PCM mode: handleBuffer queries position & speed to align haptics.
        BeatHapticsEngine.configure(
            enabled = true,
            mode = "rhythm_hybrid",
            intensity = "medium",
            sensitivity = "normal",
            motor = "crisp",
            rhythmLock = true,
            extensionId = "io.danyfy.beathaptics",
        )
        assertTrue(BeatHapticsEngine.needsPcm)
        assertTrue(sink.handleBuffer(pcm, 120_000L, 1))
        assertEquals(1, posQueries)
        assertEquals(1, paramQueries)
    }
}
