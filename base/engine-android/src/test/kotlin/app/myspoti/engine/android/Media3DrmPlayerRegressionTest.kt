package app.myspoti.engine.android

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import app.myspoti.engine.EngineException
import app.myspoti.engine.Http
import app.myspoti.engine.HttpRequest
import app.myspoti.engine.HttpResponse
import app.myspoti.engine.LicenceChannel
import app.myspoti.engine.PlayableMedia
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Media3DrmPlayerRegressionTest {
    private val noopHttp = object : Http {
        override suspend fun request(request: HttpRequest): HttpResponse = HttpResponse(404, emptyMap(), ByteArray(0))
    }

    private lateinit var player: Media3DrmPlayer

    @Before
    fun setUp() {
        player = Media3DrmPlayer(
            context = RuntimeEnvironment.getApplication(),
            http = noopHttp,
            privacyMode = false,
            looper = Looper.getMainLooper(),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        player.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

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
        timescale = 44_100,
        segments = emptyList(),
        widevinePssh = null,
        durationMs = 1_000L,
        gainDb = null,
        expiresAtMs = Long.MAX_VALUE,
    )

    private fun fieldValue(target: Any, name: String): Any? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    @Test
    fun drmKeepaliveDisabledSoRapidSkipsReleaseCdmSessionsImmediately() {
        val drm = fieldValue(player, "drm") as DefaultDrmSessionManager
        val keepaliveMs = DefaultDrmSessionManager::class.java.getDeclaredField("sessionKeepaliveMs").run {
            isAccessible = true
            getLong(drm)
        }
        assertEquals(C.TIME_UNSET, keepaliveMs)
    }

    @Test
    fun loadDoesNotCancelSameFileIdInFlightLicenceButCancelsDifferentTrack() {
        val drm = fieldValue(player, "drm") as DefaultDrmSessionManager
        val callback = DefaultDrmSessionManager::class.java.getDeclaredField("callback").run {
            isAccessible = true
            get(drm) as MediaDrmCallback
        }

        val firstStarted = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<ByteArray>()
        val firstChannel = object : LicenceChannel {
            override suspend fun licence(challenge: ByteArray): ByteArray {
                firstStarted.countDown()
                return releaseFirst.await()
            }
            override suspend fun serviceCertificate(): ByteArray = ByteArray(0)
        }

        setField(player, "licence", firstChannel)
        setField(player, "pendingLicenceFileId", "fileB")

        val pool = Executors.newSingleThreadExecutor()
        try {
            val resultRef = AtomicReference<ByteArray?>()
            val errorRef = AtomicReference<Throwable?>()
            val doneFirst = CountDownLatch(1)
            pool.submit {
                try {
                    resultRef.set(callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1, 2, 3), "")))
                } catch (t: Throwable) {
                    errorRef.set(t)
                } finally {
                    doneFirst.countDown()
                }
            }

            assertTrue("licence request did not start", firstStarted.await(3, TimeUnit.SECONDS))
            // Promoting preloaded fileB to load must NOT cancel fileB's own in-flight licence job.
            player.load(media("fileB"), 0L, true, firstChannel)
            val expected = byteArrayOf(9, 8, 7)
            releaseFirst.complete(expected)
            assertTrue("licence request did not finish", doneFirst.await(3, TimeUnit.SECONDS))
            assertEquals(null, errorRef.get())
            assertArrayEquals(expected, resultRef.get())

            // Starting a second request for fileB then loading fileC MUST cancel fileB's request promptly.
            val secondStarted = CountDownLatch(1)
            val hungSecond = CompletableDeferred<ByteArray>()
            val secondChannel = object : LicenceChannel {
                override suspend fun licence(challenge: ByteArray): ByteArray {
                    secondStarted.countDown()
                    return hungSecond.await()
                }
                override suspend fun serviceCertificate(): ByteArray = ByteArray(0)
            }
            setField(player, "licence", secondChannel)
            setField(player, "pendingLicenceFileId", "fileB")

            val secondErr = AtomicReference<Throwable?>()
            val doneSecond = CountDownLatch(1)
            pool.submit {
                try {
                    callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(4, 5, 6), ""))
                } catch (t: Throwable) {
                    secondErr.set(t)
                } finally {
                    doneSecond.countDown()
                }
            }
            assertTrue("second licence request did not start", secondStarted.await(3, TimeUnit.SECONDS))
            player.load(media("fileC"), 0L, true, secondChannel)
            assertTrue("superseded licence request was not cancelled", doneSecond.await(3, TimeUnit.SECONDS))
            val cancelCause = generateSequence<Throwable>(secondErr.get()) { it.cause }
                .filterIsInstance<CancelledAudioException>()
                .firstOrNull()
            assertEquals("fileB", cancelCause?.fileId)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            pool.shutdownNow()
        }
    }

    @Test
    fun causeChainPreservesNestedRateLimitAndOnlySuppressesStaleTrackCancellations() {
        val cbError = MediaDrmCallbackException(
            DataSpec(Uri.EMPTY),
            Uri.EMPTY,
            emptyMap(),
            0L,
            EngineException.RateLimited("https://spclient/widevine-license", 2_000L),
        )
        val wrapped = PlaybackException(
            "Media3 DRM failure",
            IllegalStateException("drm session error", cbError),
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        )
        val why = formatPlaybackErrorCause(wrapped)
        assertTrue("expected RateLimited in '$why'", "RateLimited" in why)
        assertTrue("expected 429 in '$why'", "429" in why)

        val liveItemB = CdnItem(media("fileB"), noopHttp) {}
        val errFromA = PlaybackException(
            "stale cancel",
            MediaDrmCallbackException(DataSpec(Uri.EMPTY), Uri.EMPTY, emptyMap(), 0L, CancelledAudioException("licence cancelled", null, "fileA")),
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        )
        assertTrue(isSupersededPlaybackError(loadsPending = 0, current = liveItemB, error = errFromA))

        val errFromLiveB = PlaybackException(
            "live cancel",
            MediaDrmCallbackException(DataSpec(Uri.EMPTY), Uri.EMPTY, emptyMap(), 0L, CancelledAudioException("licence cancelled", null, "fileB")),
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        )
        assertFalse(isSupersededPlaybackError(loadsPending = 0, current = liveItemB, error = errFromLiveB))

        liveItemB.cancel()
        assertTrue(isSupersededPlaybackError(loadsPending = 0, current = liveItemB, error = errFromLiveB))
    }

    @Test
    fun rapidLoadCancelsBlockedCertificateFetchAndDeliversEventsWithoutPendingSuppression() {
        val privacyPlayer = Media3DrmPlayer(
            context = RuntimeEnvironment.getApplication(),
            http = noopHttp,
            privacyMode = true,
            looper = Looper.getMainLooper(),
        )
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val pausedLatch = CountDownLatch(1)
            privacyPlayer.setListener { ev ->
                if (ev is app.myspoti.engine.PlayerEvent.Paused) pausedLatch.countDown()
            }

            val certStarted = CountDownLatch(1)
            val hungCert = CompletableDeferred<ByteArray>()
            val hangingChannel = object : LicenceChannel {
                override suspend fun licence(challenge: ByteArray): ByteArray = ByteArray(0)
                override suspend fun serviceCertificate(): ByteArray {
                    certStarted.countDown()
                    return hungCert.await()
                }
            }
            val quickCertStarted = CountDownLatch(1)
            val quickChannel = object : LicenceChannel {
                override suspend fun licence(challenge: ByteArray): ByteArray = ByteArray(0)
                override suspend fun serviceCertificate(): ByteArray {
                    quickCertStarted.countDown()
                    return ByteArray(0)
                }
            }

            privacyPlayer.load(media("fileA"), 0L, true, hangingChannel)
            assertTrue("certificate fetch did not start", certStarted.await(2, TimeUnit.SECONDS))

            val t0 = System.currentTimeMillis()
            // Rapidly skipping to fileB must abort fileA's 3s cert wait immediately and unsuppress events before prepare().
            privacyPlayer.load(media("fileB"), 0L, true, quickChannel)
            assertTrue("second load stalled behind superseded cert wait", quickCertStarted.await(1500, TimeUnit.MILLISECONDS))

            val deadline = System.currentTimeMillis() + 1_500L
            while (System.currentTimeMillis() < deadline &&
                (fieldValue(privacyPlayer, "current") == null ||
                    (fieldValue(privacyPlayer, "loadsPending") as java.util.concurrent.atomic.AtomicInteger).get() != 0)
            ) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val elapsedMs = System.currentTimeMillis() - t0
            assertTrue("rapid load stalled behind superseded cert wait (${elapsedMs}ms)", elapsedMs < 2_000L)
            assertEquals(0, (fieldValue(privacyPlayer, "loadsPending") as java.util.concurrent.atomic.AtomicInteger).get())

            // Even while exo hasn't reached READY yet, Paused/events must deliver immediately once loadsPending == 0.
            privacyPlayer.pause()
            val pauseDeadline = System.currentTimeMillis() + 1_000L
            while (System.currentTimeMillis() < pauseDeadline && pausedLatch.count > 0) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(0L, pausedLatch.count)
        } finally {
            privacyPlayer.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun dedicatedAudioPlaybackLooperAndAsyncCodecConfiguredWhenLooperOmitted() {
        val devicePlayer = Media3DrmPlayer(
            context = RuntimeEnvironment.getApplication(),
            http = noopHttp,
            privacyMode = false,
            looper = null,
        )
        try {
            val deadline = System.currentTimeMillis() + 3_000L
            var exo: androidx.media3.exoplayer.ExoPlayer? = null
            while (System.currentTimeMillis() < deadline && exo == null) {
                exo = runCatching {
                    fieldValue(devicePlayer, "exo") as? androidx.media3.exoplayer.ExoPlayer
                }.getOrNull()
                if (exo == null) Thread.sleep(10)
            }
            val playerInstance = requireNotNull(exo) { "Media3DrmPlayer did not initialize ExoPlayer" }
            val appHandler = fieldValue(devicePlayer, "handler") as android.os.Handler
            val playbackThread = fieldValue(devicePlayer, "playbackThread") as android.os.HandlerThread

            assertEquals("engine-playback", playbackThread.name)
            assertEquals(playbackThread.looper, playerInstance.playbackLooper)
            assertTrue("playbackLooper must differ from client app looper", playerInstance.playbackLooper !== appHandler.looper)
            assertEquals(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO, playbackThread.threadId.let { android.os.Process.getThreadPriority(it) })

            val renderersCls = Media3DrmPlayer::class.java.declaredClasses.first { it.simpleName == "Renderers" }
            val ctor = renderersCls.getDeclaredConstructor(Media3DrmPlayer::class.java).apply { isAccessible = true }
            val renderers = ctor.newInstance(devicePlayer)
            val codecFactoryMethod = renderersCls.getDeclaredMethod("getCodecAdapterFactory").apply { isAccessible = true }
            val codecFactory = codecFactoryMethod.invoke(renderers) as androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
            val asyncModeField = codecFactory.javaClass.getDeclaredField("asynchronousMode").apply { isAccessible = true }
            assertEquals(1, asyncModeField.getInt(codecFactory))
        } finally {
            devicePlayer.release()
        }
    }
}
