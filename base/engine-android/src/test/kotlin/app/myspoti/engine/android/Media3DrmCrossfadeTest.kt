package app.myspoti.engine.android

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.HttpResponse
import app.myspoti.engine.LicenceChannel
import app.myspoti.engine.PlayableMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3DrmCrossfadeTest {

    private fun media(fileId: String) = PlayableMedia(
        trackUri = "spotify:track:$fileId",
        fileId = fileId,
        format = 10,
        codec = "mp4a.40.2",
        bitrate = 128_000,
        cdnUrls = listOf("https://cdn.example/$fileId"),
        cdnHeaders = emptyMap(),
        initRange = 0L..99L,
        firstSegmentOffset = 100L,
        timescale = 44100,
        segments = listOf(PlayableMedia.Segment(100L..199L, 0L, 44100L * 150)),
        widevinePssh = byteArrayOf(1, 2, 3),
        durationMs = 150_000L,
        gainDb = 0.0,
        expiresAtMs = Long.MAX_VALUE,
    )

    private val noLicence = object : LicenceChannel {
        override suspend fun serviceCertificate(): ByteArray = byteArrayOf()
        override suspend fun licence(challenge: ByteArray): ByteArray = byteArrayOf()
    }

    private fun readField(target: Any, name: String): Any? {
        val f = target::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    private fun waitOnLooper(looper: Looper, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(looper).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        Shadows.shadowOf(looper).idle()
        assertTrue("Condition timed out on looper", condition())
    }

    @Test
    fun fadeOutCurrentAndCancelCrossfadeManageVolumeAndOutgoingPlayer() {
        val context = RuntimeEnvironment.getApplication()
        val looper = Looper.getMainLooper()
        val http = object : Http {
            override suspend fun request(request: HttpRequest) = HttpResponse(200, emptyMap(), byteArrayOf())
        }
        val player = Media3DrmPlayer(
            context = context,
            http = http,
            privacyMode = false,
            looper = looper,
        )
        try {
            Shadows.shadowOf(looper).idle()
            val exo = readField(player, "exo") as ExoPlayer
            assertTrue(player.supportsConcurrentPlayback)

            val fakeItem = CdnItem(media("trackA"), http) {}
            setField(player, "current", fakeItem)

            // fadeOutCurrent arms a single-deck fade that cancelCrossfade cleanly resets to full user volume.
            player.setVolume(32768)
            player.fadeOutCurrent(2_000L)
            waitOnLooper(looper) { readField(player, "singleFadeActive") as Boolean }

            player.cancelCrossfade()
            waitOnLooper(looper) { !(readField(player, "singleFadeActive") as Boolean) }
            assertEquals(32768 / 65535f, exo.volume, 0.001f)

            // Simulating an active outgoing crossfade player: seekTo or cancel releases it and restores volume.
            val mBuild = player::class.java.getDeclaredMethod("buildExo")
            mBuild.isAccessible = true
            val secondary = mBuild.invoke(player) as ExoPlayer
            setField(player, "outgoingExo", secondary)
            setField(player, "crossfadeActive", true)
            setField(player, "crossfadeProgress", 0.5f)
            assertNotNull(readField(player, "outgoingExo"))

            player.seekTo(10_000L)
            waitOnLooper(looper) {
                readField(player, "outgoingExo") == null && !(readField(player, "crossfadeActive") as Boolean)
            }
            assertNull(readField(player, "outgoingExo"))
            assertEquals(32768 / 65535f, exo.volume, 0.001f)
        } finally {
            player.release()
            Handler(looper).post { }
            Shadows.shadowOf(looper).idle()
        }
    }
}
