package com.music.spotui.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myspoti.power.EnergyPolicy
import app.myspoti.power.EnergySettings
import com.bumptech.glide.Glide
import com.music.spotui.data.DataSaver
import com.music.spotui.data.preferences.isCanvasWifiOnly
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun spotifyCanvasTrackId(uri: String): String? =
    uri.removePrefix("spotify:track:")
        .takeIf { uri.startsWith("spotify:track:") && it.isNotBlank() && !it.startsWith("local:") }

internal fun spotifyCanvasTrackIdFromRaw(trackId: String): String =
    trackId.takeIf { it.isNotBlank() && !it.startsWith("local:") }.orEmpty()

/**
 * Lookup key for Canvas queries: preserves Spotify ids, and allows local or metadata keys when
 * an installed Canvas extension can match by song title/artist/album.
 */
internal fun canvasLookupTrackId(
    rawTrackId: String,
    hasExtension: Boolean,
    title: String = "",
    artist: String = "",
): String {
    spotifyCanvasTrackIdFromRaw(rawTrackId).takeIf { it.isNotBlank() }?.let { return it }
    if (!hasExtension) return ""
    if (rawTrackId.isNotBlank()) return rawTrackId
    val normTitle = title.trim().lowercase()
    val normArtist = artist.trim().lowercase()
    return if (normTitle.isNotBlank()) "meta:$normTitle|$normArtist" else ""
}

internal fun isCanvasAllowed(context: Context, policy: EnergyPolicy): Boolean =
    policy.allowCanvas &&
        !DataSaver.active &&
        !(DataSaver.metered && isCanvasWifiOnly(context))

/**
 * Shared by Classic and Immersive mini players: looks up and pre-downloads the active track's
 * Canvas while playing, then warms the next track's cover, palette, and Canvas on unmetered links.
 */
@Composable
internal fun WarmCanvasAndNext(np: SongPlayer.NowPlaying) {
    val context = LocalContext.current
    val energy by EnergySettings.state.collectAsStateWithLifecycle()
    val allowed = isCanvasAllowed(context, energy.policy)
    val extRevision by com.music.spotui.extensions.Extensions.canvasRevision
    val hasExtCanvas = com.music.spotui.extensions.Extensions.hasCanvas()
    val songState = Graph.currentSongState

    val activeTitle = songState.title.value
    val activeArtist = songState.singer.value
    val activeAlbum = songState.album.value
    val activeCover = songState.coverUri.value
    val activeId = canvasLookupTrackId(
        rawTrackId = spotifyCanvasTrackId(np.uri) ?: np.uri.takeIf { it.startsWith("local:") }.orEmpty(),
        hasExtension = hasExtCanvas,
        title = activeTitle,
        artist = activeArtist,
    )

    LaunchedEffect(np.uri, activeId, allowed, extRevision) {
        if (activeId.isBlank() || !allowed) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) {
            try {
                Graph.api.getCanvasUrl(
                    trackId = activeId,
                    title = activeTitle,
                    artist = activeArtist,
                    album = activeAlbum,
                    durationMs = SongPlayer.getDuration().coerceAtLeast(0L),
                    cover = activeCover,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        } ?: return@LaunchedEffect
        CanvasPlayer.preload(context, url, trackId = activeId)
    }

    LaunchedEffect(np.next?.spotifyTrackId, np.next?.title, allowed, extRevision) {
        val next = np.next ?: return@LaunchedEffect
        delay(2_500)
        if (next.coverUri.isNotBlank()) {
            Glide.with(context).downloadOnly().load(next.coverUri).submit()
            Palette().extractFirstColorFromImageUrl(context, next.coverUri) { }
            Palette().extractSecondColorFromCoverUrl(context, next.coverUri) { }
        }
        val nextId = canvasLookupTrackId(next.spotifyTrackId, hasExtCanvas, next.title, next.singer)
        if (nextId.isBlank() || !allowed) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) {
            try {
                Graph.api.getCanvasUrl(
                    trackId = nextId,
                    title = next.title,
                    artist = next.singer,
                    album = next.album,
                    durationMs = next.durationMs.toLong().coerceAtLeast(0L),
                    cover = next.coverUri,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        } ?: return@LaunchedEffect
        if (!DataSaver.metered) CanvasPlayer.warm(context, url, trackId = nextId)
    }
}
