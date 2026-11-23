package app.danyfy.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.LibraryChip
import app.danyfy.shared.data.LibrarySort
import app.danyfy.shared.data.SampleCatalog
import app.danyfy.shared.data.SampleHomeItem
import app.danyfy.shared.data.SampleLibraryEntry
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.account_page_title
import app.danyfy.shared.resources.account_title
import app.danyfy.shared.resources.artist_subtitle
import app.danyfy.shared.resources.content_description_context_menu
import app.danyfy.shared.resources.create_playlist_content_description
import app.danyfy.shared.resources.filter_and_sort_sort_section_title
import app.danyfy.shared.resources.grid_view
import app.danyfy.shared.resources.ic_add
import app.danyfy.shared.resources.ic_view_grid
import app.danyfy.shared.resources.ic_view_list
import app.danyfy.shared.resources.library_chip_albums
import app.danyfy.shared.resources.library_chip_artists
import app.danyfy.shared.resources.library_chip_by_me
import app.danyfy.shared.resources.library_chip_playlists
import app.danyfy.shared.resources.library_chip_podcasts
import app.danyfy.shared.resources.library_chip_songs
import app.danyfy.shared.resources.library_search_text_hint
import app.danyfy.shared.resources.library_sort_recently_added
import app.danyfy.shared.resources.library_sort_recents
import app.danyfy.shared.resources.list_view
import app.danyfy.shared.resources.navigationbar_musicappitems_search_title
import app.danyfy.shared.resources.navigationbar_musicappitems_yourlibrary_title
import app.danyfy.shared.resources.settings_title
import app.danyfy.shared.resources.song_dna_search_chip_followed_artists
import app.danyfy.shared.resources.view_profile
import app.danyfy.shared.resources.your_library_sort_order_alphabetical
import app.danyfy.shared.resources.your_library_sort_order_creator
import app.danyfy.shared.resources.your_library_sort_order_custom
import app.danyfy.shared.ui.components.FastScrollbar
import app.danyfy.shared.ui.components.MinTouch
import app.danyfy.shared.ui.components.SampleArtwork
import app.danyfy.shared.ui.components.SearchClearButton
import app.danyfy.shared.ui.components.bottomBarsSpace
import app.danyfy.shared.ui.components.cappedPageWidth
import app.danyfy.shared.ui.components.pressable
import app.danyfy.shared.ui.components.windowSize
import app.danyfy.shared.ui.navigation.Routes
import app.danyfy.shared.ui.navigation.TabReselect
import app.danyfy.shared.ui.navigation.albumRoute
import app.danyfy.shared.ui.navigation.artistRoute
import app.danyfy.shared.ui.navigation.playlistRoute
import app.danyfy.shared.ui.theme.AppAccent
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.AppMuted
import app.danyfy.shared.ui.theme.AppPalette
import app.danyfy.shared.ui.theme.AppText
import app.danyfy.shared.ui.theme.tone
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {
    var showAccount by remember { mutableStateOf(false) }
    var gridView by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var chip by rememberSaveable { mutableStateOf(LibraryChip.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.CUSTOM) }
    var sorting by remember { mutableStateOf(false) }

    if (showAccount) {
        AccountSheet(navController = navController, onDismiss = { showAccount = false })
    }
    if (sorting) {
        LibrarySortSheet(currentSort = sort, onPick = { sort = it }, onDismiss = { sorting = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (gridView) Modifier else Modifier.cappedPageWidth())
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 12.dp, 16.dp, 8.dp),
        ) {
            Text(
                text = stringResource(Res.string.navigationbar_musicappitems_yourlibrary_title),
                fontWeight = FontWeight.Bold,
                color = AppText,
                fontSize = 22.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        TabReselect.emit(Routes.Library.route)
                    },
            )
            Icon(
                painter = painterResource(Res.drawable.ic_add),
                contentDescription = stringResource(Res.string.create_playlist_content_description),
                tint = AppText,
                modifier = Modifier.pressable(0.96f) { }.padding(end = 14.dp).size(22.dp),
            )
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(Res.string.library_search_text_hint),
                tint = if (searchActive) AppPalette else AppText,
                modifier = Modifier
                    .pressable(0.96f) {
                        searchActive = !searchActive
                        if (!searchActive) searchQuery = ""
                    }
                    .padding(end = 14.dp)
                    .size(22.dp),
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tone(0xFF3A3A3A))
                    .clickable { showAccount = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Person, contentDescription = stringResource(Res.string.account_title), tint = AppText, modifier = Modifier.size(20.dp))
            }
        }

        LibraryChipRow(chip) { tapped -> chip = if (chip == tapped) LibraryChip.ALL else tapped }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 0.dp, 16.dp, 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pressable(0.96f) { sorting = true }.padding(vertical = 4.dp),
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null, tint = AppText, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(sortLabelRes(sort)),
                    color = AppText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(if (gridView) Res.drawable.ic_view_list else Res.drawable.ic_view_grid),
                contentDescription = stringResource(if (gridView) Res.string.list_view else Res.string.grid_view),
                tint = AppText,
                modifier = Modifier.pressable(0.96f) { gridView = !gridView }.size(18.dp),
            )
        }

        AnimatedVisibility(visible = searchActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 0.dp, 16.dp, 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tone(0xFF252525))
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                val searchFocus = remember { FocusRequester() }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = AppText, fontSize = 15.sp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp, 10.dp)
                        .focusRequester(searchFocus),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(stringResource(Res.string.navigationbar_musicappitems_search_title), color = Color(0xFF888888), fontSize = 15.sp)
                            }
                            inner()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) SearchClearButton(searchFocus, Color(0xFF888888)) { searchQuery = "" }
            }
        }

        val entries = remember(chip, sort, searchQuery) {
            val base = when (chip) {
                LibraryChip.ALL -> SampleCatalog.libraryEntries
                LibraryChip.PLAYLISTS -> SampleCatalog.libraryEntries.filter { it.isPlaylist }
                LibraryChip.BY_ME -> SampleCatalog.libraryEntries.filter { it.isCreatorMe }
                LibraryChip.ALBUMS -> SampleCatalog.libraryEntries.filter { !it.isPlaylist }
                LibraryChip.ARTISTS -> emptyList()
                LibraryChip.PODCASTS -> emptyList()
                LibraryChip.SONGS -> SampleCatalog.libraryEntries.take(1)
            }
            val searched = if (searchQuery.isBlank()) base else base.filter {
                it.name.contains(searchQuery, ignoreCase = true) || it.subtitle.contains(searchQuery, ignoreCase = true)
            }
            when (sort) {
                LibrarySort.ALPHABETICAL -> searched.sortedBy { it.name.lowercase() }
                LibrarySort.CREATOR -> searched.sortedBy { it.subtitle.lowercase() }
                else -> searched
            }
        }

        val artists = remember(chip, sort, searchQuery) {
            if (chip != LibraryChip.ALL && chip != LibraryChip.ARTISTS) emptyList()
            else {
                val filtered = if (searchQuery.isBlank()) SampleCatalog.artists
                else SampleCatalog.artists.filter { it.name.contains(searchQuery, ignoreCase = true) }
                if (sort == LibrarySort.ALPHABETICAL) filtered.sortedBy { it.name.lowercase() } else filtered
            }
        }

        if (gridView) {
            LibraryGridView(entries, artists, navController)
        } else {
            LibraryListView(entries, artists, navController)
        }
    }
}

@Composable
private fun LibraryChipRow(chosen: LibraryChip, onTap: (LibraryChip) -> Unit) {
    val chips = listOf(
        LibraryChip.PLAYLISTS to Res.string.library_chip_playlists,
        LibraryChip.BY_ME to Res.string.library_chip_by_me,
        LibraryChip.ARTISTS to Res.string.library_chip_artists,
        LibraryChip.ALBUMS to Res.string.library_chip_albums,
        LibraryChip.PODCASTS to Res.string.library_chip_podcasts,
        LibraryChip.SONGS to Res.string.library_chip_songs,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(16.dp, 0.dp, 16.dp, 6.dp),
    ) {
        for ((chip, label) in chips) {
            val on = chip == chosen
            Text(
                stringResource(label),
                color = if (on) Color.Black else AppText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) AppAccent else tone(0xFF2A2A2A))
                    .clickable { onTap(chip) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun LibraryListView(
    entries: List<SampleLibraryEntry>,
    followedArtists: List<SampleHomeItem>,
    navController: NavController,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        TabReselect.library.collect {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 10.dp, bottom = bottomBarsSpace()),
            modifier = Modifier.fillMaxSize().background(AppBackground),
        ) {
            items(entries, key = { it.id }) { entry ->
                val hasMenu = entry.kind == SampleLibraryEntry.LibraryKind.PLAYLIST || entry.kind == SampleLibraryEntry.LibraryKind.ALBUM
                Row(
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .pressable(0.96f) { openLibraryEntry(entry, navController) }
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = if (hasMenu) 14.dp else 20.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    LibraryArtwork(
                        entry = entry,
                        modifier = Modifier.size(55.dp).clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(text = entry.name, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.isPinned) {
                                Icon(Icons.Default.PushPin, null, tint = AppAccent, modifier = Modifier.padding(end = 4.dp).size(12.dp))
                            }
                            Text(text = entry.subtitle, color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (hasMenu) {
                        Icon(
                            Icons.Default.MoreVert,
                            stringResource(Res.string.content_description_context_menu),
                            tint = AppMuted,
                            modifier = Modifier.size(MinTouch).clip(CircleShape).clickable { }.padding(12.dp),
                        )
                    }
                }
            }
            if (followedArtists.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.song_dna_search_chip_followed_artists),
                        color = AppText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp),
                    )
                }
                items(followedArtists, key = { it.id }) { artist ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .pressable(0.96f) { navController.navigate(artistRoute(artist.name, artist.id)) }
                            .fillMaxWidth()
                            .padding(20.dp, 6.dp),
                    ) {
                        SampleArtwork(art = artist.art, shape = CircleShape, modifier = Modifier.size(55.dp))
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(text = artist.name, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(text = stringResource(Res.string.artist_subtitle), color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun LibraryGridView(
    entries: List<SampleLibraryEntry>,
    followedArtists: List<SampleHomeItem>,
    navController: NavController,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        TabReselect.library.collect {
            if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                gridState.animateScrollToItem(0)
            }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(windowSize().columns(3, 170.dp)),
        modifier = Modifier.fillMaxSize().background(AppBackground),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, bottomBarsSpace()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(entries.size, key = { entries[it].id }) { i ->
            val entry = entries[i]
            Column(
                modifier = Modifier.pressable(0.96f) { openLibraryEntry(entry, navController) },
            ) {
                LibraryArtwork(
                    entry = entry,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = entry.name, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = entry.subtitle, color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (followedArtists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(Res.string.song_dna_search_chip_followed_artists),
                    color = AppText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(followedArtists.size, key = { followedArtists[it].id }) { i ->
                val artist = followedArtists[i]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.pressable(0.96f) { navController.navigate(artistRoute(artist.name, artist.id)) },
                ) {
                    SampleArtwork(art = artist.art, shape = CircleShape, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = artist.name, color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(text = stringResource(Res.string.artist_subtitle), color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LibraryArtwork(entry: SampleLibraryEntry, modifier: Modifier) {
    val kind = when (entry.kind) {
        SampleLibraryEntry.LibraryKind.DOWNLOADS -> "downloads"
        SampleLibraryEntry.LibraryKind.LOCAL_FILES -> "local"
        SampleLibraryEntry.LibraryKind.FOLDER -> "folder"
        else -> null
    }
    SampleArtwork(art = entry.art, modifier = modifier, specialKind = kind)
}

private fun openLibraryEntry(entry: SampleLibraryEntry, navController: NavController) {
    when (entry.kind) {
        SampleLibraryEntry.LibraryKind.LIKED -> navController.navigate(Routes.Liked.route)
        SampleLibraryEntry.LibraryKind.LOCAL_FILES -> navController.navigate(Routes.LocalFiles.route)
        SampleLibraryEntry.LibraryKind.DOWNLOADS -> navController.navigate(Routes.Downloads.route)
        SampleLibraryEntry.LibraryKind.ALBUM -> navController.navigate(albumRoute(entry.name, entry.subtitle, entry.id))
        else -> navController.navigate(playlistRoute(entry.id, entry.name))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    navController: NavController,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tone(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp),
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(tone(0xFF3A3A3A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = AppText, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text("Dany", color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("iOS Preview", color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = tone(0xFF2A2A2A))
            AccountRow(stringResource(Res.string.view_profile)) {
                onDismiss()
                navController.navigate(Routes.Profile.route)
            }
            AccountRow(stringResource(Res.string.settings_title)) {
                onDismiss()
                navController.navigate(Routes.Settings.route)
            }
            AccountRow(stringResource(Res.string.account_page_title) + "  \u2197") {
                onDismiss()
            }
            HorizontalDivider(color = tone(0xFF2A2A2A))
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = AppText,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(20.dp, 16.dp),
    )
}

private fun sortLabelRes(sort: LibrarySort) = when (sort) {
    LibrarySort.CUSTOM -> Res.string.your_library_sort_order_custom
    LibrarySort.RECENTS -> Res.string.library_sort_recents
    LibrarySort.RECENTLY_ADDED -> Res.string.library_sort_recently_added
    LibrarySort.ALPHABETICAL -> Res.string.your_library_sort_order_alphabetical
    LibrarySort.CREATOR -> Res.string.your_library_sort_order_creator
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySortSheet(currentSort: LibrarySort, onPick: (LibrarySort) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.filter_and_sort_sort_section_title),
                color = AppText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            for (s in LibrarySort.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(s); onDismiss() }.padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val selected = currentSort == s
                    Text(stringResource(sortLabelRes(s)), color = if (selected) AppAccent else AppText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = AppAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
