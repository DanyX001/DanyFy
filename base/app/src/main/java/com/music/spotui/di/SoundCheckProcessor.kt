/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.spotui.rust.OrpheonRustAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Global Sound Check toggle read on the audio thread (see EqualizerPref). */
object SoundCheck {
    @Volatile var enabled: Boolean = false
}

/**
 * Apple Music-style Sound Check (loudness normalization):
 * keeps quiet albums and loud masters at a consistent perceived volume (~`-15.5 dBFS` K-trimmed RMS)
 * using a slow gated envelope follower (never pumping silent fades or sub-bass rumble) and a peak
 * ceiling limiter. When disabled, passes audio bit-for-bit untouched.
 */
@UnstableApi
class SoundCheckProcessor(
    private val active: () -> Boolean = { SoundCheck.enabled },
) : BaseAudioProcessor() {
    private var channels = 0
    private var isFloat = false
    private var hpCoeff = 0f
    private var hpStateL = 0f
    private var hpStateR = 0f
    private var fastEnvCoeff = 0f
    private var slowEnvCoeff = 0f
    private var gainCoeff = 0f
    private var releaseCoeff = 0f

    private var rmsSq = TARGET_RMS * TARGET_RMS
    private var currentGain = 1f
    private var limiterGain = 1f
    private var warmupLeft = 0
    private var rustHandle: Long = if (OrpheonRustAudio.available) OrpheonRustAudio.nativeSoundCheckCreate() else 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val ch = inputAudioFormat.channelCount
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) ||
            (ch != 1 && ch != 2) ||
            inputAudioFormat.sampleRate <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = ch
        isFloat = enc == C.ENCODING_PCM_FLOAT
        val sr = inputAudioFormat.sampleRate.toFloat()
        hpCoeff = (1f - exp((-2.0 * PI * 140.0 / sr).toFloat())).coerceIn(0.001f, 0.5f)
        fastEnvCoeff = 1f - exp(-1f / (0.18f * sr))
        slowEnvCoeff = 1f - exp(-1f / (2.4f * sr))
        gainCoeff = 1f - exp(-1f / (0.35f * sr))
        releaseCoeff = 1f - exp(-1f / (0.20f * sr))
        resetState(sr.roundToInt())
        if (OrpheonRustAudio.available && rustHandle == 0L) rustHandle = OrpheonRustAudio.nativeSoundCheckCreate()
        if (rustHandle != 0L) OrpheonRustAudio.nativeSoundCheckConfigure(rustHandle, ch, inputAudioFormat.sampleRate, isFloat)
        return inputAudioFormat
    }

    override fun onFlush() {
        val sr = inputAudioFormat.sampleRate.coerceAtLeast(1)
        resetState(sr)
        if (rustHandle != 0L) OrpheonRustAudio.nativeSoundCheckReset(rustHandle, sr)
    }

    override fun onReset() {
        channels = 0
        hpStateL = 0f; hpStateR = 0f
        rmsSq = TARGET_RMS * TARGET_RMS
        currentGain = 1f
        limiterGain = 1f
        warmupLeft = 0
        if (rustHandle != 0L) {
            OrpheonRustAudio.nativeSoundCheckDestroy(rustHandle)
            rustHandle = 0L
        }
    }

    private fun resetState(sampleRate: Int) {
        hpStateL = 0f; hpStateR = 0f
        rmsSq = TARGET_RMS * TARGET_RMS
        currentGain = 1f
        limiterGain = 1f
        warmupLeft = (sampleRate * 0.65f).roundToInt()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val on = active()
        val frameBytes = channels * if (isFloat) 4 else 2
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return

        if (rustHandle != 0L && inputBuffer.isDirect) {
            val out = replaceOutputBuffer(frames * frameBytes)
            if (out.isDirect && OrpheonRustAudio.nativeSoundCheckProcessDirect(
                    rustHandle,
                    inputBuffer,
                    inputBuffer.position(),
                    out,
                    out.position(),
                    frames,
                    on,
                )
            ) {
                inputBuffer.position(inputBuffer.position() + frames * frameBytes)
                out.position(out.position() + frames * frameBytes)
                out.flip()
                return
            }
        }

        if (!on && abs(currentGain - 1f) < SETTLED && limiterGain > 1f - SETTLED) {
            currentGain = 1f
            limiterGain = 1f
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(frames * frameBytes)
        inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())

        val hpA = hpCoeff
        val fastA = fastEnvCoeff
        val slowA = slowEnvCoeff
        val gA = gainCoeff
        val rel = releaseCoeff

        repeat(frames) {
            val l = if (isFloat) inputBuffer.float else inputBuffer.short * (1f / 32768f)
            val r = if (channels == 2) {
                if (isFloat) inputBuffer.float else inputBuffer.short * (1f / 32768f)
            } else l

            if (on) {
                hpStateL += hpA * (l - hpStateL)
                val hl = l - hpStateL
                val instSq = if (channels == 2) {
                    hpStateR += hpA * (r - hpStateR)
                    val hr = r - hpStateR
                    0.5f * (hl * hl + hr * hr)
                } else {
                    hl * hl
                }
                if (instSq > GATE_SQ) {
                    val envA = if (warmupLeft > 0) {
                        warmupLeft--
                        fastA
                    } else {
                        slowA
                    }
                    rmsSq += envA * (instSq - rmsSq)
                }
                val rms = sqrt(rmsSq.coerceAtLeast(1e-9f))
                val targetGain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
                currentGain += gA * (targetGain - currentGain)
            } else {
                currentGain += gA * (1f - currentGain)
            }

            var outL = l * currentGain
            var outR = r * currentGain
            val peak = max(abs(outL), abs(outR))
            limiterGain += (1f - limiterGain) * rel
            if (peak * limiterGain > CEILING) limiterGain = CEILING / peak
            outL *= limiterGain
            outR *= limiterGain

            if (isFloat) {
                out.putFloat(outL)
                if (channels == 2) out.putFloat(outR)
            } else {
                out.putShort((outL * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                if (channels == 2) out.putShort((outR * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }

    companion object {
        /** ~ -15.5 dBFS weighted RMS target (matches mastered streaming loudness comfortably). */
        internal const val TARGET_RMS = 0.168f
        internal const val MIN_GAIN = 0.35f
        internal const val MAX_GAIN = 2.20f
        /** Ignore pauses / quiet fades below -48 dBFS so silence never pumps gain upward. */
        private const val GATE_SQ = 0.004f * 0.004f
        private const val SETTLED = 0.002f
        /** -0.5 dBFS soft safety ceiling. */
        private const val CEILING = 0.944f
    }
}
