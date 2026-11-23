/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.local.ListenHistory
import com.music.spotui.ui.viewmodel.LyricsViewModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleFeaturesAndDspTest {
    private fun stereoPcm16Buffer(frames: Int, sampleRate: Int, freqHz: Double, ampL: Double, ampR: Double): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = sin(2.0 * PI * freqHz * i / sampleRate)
            val sl = (s * ampL * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            val sr = (s * ampR * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            buf.putShort(sl)
            buf.putShort(sr)
        }
        buf.flip()
        return buf
    }

    private fun drainRms(out: ByteBuffer, skipFrames: Int = 512): Double {
        out.order(ByteOrder.nativeOrder())
        val totalFrames = out.remaining() / 4
        var sumSq = 0.0
        var count = 0
        for (i in 0 until totalFrames) {
            val l = out.short / 32768.0
            val r = out.short / 32768.0
            if (i >= skipFrames) {
                sumSq += 0.5 * (l * l + r * r)
                count++
            }
        }
        return if (count == 0) 0.0 else sqrt(sumSq / count)
    }

    @Test
    fun soundCheckNormalizesQuietTracksWithoutClippingLoudPeaks() {
        var enabled = false
        val proc = SoundCheckProcessor { enabled }
        val fmt = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        proc.configure(fmt)
        proc.flush()

        // Off = bit-for-bit identical.
        val rawIn = stereoPcm16Buffer(1024, 44_100, 1000.0, 0.2, 0.2)
        val copy = ByteArray(rawIn.remaining()).also { rawIn.duplicate().get(it) }
        proc.queueInput(rawIn)
        val outOff = proc.output
        val got = ByteArray(outOff.remaining()).also { outOff.get(it) }
        assertArrayEquals(copy, got)

        // Quiet track (0.04 amp) -> Sound Check boosts toward target loudness after warmup.
        enabled = true
        proc.flush()
        val quiet = stereoPcm16Buffer(32_768, 44_100, 1000.0, 0.04, 0.04)
        val inQuietRms = drainRms(quiet.duplicate(), skipFrames = 16_000)
        proc.queueInput(quiet)
        val outQuietRms = drainRms(proc.output, skipFrames = 16_000)
        assertTrue("Quiet audio should be lifted ($outQuietRms > $inQuietRms)", outQuietRms > inQuietRms * 1.4)

        // Hot track (0.95 amp) -> Sound Check lowers level cleanly.
        proc.flush()
        val hot = stereoPcm16Buffer(32_768, 44_100, 1000.0, 0.95, 0.95)
        val inHotRms = drainRms(hot.duplicate(), skipFrames = 16_000)
        proc.queueInput(hot)
        val outHotRms = drainRms(proc.output, skipFrames = 16_000)
        assertTrue("Loud audio should be attenuated safely ($outHotRms < $inHotRms)", outHotRms < inHotRms * 0.6)
    }

    @Test
    fun listenHistorySessionRecordsDeduplicatesAndExcludesPlayingTrack() {
        ListenHistory.recordSession(
            ListenHistory.Listen(1L, "spotify:track:a", "Track A", "Artist A", "Album A", "", 0L, ListenHistory.Source.SPOTIFY),
        )
        ListenHistory.recordSession(
            ListenHistory.Listen(2L, "spotify:track:b", "Track B", "Artist B", "Album B", "", 0L, ListenHistory.Source.SPOTIFY),
        )
        ListenHistory.recordSession(
            ListenHistory.Listen(3L, "spotify:track:a", "Track A", "Artist A", "Album A", "", 0L, ListenHistory.Source.SPOTIFY),
        )
        val combined = ListenHistory.recentCombined(excludeKey = "spotify:track:b")
        assertTrue(combined.any { it.key == "spotify:track:a" })
        assertTrue(combined.none { it.key == "spotify:track:b" })
        assertEquals("spotify:track:a", combined.first().key)
    }

    @Test
    fun translationButtonHidesEnglishSameLanguageAndUntranslatableLyrics() {
        val sampleLines = listOf(LyricLine(0L, "Line one"), LyricLine(2000L, "Line two"))
        val instrumental = listOf(LyricLine(0L, "♪"))
        val baseLyrics = Lyrics(lines = sampleLines, synced = true, language = "en")
        val roLyrics = Lyrics(lines = sampleLines, synced = true, language = "ro")
        val jaLyrics = Lyrics(lines = sampleLines, synced = true, language = "ja")
        val instLyrics = Lyrics(lines = instrumental, synced = false, language = "ro")
        val uncheckedNull = Lyrics(lines = sampleLines, synced = true, language = null)

        // English lyrics stay hidden regardless of target language.
        assertEquals(
            false,
            LyricsViewModel.shouldOfferTranslation(
                LyricsViewModel.State.Loaded(baseLyrics, sourceLanguage = "en", translationChecked = true),
                targetLanguage = "ro",
            ),
        )
        // Untranslatable / unchecked / instrumental stay hidden.
        assertEquals(
            false,
            LyricsViewModel.shouldOfferTranslation(
                LyricsViewModel.State.Loaded(uncheckedNull, sourceLanguage = null, translationChecked = false),
                targetLanguage = "en",
            ),
        )
        assertEquals(
            false,
            LyricsViewModel.shouldOfferTranslation(
                LyricsViewModel.State.Loaded(instLyrics, sourceLanguage = "ro", translationChecked = true),
                targetLanguage = "en",
            ),
        )
        // Same language as target stays hidden.
        assertEquals(
            false,
            LyricsViewModel.shouldOfferTranslation(
                LyricsViewModel.State.Loaded(roLyrics, sourceLanguage = "ro", translationChecked = true),
                targetLanguage = "ro",
            ),
        )
        // Non-English foreign lyrics when target != source DO show Translate.
        assertEquals(
            true,
            LyricsViewModel.shouldOfferTranslation(
                LyricsViewModel.State.Loaded(jaLyrics, sourceLanguage = "ja", translationChecked = true),
                targetLanguage = "en",
            ),
        )
        assertEquals(
            true,
            LyricsViewModel.shouldOfferTranslation(
                LyricsViewModel.State.Loaded(roLyrics, sourceLanguage = "ro", translationChecked = true),
                targetLanguage = "en",
            ),
        )
    }
}
