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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.PreviewPlayerState
import app.danyfy.shared.data.SampleCatalog
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.accessibility_navigate_back
import app.danyfy.shared.ui.components.LikeButton
import app.danyfy.shared.ui.components.SampleArtwork
import app.danyfy.shared.ui.components.bottomBarsSpace
import app.danyfy.shared.ui.components.pressable
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.AppMuted
import app.danyfy.shared.ui.theme.AppPalette
import app.danyfy.shared.ui.theme.AppText
import app.danyfy.shared.ui.theme.headerTint
import org.jetbrains.compose.resources.stringResource

/**
 * Clean Spotify-styled sub-page used when opening playlists, albums, artists, liked songs, queue or
 * other secondary routes in milestone 1.
 */
@Composable
fun CollectionDetailScreen(
    navController: NavController,
    title: String,
    subtitle: String,
) {
    val art = SampleCatalog.playlists.first().art
    val top = headerTint(art.start, 0.55f)
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomBarsSpace()),
        modifier = Modifier.fillMaxSize().background(AppBackground),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(top, AppBackground)))
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
                        text = title,
                        color = AppText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SampleArtwork(
                    art = art,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp).size(200.dp),
                )
                Text(
                    text = title,
                    color = AppText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = AppMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
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
        item { Spacer(Modifier.height(16.dp)) }
    }
}
