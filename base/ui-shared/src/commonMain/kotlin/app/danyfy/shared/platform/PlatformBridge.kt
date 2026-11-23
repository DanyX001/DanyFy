package app.danyfy.shared.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred

interface PlatformHttp {
    fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        callback: (statusCode: Int, body: String?, error: String?) -> Unit,
    )

    fun getBytes(
        url: String,
        headers: Map<String, String>,
        callback: (statusCode: Int, base64Data: String?, error: String?) -> Unit,
    )
}

interface PlatformAudio {
    fun playStream(
        url: String,
        headers: Map<String, String>,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationMs: Long,
    )

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
}

interface PlatformAudioCallbacks {
    fun onPlaybackStateChanged(isPlaying: Boolean, isBuffering: Boolean)
    fun onPositionDiscontinuity(positionMs: Long, durationMs: Long)
    fun onTrackEnded()
    fun onPlaybackError(message: String)
}

interface PlatformStorage {
    fun getString(key: String, default: String? = null): String?
    fun setString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun setBoolean(key: String, value: Boolean)
}

interface PlatformYtmAuth {
    fun openSignIn(onSuccess: (cookies: String) -> Unit, onCancel: () -> Unit)
    fun clearCookies()
}

object PlatformRegistry {
    var http: PlatformHttp = NoOpPlatformHttp
    var audio: PlatformAudio = NoOpPlatformAudio
    var storage: PlatformStorage = InMemoryPlatformStorage()
    var auth: PlatformYtmAuth = NoOpPlatformYtmAuth
    var audioCallbacks: PlatformAudioCallbacks? = null

    fun registerIosBridge(
        httpBridge: PlatformHttp,
        audioBridge: PlatformAudio,
        storageBridge: PlatformStorage,
        authBridge: PlatformYtmAuth,
    ) {
        http = httpBridge
        audio = audioBridge
        storage = storageBridge
        auth = authBridge
    }

    suspend fun httpPostJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
    ): Pair<Int, String> {
        val deferred = CompletableDeferred<Pair<Int, String>>()
        http.postJson(url, headers, jsonBody) { status, body, err ->
            if (err != null) {
                deferred.completeExceptionally(RuntimeException("HTTP POST failed: $err"))
            } else {
                deferred.complete(Pair(status, body.orEmpty()))
            }
        }
        return deferred.await()
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun httpGetBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        http.getBytes(url, headers) { status, b64, _ ->
            if (status in 200..299 && !b64.isNullOrBlank()) {
                try {
                    deferred.complete(kotlin.io.encoding.Base64.decode(b64))
                } catch (_: Throwable) {
                    deferred.complete(null)
                }
            } else {
                deferred.complete(null)
            }
        }
        return deferred.await()
    }
}

internal object NoOpPlatformHttp : PlatformHttp {
    override fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        callback: (statusCode: Int, body: String?, error: String?) -> Unit,
    ) {
        callback(500, null, "No PlatformHttp registered")
    }

    override fun getBytes(
        url: String,
        headers: Map<String, String>,
        callback: (statusCode: Int, base64Data: String?, error: String?) -> Unit,
    ) {
        callback(500, null, "No PlatformHttp registered")
    }
}

private object NoOpPlatformAudio : PlatformAudio {
    override fun playStream(
        url: String,
        headers: Map<String, String>,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationMs: Long,
    ) {}
    override fun pause() {}
    override fun resume() {}
    override fun seekTo(positionMs: Long) {}
    override fun stop() {}
}

private object NoOpPlatformYtmAuth : PlatformYtmAuth {
    override fun openSignIn(onSuccess: (cookies: String) -> Unit, onCancel: () -> Unit) {
        onCancel()
    }
    override fun clearCookies() {}
}

class InMemoryPlatformStorage : PlatformStorage {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun setString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun getBoolean(key: String, default: Boolean): Boolean =
        map[key]?.toBooleanStrictOrNull() ?: default
    override fun setBoolean(key: String, value: Boolean) {
        map[key] = value.toString()
    }
}

expect fun currentTimeSeconds(): Long

expect fun decodeImageWithColor(bytes: ByteArray): Pair<ImageBitmap, Color>?

expect fun ensureDefaultPlatformBridge()
