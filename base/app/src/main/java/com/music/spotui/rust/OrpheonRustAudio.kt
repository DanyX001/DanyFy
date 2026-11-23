package com.music.spotui.rust

import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer

/**
 * JNI bridge to Orpheon's native Rust audio & SIMD DSP engine (`liborpheon_rust.so`).
 *
 * Provides zero-allocation native execution for:
 * - 10-band Trapezoidal SVF Equalizer + lookahead/hold peak limiter ([EqualizerProcessor])
 * - Mid/Side Spatial Stereo Widener + makeup gain + peak limiter ([SpatialProcessor])
 * - Gated RMS Loudness Normalizer + soft ceiling limiter ([SoundCheckProcessor])
 * - Perceptual OKLab Artwork Palette Extractor & 3-pass Separable Stack Blur
 * - MP4 Widevine PSSH box extraction
 * - Kernel CFS real-time audio thread priority boosting (`ANDROID_PRIORITY_URGENT_AUDIO`)
 */
@Keep
object OrpheonRustAudio {
    val available: Boolean = runCatching {
        try {
            System.loadLibrary("orpheon_rust")
            true
        } catch (_: UnsatisfiedLinkError) {
            // Host JVM unit-test fallback: load the host Linux release build if present.
            val candidates = listOf(
                File("../rust-audio/target/release/liborpheon_rust.so"),
                File("rust-audio/target/release/liborpheon_rust.so"),
                File("/root/Orpheon/base/rust-audio/target/release/liborpheon_rust.so"),
            )
            val hostSo = candidates.firstOrNull { it.isFile }
            if (hostSo != null) {
                try {
                    System.load(hostSo.absolutePath)
                } catch (_: UnsatisfiedLinkError) {
                    // Robolectric SandboxClassLoader and AppClassLoader share a worker JVM; isolate per classloader.
                    val copy = File.createTempFile("orpheon_rust_", ".so").apply { deleteOnExit() }
                    hostSo.copyTo(copy, overwrite = true)
                    System.load(copy.absolutePath)
                }
                true
            } else {
                false
            }
        }
    }.getOrDefault(false)

    fun versionOrNull(): String? = if (available) runCatching { nativeVersion() }.getOrNull() else null

    fun boostAudioThread(): Int = if (available) runCatching { nativeBoostAudioThread() }.getOrDefault(-1) else -1

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeBoostAudioThread(): Int

    @JvmStatic external fun nativeEqCreate(): Long
    @JvmStatic external fun nativeEqDestroy(handle: Long)
    @JvmStatic external fun nativeEqConfigure(handle: Long, channels: Int, sampleRate: Int, isFloat: Boolean)
    @JvmStatic external fun nativeEqFlush(handle: Long, gains: FloatArray?, preampDb: Float)
    @JvmStatic external fun nativeEqReset(handle: Long)
    @JvmStatic external fun nativeEqProcessDirect(
        handle: Long,
        inBuf: ByteBuffer,
        inPos: Int,
        outBuf: ByteBuffer,
        outPos: Int,
        frames: Int,
        hasTarget: Boolean,
        gains: FloatArray?,
        preampTarget: Float,
    ): Boolean

    @JvmStatic external fun nativeSpatialCreate(): Long
    @JvmStatic external fun nativeSpatialDestroy(handle: Long)
    @JvmStatic external fun nativeSpatialConfigure(handle: Long, sampleRate: Int, isFloat: Boolean)
    @JvmStatic external fun nativeSpatialFlush(handle: Long, targetWidth: Float)
    @JvmStatic external fun nativeSpatialReset(handle: Long)
    @JvmStatic external fun nativeSpatialProcessDirect(
        handle: Long,
        inBuf: ByteBuffer,
        inPos: Int,
        outBuf: ByteBuffer,
        outPos: Int,
        frames: Int,
        targetWidth: Float,
    ): Boolean

    @JvmStatic external fun nativeExtractWidevinePssh(initBytes: ByteArray): ByteArray?
    @JvmStatic external fun nativeConfigureBeatDetector(enabled: Boolean, mode: Int, sensitivity: Int, strictRhythm: Boolean)
    @JvmStatic external fun nativeDrainBeatEvents(handle: Long, outEvents: LongArray): Int
    @JvmStatic external fun nativePollBeat(): Int
    @JvmStatic external fun nativeDetectBeatsDirect(handle: Long, buf: ByteBuffer, pos: Int, frames: Int): Int

    @JvmStatic external fun nativeSoundCheckCreate(): Long
    @JvmStatic external fun nativeSoundCheckDestroy(handle: Long)
    @JvmStatic external fun nativeSoundCheckConfigure(handle: Long, channels: Int, sampleRate: Int, isFloat: Boolean)
    @JvmStatic external fun nativeSoundCheckReset(handle: Long, sampleRate: Int)
    @JvmStatic external fun nativeSoundCheckProcessDirect(
        handle: Long,
        inBuf: ByteBuffer,
        inPos: Int,
        outBuf: ByteBuffer,
        outPos: Int,
        frames: Int,
        active: Boolean,
    ): Boolean

    @JvmStatic external fun nativeExtractArtworkColors(pixels: IntArray, width: Int, height: Int): IntArray?
    @JvmStatic external fun nativeBlurArgb(pixels: IntArray, width: Int, height: Int, radius: Int): Boolean
}
