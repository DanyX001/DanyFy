package com.music.spotui.rust

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class OrpheonRustAudioTest {
    @Test
    fun rustLibraryLoadsAndReportsVersion() {
        assertTrue("liborpheon_rust.so should be loaded", OrpheonRustAudio.available)
        val ver = OrpheonRustAudio.versionOrNull()
        assertNotNull(ver)
        assertTrue("unexpected version: $ver", ver!!.startsWith("orpheon-rust-audio 1.0.0"))
    }

    @Test
    fun widevinePsshExtractionMatchesMp4Spec() {
        val widevineUuid = byteArrayOf(
            0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(),
            0x79, 0xd6.toByte(), 0x4a, 0xce.toByte(),
            0xa3.toByte(), 0xc8.toByte(), 0x27, 0xdc.toByte(),
            0xd5.toByte(), 0x1d, 0x21, 0xed.toByte(),
        )
        val payload = byteArrayOf(1, 2, 3, 4)
        val psshSize = 8 + 4 + 16 + 4 + payload.size
        val psshBox = ByteBuffer.allocate(psshSize).apply {
            putInt(psshSize)
            put("pssh".toByteArray(Charsets.ISO_8859_1))
            putInt(0) // version & flags
            put(widevineUuid)
            putInt(payload.size)
            put(payload)
        }.array()

        val moovSize = 8 + psshSize
        val initChunk = ByteBuffer.allocate(16 + moovSize).apply {
            putInt(16)
            put("ftyp".toByteArray(Charsets.ISO_8859_1))
            putLong(0L)
            putInt(moovSize)
            put("moov".toByteArray(Charsets.ISO_8859_1))
            put(psshBox)
        }.array()

        val extracted = OrpheonRustAudio.nativeExtractWidevinePssh(initChunk)
        assertNotNull(extracted)
        assertArrayEquals(psshBox, extracted)
        assertNull(OrpheonRustAudio.nativeExtractWidevinePssh(ByteArray(16)))
    }

    @Test
    fun nativeArtworkPaletteAndBlurExecuteCleanly() {
        val w = 48
        val h = 48
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x < w / 2 && y < h / 2 -> 0xFFE63946.toInt()
                x >= w / 2 && y < h / 2 -> 0xFF457B9D.toInt()
                x < w / 2 -> 0xFF2A9D8F.toInt()
                else -> 0xFFF4A261.toInt()
            }
        }
        val colors = OrpheonRustAudio.nativeExtractArtworkColors(pixels, w, h)
        assertNotNull(colors)
        assertEquals(6, colors!!.size)
        assertTrue(colors.slice(0..3).toSet().size >= 3)

        val blurPixels = IntArray(32 * 32) { 0xFF000000.toInt() }
        for (y in 8 until 24) for (x in 8 until 24) blurPixels[y * 32 + x] = 0xFFFFFFFF.toInt()
        assertTrue(OrpheonRustAudio.nativeBlurArgb(blurPixels, 32, 32, 4))
        val edgeR = (blurPixels[16 * 32 + 7] ushr 16) and 0xFF
        val centerR = (blurPixels[16 * 32 + 16] ushr 16) and 0xFF
        assertTrue("expected blurred edge between dark and bright: edge=$edgeR center=$centerR", edgeR in 25 until centerR)
        assertFalse(OrpheonRustAudio.nativeBlurArgb(blurPixels, 32, 32, 0))
    }

    @Test
    fun nativeSoundCheckNormalizesDirectBuffers() {
        val handle = OrpheonRustAudio.nativeSoundCheckCreate()
        assertTrue(handle != 0L)
        try {
            val sr = 48_000
            val frames = 36_000
            OrpheonRustAudio.nativeSoundCheckConfigure(handle, 2, sr, true)
            val inBuf = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) {
                val s = (sin(2.0 * PI * 440.0 * i / sr) * 0.04).toFloat()
                inBuf.putFloat(s)
                inBuf.putFloat(s)
            }
            inBuf.flip()
            assertTrue(
                OrpheonRustAudio.nativeSoundCheckProcessDirect(
                    handle = handle,
                    inBuf = inBuf,
                    inPos = 0,
                    outBuf = outBuf,
                    outPos = 0,
                    frames = frames,
                    active = true,
                ),
            )
            var lateOutPeak = 0f
            for (i in (frames / 2) until frames) {
                lateOutPeak = maxOf(lateOutPeak, abs(outBuf.getFloat(i * 8)))
            }
            assertTrue("expected native SoundCheck gain boost: $lateOutPeak", lateOutPeak > 0.06f)
        } finally {
            OrpheonRustAudio.nativeSoundCheckDestroy(handle)
        }
    }
}
