package app.danyfy.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.CollectionPage
import app.danyfy.shared.data.PreviewPlayerState
import app.danyfy.shared.data.SampleCatalog
import app.danyfy.shared.data.TrackItem
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.accessibility_navigate_back
import app.danyfy.shared.ui.LocalLibraryStore
import app.danyfy.shared.ui.LocalOfflinePreview
import app.danyfy.shared.ui.LocalPlayerController
import app.danyfy.shared.ui.LocalYtmApi
import app.danyfy.shared.ui.components.LikeButton
import app.danyfy.shared.ui.components.MediaArtwork
import app.danyfy.shared.ui.components.SampleArtwork
import app.danyfy.shared.ui.components.bottomBarsSpace
import app.danyfy.shared.ui.components.pressable
import app.danyfy.shared.ui.components.rememberCoverTint
import app.danyfy.shared.ui.theme.AppAccent
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.AppMuted
import app.danyfy.shared.ui.theme.AppPalette
import app.danyfy.shared.ui.theme.AppText
import app.danyfy.shared.ui.theme.headerTint
import org.jetbrains.compose.resources.stringResource

@Composable
fun CollectionDetailScreen(
    navController: NavController,
    title: String,
    subtitle: String,
    browseId: String = "",
) {
    val ytmApi = LocalYtmApi.current
    val player = LocalPlayerController.current
    val library = LocalLibraryStore.current
    val offline = LocalOfflinePreview.current

    var collectionPage by remember(browseId) { mutableStateOf<CollectionPage?>(null) }
    var isLoading by remember(browseId) { mutableStateOf(browseId.isNotBlank() && !offline) }

    LaunchedEffect(browseId) {
        if (browseId.isBlank() || offline) return@LaunchedEffect
        try {
            isLoading = true
            val page = ytmApi.browseCollection(browseId)
            collectionPage = page
        } catch (_: Throwable) {
        } finally {
            isLoading = false
        }
    }

    val page = collectionPage
    val hasLiveTracks = page != null && page.tracks.isNotEmpty()
    val displayTitle = page?.title?.takeIf { it.isNotBlank() } ?: title
    val displaySubtitle = page?.owner?.takeIf { it.isNotBlank() } ?: subtitle

    val art = SampleCatalog.playlists.first().art
    val topTint = if (page?.artworkUrl != null) {
        headerTint(rememberCoverTint(page.artworkUrl, browseId, displayTitle, art.start), 0.55f)
    } else {
        headerTint(art.start, 0.55f)
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomBarsSpace()),
        modifier = Modifier.fillMaxSize().background(AppBackground),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(topTint, AppBackground)))
                    .statusBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.accessibility_navigate_back), tint = AppText)
                    }
                    Text(
                        text = displayTitle,
                        color = AppText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (page?.artworkUrl != null) {
                    MediaArtwork(
                        artworkUrl = page.artworkUrl,
                        title = displayTitle,
                        id = browseId,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp).size(200.dp),
                    )
                } else {
                    SampleArtwork(
                        art = art,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp).size(200.dp),
                    )
                }

                Text(
                    text = displayTitle,
                    color = AppText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (displaySubtitle.isNotBlank()) {
                    Text(
                        text = displaySubtitle,
                        color = AppMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (isLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = AppAccent, modifier = Modifier.size(36.dp))
                }
            }
        } else if (hasLiveTracks) {
            val tracks = page.tracks
            items(tracks.size, key = { tracks[it].id }) { i ->
                val track = tracks[i]
                val active = player.currentTrack?.id == track.id
                val liked = library.isLiked(track.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(0.98f) { player.playTrack(track, tracks) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    MediaArtwork(
                        artworkUrl = track.artworkUrl,
                        title = track.title,
                        id = track.id,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.size(48.dp),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(track.title, color = if (active) AppPalette else AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (track.explicit) {
                                ExplicitBadge()
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(track.artist, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    LikeButton(liked = liked, size = 22.dp) { library.toggleLike(track) }
                }
            }
        } else {
            // Deterministic offline fallback (used by desktop screenshots)
            items(SampleCatalog.songs.size, key = { SampleCatalog.songs[it].id }) { i ->
                val song = SampleCatalog.songs[i]
                val active = PreviewPlayerState.currentSong.id == song.id
                val liked = PreviewPlayerState.isLiked(song.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(0.98f) { PreviewPlayerState.selectSong(song) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SampleArtwork(art = song.art, shape = RoundedCornerShape(4.dp), modifier = Modifier.size(48.dp))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(song.title, color = if (active) AppPalette else AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (song.explicit) {
                                ExplicitBadge()
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(song.singer, color = AppMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    LikeButton(liked = liked, size = 22.dp) { PreviewPlayerState.toggleLike(song.id) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
