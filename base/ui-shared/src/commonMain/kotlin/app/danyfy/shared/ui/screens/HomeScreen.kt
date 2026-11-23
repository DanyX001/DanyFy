package app.danyfy.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.SampleCatalog
import app.danyfy.shared.data.SampleHomeItem
import app.danyfy.shared.data.SampleHomeSection
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.about_detail
import app.danyfy.shared.resources.account_page_detail
import app.danyfy.shared.resources.account_page_title
import app.danyfy.shared.resources.accounts_switch_row
import app.danyfy.shared.resources.ad_category_title_podcasts
import app.danyfy.shared.resources.artist_subtitle
import app.danyfy.shared.resources.audiobooks_title
import app.danyfy.shared.resources.connect_device_menu_logout
import app.danyfy.shared.resources.creator_biography_section_title
import app.danyfy.shared.resources.extensions_row_detail
import app.danyfy.shared.resources.extensions_title
import app.danyfy.shared.resources.filter_chip_all_label
import app.danyfy.shared.resources.friends_title
import app.danyfy.shared.resources.ic_friends_big
import app.danyfy.shared.resources.recap_detail
import app.danyfy.shared.resources.recap_title
import app.danyfy.shared.resources.recents_content_filter_music
import app.danyfy.shared.resources.recents_detail
import app.danyfy.shared.resources.recents_title
import app.danyfy.shared.resources.settings_detail
import app.danyfy.shared.resources.settings_title
import app.danyfy.shared.resources.support_row_detail
import app.danyfy.shared.resources.support_title
import app.danyfy.shared.resources.view_profile
import app.danyfy.shared.resources.whats_new_title
import app.danyfy.shared.ui.components.SampleArtwork
import app.danyfy.shared.ui.components.bottomBarsSpace
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
import app.danyfy.shared.ui.theme.AppText
import app.danyfy.shared.ui.theme.GridBackground
import app.danyfy.shared.ui.theme.tone
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(navController: NavController) {
    var facet by remember { mutableStateOf("") }
    val shortcuts = remember(facet) {
        if (facet.isBlank()) SampleCatalog.shortcuts
        else SampleCatalog.shortcuts.take(6)
    }
    val sections = remember(facet) {
        when (facet) {
            "podcasts-chip" -> SampleCatalog.homeSections.take(1).map {
                it.copy(title = "Podcasts & Episodes")
            }
            "audiobooks-chip" -> SampleCatalog.homeSections.take(1).map {
                it.copy(title = "Audiobooks")
            }
            else -> SampleCatalog.homeSections
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        TabReselect.home.collect {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
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
        ) {
            stickyHeader {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to AppBackground,
                                0.55f to AppBackground.copy(alpha = 0.85f),
                                1f to Color.Transparent,
                            )
                        )
                        .statusBarsPadding()
                        .padding(bottom = 10.dp)
                ) {
                    HomeHeaderRow(navController, facet) { facet = it }
                }
            }
            item {
                HomeShortcutGrid(navController, shortcuts)
            }
            items(sections.size, key = { sections[it].title }) { i ->
                HomeFeedSection(navController, sections[i])
            }
        }
    }
}

@Composable
private fun HomeHeaderRow(navController: NavController, facet: String, onFacet: (String) -> Unit) {
    var showProfile by remember { mutableStateOf(false) }
    if (showProfile) {
        ProfileSheet(
            onOpen = { route -> showProfile = false; navController.navigate(route) },
            onDismiss = { showProfile = false },
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showProfile = true }
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8622C)),
            ) {
                Text(
                    text = "D",
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        WhatsNewBell { navController.navigate(Routes.WhatsNew.route) }
        Box(
            modifier = Modifier
                .padding(vertical = 10.dp)
                .size(width = 1.dp, height = 22.dp)
                .background(tone(0xFF3E3E3E)),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            val labels = mapOf(
                "music-chip" to stringResource(Res.string.recents_content_filter_music),
                "podcasts-chip" to stringResource(Res.string.ad_category_title_podcasts),
                "audiobooks-chip" to stringResource(Res.string.audiobooks_title),
            )
            if (facet.isEmpty()) {
                HomePill(stringResource(Res.string.filter_chip_all_label), selected = true) {}
                HomePill(labels.getValue("music-chip")) { onFacet("music-chip") }
                HomePill(labels.getValue("podcasts-chip")) { onFacet("podcasts-chip") }
                HomePill(labels.getValue("audiobooks-chip")) { onFacet("audiobooks-chip") }
            } else {
                val subs = when (facet) {
                    "music-chip" -> listOf("music-following" to "Following")
                    else -> emptyList()
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.clip(CircleShape).background(tone(0xFF2A2A2A)).clickable { onFacet("") }.padding(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.filter_chip_all_label),
                        tint = AppText,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HomePill(labels[facet].orEmpty(), selected = true, shape = if (subs.isEmpty()) RoundedCornerShape(50) else JoinedStart) {
                        onFacet(facet)
                    }
                    subs.forEachIndexed { i, (id, label) ->
                        val shown = remember(id) { MutableTransitionState(false).apply { targetState = true } }
                        AnimatedVisibility(
                            visibleState = shown,
                            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                        ) {
                            HomePill(label, selected = false, shape = if (i == subs.lastIndex) JoinedEnd else RoundedCornerShape(4.dp)) {}
                        }
                    }
                }
            }
        }
        IconButton(onClick = { navController.navigate(Routes.Friends.route) }) {
            Icon(
                painterResource(Res.drawable.ic_friends_big),
                contentDescription = stringResource(Res.string.friends_title),
                tint = AppText,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private val JoinedStart = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = 15, bottomEndPercent = 15)
private val JoinedEnd = RoundedCornerShape(topStartPercent = 15, bottomStartPercent = 15, topEndPercent = 50, bottomEndPercent = 50)

@Composable
private fun HomePill(label: String, selected: Boolean = false, shape: Shape = RoundedCornerShape(50), onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) AppAccent else tone(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = if (selected) Color.Black else AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeShortcutGrid(navController: NavController, items: List<SampleHomeItem>) {
    val cols = windowSize().columns(2, 280.dp)
    Column(modifier = Modifier.padding(8.dp, 4.dp)) {
        items.chunked(cols).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp),
            ) {
                rowItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .pressable(0.96f) { navigateHomeItem(navController, item) }
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(GridBackground),
                    ) {
                        SampleArtwork(
                            art = item.art,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = item.name,
                            color = AppText,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(lineBreak = LineBreak.Heading),
                            modifier = Modifier.padding(8.dp, 4.dp),
                        )
                    }
                }
                repeat(cols - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HomeFeedSection(navController: NavController, section: SampleHomeSection) {
    Text(
        text = section.title,
        color = AppText,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp),
    )
    LazyRow(contentPadding = PaddingValues(10.dp, 0.dp)) {
        items(section.items.size, key = { section.items[it].id }) { i ->
            HomeFeedCard(section.items[i]) { navigateHomeItem(navController, section.items[i]) }
        }
    }
}

@Composable
private fun HomeFeedCard(item: SampleHomeItem, onClick: () -> Unit) {
    val isArtist = item.kind == SampleHomeItem.Kind.Artist
    val subtitle = if (isArtist) stringResource(Res.string.artist_subtitle) else item.subtitle
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .pressable(0.96f) { onClick() }
            .width(150.dp)
            .padding(6.dp),
    ) {
        SampleArtwork(
            art = item.art,
            shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
            modifier = Modifier.size(150.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            color = AppText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        Text(
            text = subtitle,
            color = AppMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
private fun WhatsNewBell(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(end = 10.dp, top = 10.dp, bottom = 10.dp)
            .size(34.dp),
    ) {
        Icon(
            Icons.Outlined.Notifications,
            contentDescription = stringResource(Res.string.whats_new_title),
            tint = AppText,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(onOpen: (route: String) -> Unit = {}, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tone(0xFF282828),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(Routes.Profile.route) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFE8622C)),
                ) {
                    Text("D", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text("Dany", color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
            }
            Text(stringResource(Res.string.view_profile), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 82.dp, bottom = 4.dp))
            HorizontalDivider(color = tone(0xFF3E3E3E), modifier = Modifier.padding(vertical = 8.dp))
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(Routes.Settings.route) }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.settings_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(Res.string.settings_detail), color = AppMuted, fontSize = 12.sp)
            }
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(Routes.Recap.route) }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.recap_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(Res.string.recap_detail), color = AppMuted, fontSize = 12.sp)
            }
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(Routes.Recent.route) }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.recents_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(Res.string.recents_detail), color = AppMuted, fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(Routes.Extensions.route) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.extensions_title), color = AppText, fontSize = 16.sp)
                    Text(stringResource(Res.string.extensions_row_detail), color = AppMuted, fontSize = 12.sp)
                }
                Icon(Icons.Filled.Extension, null, tint = AppMuted, modifier = Modifier.padding(start = 12.dp).size(22.dp))
            }
            Column(
                Modifier.fillMaxWidth().clickable { onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.account_page_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(Res.string.account_page_detail), color = AppMuted, fontSize = 12.sp)
            }
            Column(
                Modifier.fillMaxWidth().clickable { onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.support_title), color = AppAccent, fontSize = 16.sp)
                Text(stringResource(Res.string.support_row_detail), color = AppMuted, fontSize = 12.sp)
            }
            Column(
                Modifier.fillMaxWidth().clickable { onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.creator_biography_section_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(Res.string.about_detail), color = AppMuted, fontSize = 12.sp)
            }
            Column(
                Modifier.fillMaxWidth().clickable { onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(Res.string.accounts_switch_row), color = AppText, fontSize = 16.sp)
                Text("Preview mode (offline)", color = AppMuted, fontSize = 12.sp)
            }
            Text(
                stringResource(Res.string.connect_device_menu_logout),
                color = AppText,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

private fun navigateHomeItem(navController: NavController, item: SampleHomeItem) {
    when (item.kind) {
        SampleHomeItem.Kind.Artist -> navController.navigate(artistRoute(item.name, item.id))
        SampleHomeItem.Kind.Album -> navController.navigate(albumRoute(item.name, item.subtitle.removePrefix("Album • "), item.id))
        SampleHomeItem.Kind.Playlist -> {
            if (item.id == "pl_liked") navController.navigate(Routes.Liked.route)
            else navController.navigate(playlistRoute(item.id, item.name))
        }
        SampleHomeItem.Kind.Show -> navController.navigate(playlistRoute(item.id, item.name))
    }
}
