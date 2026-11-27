// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.spotify

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import app.myspoti.engine.Cluster
import app.myspoti.engine.NativeEngine

/**
 * The account's playback as the native engine knows it, in the app's terms ([PlayerState]): for a
 * player that reads it from the engine instead of the Web Player page ([SpotifyPlayer] while the
 * engine plays the account's songs, the listen mirror on the engine).
 */
object EngineStates {
    private fun kindOf(uri: String) = when {
        uri.startsWith("spotify:ad:") -> ItemKind.AD
        uri.startsWith("spotify:episode:") -> ItemKind.EPISODE
        else -> ItemKind.TRACK
    }

    /** The engine's own song and where it is. Shuffle, repeat and restrictions are not the engine's to say: off and none. */
    fun of(n: NativeEngine.Now): PlayerState = PlayerState(
        item = item(n),
        contextUri = n.contextUri,
        previous = n.previous?.let(::summaryItem),
        next = n.next?.let(::summaryItem),
        isPaused = !n.playing && !n.buffering,
        isBuffering = n.buffering,
        positionMs = n.positionMs,
        timestampMs = n.atMs,
        speed = 1.0,
        shuffle = false,
        repeat = Repeat.OFF,
        restrictions = PlayerRestrictions(),
    )

    /** What another device plays, as the engine's cluster says (no name: the cluster gives only the uri). */
    fun of(c: Cluster): PlayerState = PlayerState(
        item = c.trackUri?.let { uri ->
            NowPlaying(kind = kindOf(uri), uri = uri, uid = null, name = "", artists = emptyList(), album = null, imageUrl = null, durationMs = c.durationMs)
        },
        contextUri = c.contextUri,
        isPaused = c.paused,
        isBuffering = c.isBuffering,
        positionMs = c.positionMs,
        timestampMs = c.localTimestampMs(c.timestampMs),
        speed = if (c.paused) 1.0 else if (c.isBuffering) 0.0 else c.speed,
        shuffle = false,
        repeat = Repeat.OFF,
        restrictions = PlayerRestrictions(),
    )

    private fun item(n: NativeEngine.Now) = NowPlaying(
        kind = kindOf(n.uri), uri = n.uri, uid = null, name = n.name,
        artists = n.authors.map { (uri, name) -> ArtistRef(uri, name) },
        album = n.album.takeIf { it.isNotEmpty() }?.let { AlbumRef(n.albumUri.orEmpty(), it) },
        imageUrl = n.imageUrl, durationMs = n.durationMs, provider = n.provider,
    )

    private fun summaryItem(t: NativeEngine.TrackSummary) = NowPlaying(
        kind = kindOf(t.uri), uri = t.uri, uid = null, name = t.name,
        artists = t.authors.map { (uri, name) -> ArtistRef(uri, name) },
        album = t.album.takeIf { it.isNotEmpty() }?.let { AlbumRef(t.albumUri.orEmpty(), it) },
        imageUrl = t.imageUrl, durationMs = t.durationMs, provider = t.provider,
    )

    /**
     * The state to show while the engine plays the account's songs: the engine's own playback ([n],
     * live, from its player) laid over what the page last said ([page], the account's as Spotify's
     * servers relay it, seconds late: a resume shown six seconds back, a new song eight seconds in).
     *
     * The same song on both: the page's item and options (its artists and album, the songs around
     * it, shuffle, repeat, restrictions) with the engine's play state, place and length. Another song
     * (the page has not caught up yet, or no page at all): the engine's song with the page's options,
     * and the page's song as the one before when the engine moved on to the page's next.
     * Null [n] (the engine holds nothing): the page's state as it is.
     */
    fun overlay(page: PlayerState?, n: NativeEngine.Now?): PlayerState? {
        if (n == null) return page
        val own = of(n)
        val pageItem = page?.item
        if (page == null || pageItem == null) return own.copy(
            shuffle = page?.shuffle ?: own.shuffle, repeat = page?.repeat ?: own.repeat,
        )
        if (pageItem.uri == n.uri) return page.copy(
            item = pageItem.copy(
                durationMs = if (n.durationMs > 0) n.durationMs else pageItem.durationMs,
                provider = n.provider ?: pageItem.provider,
            ),
            contextUri = n.contextUri ?: page.contextUri,
            previous = own.previous ?: page.previous,
            next = own.next ?: page.next,
            isPaused = own.isPaused,
            isBuffering = own.isBuffering,
            positionMs = own.positionMs,
            timestampMs = own.timestampMs,
            // The engine's player runs at its own pace: the clock moves at 1x from its position.
            speed = 1.0,
        )
        val movedOn = page.next?.uri == n.uri
        return own.copy(
            item = if (movedOn) own.item!!.copy(uid = page.next?.uid, explicit = page.next?.explicit ?: false, provider = n.provider ?: page.next?.provider) else own.item,
            contextUri = n.contextUri ?: page.contextUri.takeIf { movedOn },
            contextName = page.contextName.takeIf { movedOn || (n.contextUri != null && n.contextUri == page.contextUri) },
            previous = pageItem.takeIf { movedOn } ?: own.previous,
            next = own.next,
            shuffle = page.shuffle,
            repeat = page.repeat,
            restrictions = page.restrictions,
        )
    }

    /**
     * Lays live Connect [cluster] playback from another active device over [page] (whose WebView
     * Connect updates lag ~6-8 s behind real time). Preserves [page] metadata when the track matches
     * or matches [knownItem] / adjacent tracks, and falls back to [fallbackTimestampMs] when [c] omits
     * a server timestamp.
     */
    fun overlayCluster(
        page: PlayerState?,
        c: Cluster?,
        fallbackTimestampMs: Long = System.currentTimeMillis(),
        knownItem: NowPlaying? = null,
    ): PlayerState? {
        if (c == null || c.activeDeviceId == null) return page
        val uri = c.trackUri ?: return page
        val ts = c.localTimestampMs(fallbackTimestampMs)
        if (uri.startsWith("spotify:ad:")) {
            val baseItem = page?.item?.takeIf { it.kind != ItemKind.AD }
            return (page ?: of(c)).copy(
                item = baseItem,
                contextUri = c.contextUri ?: page?.contextUri,
                isPaused = c.paused,
                isBuffering = true,
                positionMs = if (baseItem != null) page?.positionMs ?: 0L else 0L,
                timestampMs = ts,
                speed = 0.0,
            )
        }
        val effectiveSpeed = when {
            c.paused -> 1.0
            c.isBuffering -> 0.0
            else -> c.speed
        }
        val pageItem = page?.item
        if (page != null && pageItem != null && pageItem.uri == uri) {
            return page.copy(
                item = if (c.durationMs > 0) pageItem.copy(durationMs = c.durationMs) else pageItem,
                contextUri = c.contextUri ?: page.contextUri,
                isPaused = c.paused,
                isBuffering = c.isBuffering,
                positionMs = c.positionMs,
                timestampMs = ts,
                speed = effectiveSpeed,
            )
        }
        val movedOn = page?.next?.uri == uri
        val movedBack = page?.previous?.uri == uri
        val matched = when {
            knownItem?.uri == uri -> knownItem
            movedOn -> page?.next
            movedBack -> page?.previous
            else -> null
        }
        val own = of(c).copy(timestampMs = ts, speed = effectiveSpeed)
        val item = matched?.let {
            if (c.durationMs > 0) it.copy(durationMs = c.durationMs) else it
        } ?: own.item
        val sameContext = movedOn || movedBack || (c.contextUri != null && c.contextUri == page?.contextUri)
        return own.copy(
            item = item,
            contextUri = c.contextUri ?: page?.contextUri.takeIf { sameContext },
            contextName = page?.contextName.takeIf { sameContext },
            previous = if (movedOn) pageItem else page?.previous.takeIf { !movedBack && sameContext },
            next = if (movedBack) pageItem else page?.next.takeIf { !movedOn && sameContext },
            shuffle = page?.shuffle ?: false,
            repeat = page?.repeat ?: Repeat.OFF,
            restrictions = page?.restrictions ?: PlayerRestrictions(),
        )
    }
}
