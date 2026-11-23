package app.danyfy.shared.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.PreviewPlayerState
import app.danyfy.shared.data.SampleArt
import app.danyfy.shared.data.SampleCatalog
import app.danyfy.shared.data.SampleHomeItem
import app.danyfy.shared.data.SampleSong
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.album
import app.danyfy.shared.resources.aligned_curation_first_save_default_row_remove_action_description
import app.danyfy.shared.resources.artist_subtitle
import app.danyfy.shared.resources.assisted_curation_search_filter_albums
import app.danyfy.shared.resources.assisted_curation_search_filter_artists
import app.danyfy.shared.resources.assisted_curation_search_filter_playlists
import app.danyfy.shared.resources.assisted_curation_search_filter_songs
import app.danyfy.shared.resources.assisted_curation_search_filter_top
import app.danyfy.shared.resources.assisted_curation_search_playlist
import app.danyfy.shared.resources.assisted_curation_search_search_history_title
import app.danyfy.shared.resources.assisted_curation_search_song
import app.danyfy.shared.resources.browse_all
import app.danyfy.shared.resources.chat_content_picker_clear_recent_searches
import app.danyfy.shared.resources.ic_search_big
import app.danyfy.shared.resources.navigationbar_musicappitems_search_title
import app.danyfy.shared.resources.search_header_field_hint
import app.danyfy.shared.resources.voice_search
import app.danyfy.shared.ui.components.LikeButton
import app.danyfy.shared.ui.components.SampleArtwork
import app.danyfy.shared.ui.components.SearchClearButton
import app.danyfy.shared.ui.components.WindowSize
import app.danyfy.shared.ui.components.bottomBarsSpace
import app.danyfy.shared.ui.components.pressable
import app.danyfy.shared.ui.navigation.Routes
import app.danyfy.shared.ui.navigation.TabReselect
import app.danyfy.shared.ui.navigation.albumRoute
import app.danyfy.shared.ui.navigation.artistRoute
import app.danyfy.shared.ui.navigation.categoryRoute
import app.danyfy.shared.ui.navigation.playlistRoute
import app.danyfy.shared.ui.theme.AppAccent
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.AppMuted
import app.danyfy.shared.ui.theme.AppPalette
import app.danyfy.shared.ui.theme.AppText
import app.danyfy.shared.ui.theme.tone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

enum class SearchFilter(val label: StringResource) {
    Top(Res.string.assisted_curation_search_filter_top),
    Songs(Res.string.assisted_curation_search_filter_songs),
    Artists(Res.string.assisted_curation_search_filter_artists),
    Albums(Res.string.assisted_curation_search_filter_albums),
    Playlists(Res.string.assisted_curation_search_filter_playlists),
}

private sealed class SearchRow {
    data class Song(val song: SampleSong) : SearchRow()
    data class Artist(val artist: SampleHomeItem) : SearchRow()
    data class Album(val album: SampleHomeItem) : SearchRow()
    data class Playlist(val playlist: SampleHomeItem) : SearchRow()
}

private data class RecentEntry(val key: String, val type: String, val name: String, val singer: String, val art: SampleArt)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.Top) }
    var searchFocused by remember { mutableStateOf(false) }
    var recents by remember {
        mutableStateOf(
            listOf(
                RecentEntry("t1", "song", SampleCatalog.songs[0].title, SampleCatalog.songs[0].singer, SampleCatalog.songs[0].art),
                RecentEntry("art1", "artist", SampleCatalog.artists[0].name, "", SampleCatalog.artists[0].art),
                RecentEntry("alb1", "album", SampleCatalog.albums[0].name, "Klara Lind & The Echoes", SampleCatalog.albums[0].art),
            )
        )
    }

    val listState = rememberLazyListState()
    val searchFieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        TabReselect.search.collect {
            runCatching { searchFieldFocus.requestFocus() }
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = { keyboard?.hide(); focusManager.clearFocus() }

    val matchingRows = remember(query, filter) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else {
            val songs = SampleCatalog.songs.filter {
                it.title.contains(q, ignoreCase = true) || it.singer.contains(q, ignoreCase = true)
            }.map { SearchRow.Song(it) }
            val artists = SampleCatalog.artists.filter { it.name.contains(q, ignoreCase = true) }.map { SearchRow.Artist(it) }
            val albums = SampleCatalog.albums.filter { it.name.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true) }.map { SearchRow.Album(it) }
            val playlists = SampleCatalog.playlists.filter { it.name.contains(q, ignoreCase = true) }.map { SearchRow.Playlist(it) }
            when (filter) {
                SearchFilter.Top -> songs + artists + albums + playlists
                SearchFilter.Songs -> songs
                SearchFilter.Artists -> artists
                SearchFilter.Albums -> albums
                SearchFilter.Playlists -> playlists
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomBarsSpace()),
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
        ) {
            item {
                SearchTopBar()
            }
            stickyHeader {
                SearchStickyBar(
                    text = query,
                    onFocusChange = { searchFocused = it },
                    focusRequester = searchFieldFocus,
                    onTextChange = { query = it },
                )
            }
            if (query.isBlank()) {
                if (searchFocused && recents.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(Res.string.assisted_curation_search_search_history_title),
                                color = AppText,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(Res.string.chat_content_picker_clear_recent_searches),
                                color = AppMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.pressable(0.96f) { recents = emptyList() },
                            )
                        }
                    }
                    items(recents.size, key = { recents[it].key }) { i ->
                        val item = recents[i]
                        RecentItemRow(
                            item = item,
                            onClick = {
                                dismissKeyboard()
                                when (item.type) {
                                    "song" -> SampleCatalog.songs.firstOrNull { it.id == item.key }?.let { PreviewPlayerState.selectSong(it) }
                                    "artist" -> navController.navigate(artistRoute(item.name, item.key))
                                    "album" -> navController.navigate(albumRoute(item.name, item.singer, item.key))
                                    "playlist" -> navController.navigate(playlistRoute(item.key, item.name))
                                }
                            },
                            onRemove = { recents = recents.filterNot { it.key == item.key } },
                        )
                    }
                } else {
                    item {
                        BrowseAllSection { genre, title ->
                            if (genre == "new-releases") navController.navigate(Routes.NewReleases.route)
                            else navController.navigate(categoryRoute(genre, title))
                        }
                    }
                }
            } else {
                item { SearchFilterChips(filter) { filter = it } }
                if (matchingRows.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp, 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Couldn't find \"${query.trim()}\"",
                                color = AppText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(matchingRows.size) { i ->
                        when (val row = matchingRows[i]) {
                            is SearchRow.Song -> SearchSongRow(row.song) {
                                dismissKeyboard()
                                PreviewPlayerState.selectSong(row.song)
                            }
                            is SearchRow.Artist -> SearchSimpleRow(
                                title = row.artist.name,
                                subtitle = stringResource(Res.string.artist_subtitle),
                                art = row.artist.art,
                                round = true,
                            ) {
                                dismissKeyboard()
                                navController.navigate(artistRoute(row.artist.name, row.artist.id))
                            }
                            is SearchRow.Album -> SearchSimpleRow(
                                title = row.album.name,
                                subtitle = stringResource(Res.string.album) + " • ${row.album.subtitle.removePrefix("Album • ")}",
                                art = row.album.art,
                                round = false,
                            ) {
                                dismissKeyboard()
                                navController.navigate(albumRoute(row.album.name, row.album.subtitle, row.album.id))
                            }
                            is SearchRow.Playlist -> SearchSimpleRow(
                                title = row.playlist.name,
                                subtitle = stringResource(Res.string.assisted_curation_search_playlist) + " • ${row.playlist.subtitle}",
                                art = row.playlist.art,
                                round = false,
                            ) {
                                dismissKeyboard()
                                navController.navigate(playlistRoute(row.playlist.id, row.playlist.name))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTopBar() {
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.navigationbar_musicappitems_search_title),
            color = AppText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SearchStickyBar(
    text: String,
    onFocusChange: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
    onTextChange: (String) -> Unit,
) {
    val ownFocus = remember { FocusRequester() }
    val fieldFocus = focusRequester ?: ownFocus
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .height(55.dp)
                .background(tone(0xFF2A2A2A))
                .padding(10.dp, 0.dp),
        ) {
            Icon(
                painterResource(Res.drawable.ic_search_big),
                tint = AppText,
                contentDescription = "",
            )
            TextField(
                enabled = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                value = text,
                textStyle = TextStyle.Default.copy(fontSize = 16.sp, color = AppText, fontWeight = FontWeight(500)),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = AppText,
                    unfocusedTextColor = AppText,
                    cursorColor = AppPalette,
                ),
                singleLine = true,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        textAlign = TextAlign.Start,
                        color = AppMuted,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        text = stringResource(Res.string.search_header_field_hint),
                    )
                },
                trailingIcon = if (text.isEmpty()) null else {
                    { SearchClearButton(fieldFocus, AppText) { onTextChange("") } }
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(55.dp)
                .clip(CircleShape)
                .background(tone(0xFF2A2A2A))
                .pressable(0.9f) { },
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = stringResource(Res.string.voice_search),
                tint = AppText,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BrowseAllSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    val tiles = SampleCatalog.browseCategories
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.browse_all),
            color = AppText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = WindowSize.columnsIn(maxWidth, 2, 220.dp)
            Column(Modifier.fillMaxWidth()) {
                tiles.chunked(cols).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp, 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowItems.forEach { t ->
                            BrowseCategoryTile(t.title, t.color, t.art, Modifier.weight(1f)) {
                                onCategoryClick(t.key, t.title)
                            }
                        }
                        repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    art: SampleArt,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        SampleArtwork(
            art = art,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(64.dp)
                .offset(x = 16.dp, y = 10.dp)
                .rotate(25f),
        )
        Text(
            text = name,
            color = AppText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SearchFilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        val filters = SearchFilter.entries
        items(filters.size) { i ->
            val f = filters[i]
            val on = f == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) AppAccent else tone(0xFF2A2A2A))
                    .pressable(0.96f) { onSelect(if (on && f != SearchFilter.Top) SearchFilter.Top else f) }
                    .padding(horizontal = 14.dp),
            ) {
                Text(stringResource(f.label), color = if (on) Color.Black else AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SearchSongRow(song: SampleSong, onClick: () -> Unit) {
    val active = PreviewPlayerState.currentSong.id == song.id
    val liked = PreviewPlayerState.isLiked(song.id)
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(0.98f, onClick = onClick)
            .padding(16.dp, 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        ) {
            SampleArtwork(
                art = song.art,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(end = 10.dp).size(48.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = if (active) AppPalette else AppText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.explicit) {
                        ExplicitBadge()
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = stringResource(Res.string.assisted_curation_search_song) + " • ${song.singer}",
                        color = AppMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LikeButton(liked = liked, size = 22.dp) {
            PreviewPlayerState.toggleLike(song.id)
        }
    }
}

@Composable
private fun SearchSimpleRow(
    title: String,
    subtitle: String,
    art: SampleArt,
    round: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f, onClick = onClick).fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        SampleArtwork(
            art = art,
            shape = if (round) CircleShape else RoundedCornerShape(6.dp),
            modifier = Modifier.padding(end = 10.dp).size(48.dp),
        )
        Column {
            Text(text = title, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = subtitle, color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecentItemRow(
    item: RecentEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressable(0.96f, onClick = onClick).fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        SampleArtwork(
            art = item.art,
            shape = RoundedCornerShape(if (item.type == "artist") 100.dp else 6.dp),
            modifier = Modifier.size(48.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
            Text(text = item.name, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when (item.type) {
                "song" -> stringResource(Res.string.assisted_curation_search_song) + " • ${item.singer}"
                "artist" -> stringResource(Res.string.artist_subtitle)
                "album" -> stringResource(Res.string.album) + " • ${item.singer}"
                else -> stringResource(Res.string.assisted_curation_search_playlist)
            }
            Text(text = subtitle, color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            imageVector = Icons.Default.Close,
            tint = AppMuted,
            modifier = Modifier.pressable(0.96f, onClick = onRemove).size(20.dp),
            contentDescription = stringResource(Res.string.aligned_curation_first_save_default_row_remove_action_description),
        )
    }
}
