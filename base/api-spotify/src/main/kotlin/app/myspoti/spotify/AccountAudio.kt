package app.myspoti.spotify

/**
 * A player of the account's audio outside the Web Player page (the native engine): while [on],
 * [SpotifyPlayer] sends it the playback commands instead of the page's own player, and the page
 * stays as the account's remote (the state, the queue, the tokens). The page plays itself again
 * when this is off, and for the unheard listen-sync mirror.
 */
interface AccountAudio {
    /** The user turned it on and it can play on this device. */
    val on: Boolean
    /** The Spotify Connect device it registered as, null before it did: this phone, not "another device". */
    val deviceId: String?
    /**
     * It holds the account's playback now (a song loaded on it, playing or paused): the page then
     * sees "another device" play, and that device is this one. False once playback moved away.
     */
    val holdsPlayback: Boolean get() = false
    /**
     * What it plays and where, from its own player, live; null with nothing loaded. [SpotifyPlayer]
     * shows this rather than the page's state of it, which Spotify's servers relay seconds late.
     * Null flow: a player that does not tell (the page's state is shown).
     */
    val now: kotlinx.coroutines.flow.StateFlow<app.myspoti.engine.NativeEngine.Now?>? get() = null
    /** Live Spotify Connect cluster (active device id, remote track/position/paused state), when running. */
    val cluster: kotlinx.coroutines.flow.StateFlow<app.myspoti.engine.Cluster?>? get() = null
    /** True when another Connect device (not this engine or the phone WebView) holds active playback in [cluster]. */
    val hasRemoteActive: Boolean
        get() {
            val activeId = cluster?.value?.activeDeviceId ?: return false
            return activeId != deviceId && !app.myspoti.spotify.bridge.PageDevice.isOwn(activeId)
        }
    /**
     * It plays the listen mirror now (an extension's song, unheard here, see MirrorEngine): not the
     * account's songs the user plays, so its playback is not shown as theirs, and the page's side
     * never repairs, pauses or follows it (a skip chain paused it, and the mirror's song with it).
     */
    val mirrors: Boolean get() = false
    /** True while the native engine is actively recovering from a server-inserted ad break. */
    val isRecoveringAd: Boolean get() = false
    /** [order]: the context's songs in an order of the app's (a sorted list, its own shuffle), null for the list's own. */
    /** [shuffle]: the shuffle the play starts with (the user's last choice); null leaves the account's. */
    suspend fun play(contextUri: String, trackUri: String?, index: Int?, positionMs: Long?, order: List<String>?, shuffle: Boolean? = null)
    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    suspend fun next()
    suspend fun previous()
    /** Commands another active Connect device without transferring playback to this phone. */
    suspend fun remotePlay(
        contextUri: String,
        trackUri: String? = null,
        index: Int? = null,
        positionMs: Long? = null,
        order: List<String>? = null,
        shuffle: Boolean? = null,
        targetDeviceId: String? = null,
    ): Boolean = false
    suspend fun remotePause(targetDeviceId: String? = null): Boolean = false
    suspend fun remoteResume(targetDeviceId: String? = null): Boolean = false
    suspend fun remoteSeek(positionMs: Long, targetDeviceId: String? = null): Boolean = false
    suspend fun remoteNext(targetDeviceId: String? = null): Boolean = false
    suspend fun remotePrevious(positionMs: Long = 0L, targetDeviceId: String? = null): Boolean = false
    /** Crossfade duration between consecutive tracks in seconds (0 = off). */
    fun setCrossfadeSeconds(seconds: Int) {}

    suspend fun skipTo(
        contextUri: String?,
        trackUri: String,
        trackUid: String? = null,
        provider: String? = null,
        order: List<String>? = null,
        shuffle: Boolean? = null,
        steps: Int? = null,
    )
}
