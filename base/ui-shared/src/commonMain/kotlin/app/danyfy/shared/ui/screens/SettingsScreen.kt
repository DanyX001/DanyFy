package app.danyfy.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.AudioQualityOption
import app.danyfy.shared.data.DownloadQualityOption
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.accessibility_navigate_back
import app.danyfy.shared.resources.accounts_title
import app.danyfy.shared.resources.color_blue
import app.danyfy.shared.resources.color_green
import app.danyfy.shared.resources.color_orange
import app.danyfy.shared.resources.color_pink
import app.danyfy.shared.resources.color_purple
import app.danyfy.shared.resources.data_saver_summary
import app.danyfy.shared.resources.data_saver_title
import app.danyfy.shared.resources.equalizer_settings_detail
import app.danyfy.shared.resources.equalizer_title
import app.danyfy.shared.data.Look
import app.danyfy.shared.platform.PlatformRegistry
import app.danyfy.shared.resources.look_classic
import app.danyfy.shared.resources.look_classic_detail
import app.danyfy.shared.resources.look_immersive
import app.danyfy.shared.resources.look_immersive_detail
import app.danyfy.shared.ui.LocalLibraryStore
import app.danyfy.shared.ui.LocalLook
import app.danyfy.shared.ui.LocalYtmApi
import app.danyfy.shared.resources.lyrics_extra_summary
import app.danyfy.shared.resources.lyrics_extra_title
import app.danyfy.shared.resources.lyrics_word_sync_off
import app.danyfy.shared.resources.lyrics_word_sync_on
import app.danyfy.shared.resources.lyrics_word_sync_title
import app.danyfy.shared.resources.metadata_footer
import app.danyfy.shared.resources.metadata_title
import app.danyfy.shared.resources.myspoti_shuffle_detail
import app.danyfy.shared.resources.myspoti_shuffle_title
import app.danyfy.shared.resources.quality_download_ask
import app.danyfy.shared.resources.quality_download_ask_detail
import app.danyfy.shared.resources.quality_download_cd
import app.danyfy.shared.resources.quality_download_cd_detail
import app.danyfy.shared.resources.quality_download_detail
import app.danyfy.shared.resources.quality_download_hires
import app.danyfy.shared.resources.quality_download_hires_detail
import app.danyfy.shared.resources.quality_download_off
import app.danyfy.shared.resources.quality_download_off_detail
import app.danyfy.shared.resources.quality_download_small
import app.danyfy.shared.resources.quality_download_small_detail
import app.danyfy.shared.resources.quality_download_title
import app.danyfy.shared.resources.quality_streaming_auto
import app.danyfy.shared.resources.quality_streaming_auto_detail
import app.danyfy.shared.resources.quality_streaming_cell_title
import app.danyfy.shared.resources.quality_streaming_detail
import app.danyfy.shared.resources.quality_streaming_high
import app.danyfy.shared.resources.quality_streaming_high_detail
import app.danyfy.shared.resources.quality_streaming_low
import app.danyfy.shared.resources.quality_streaming_low_detail
import app.danyfy.shared.resources.quality_streaming_normal
import app.danyfy.shared.resources.quality_streaming_normal_detail
import app.danyfy.shared.resources.quality_streaming_very_high
import app.danyfy.shared.resources.quality_streaming_very_high_detail
import app.danyfy.shared.resources.quality_streaming_wifi_title
import app.danyfy.shared.resources.settings_about_version
import app.danyfy.shared.resources.settings_amoled_theme
import app.danyfy.shared.resources.settings_amoled_theme_subtitle
import app.danyfy.shared.resources.settings_auto_update_detail
import app.danyfy.shared.resources.settings_auto_update_title
import app.danyfy.shared.resources.settings_autoplay_detail
import app.danyfy.shared.resources.settings_autoplay_title
import app.danyfy.shared.resources.settings_crossfade_off
import app.danyfy.shared.resources.settings_download_lyrics_detail
import app.danyfy.shared.resources.settings_download_lyrics_title
import app.danyfy.shared.resources.settings_hide_dj_detail
import app.danyfy.shared.resources.settings_hide_dj_title
import app.danyfy.shared.resources.settings_item_canvas_title
import app.danyfy.shared.resources.settings_local_files_shown
import app.danyfy.shared.resources.settings_local_files_shown_detail
import app.danyfy.shared.resources.settings_look_title
import app.danyfy.shared.resources.settings_native_audio_detail
import app.danyfy.shared.resources.settings_native_audio_title
import app.danyfy.shared.resources.settings_player_lyrics_card_detail
import app.danyfy.shared.resources.settings_player_lyrics_card_title
import app.danyfy.shared.resources.settings_release_notify
import app.danyfy.shared.resources.settings_release_notify_detail
import app.danyfy.shared.resources.settings_search
import app.danyfy.shared.resources.settings_search_hint
import app.danyfy.shared.resources.settings_section_app
import app.danyfy.shared.resources.settings_section_controls
import app.danyfy.shared.resources.settings_section_data
import app.danyfy.shared.resources.settings_section_display
import app.danyfy.shared.resources.settings_section_downloads
import app.danyfy.shared.resources.settings_section_library
import app.danyfy.shared.resources.settings_section_lyrics
import app.danyfy.shared.resources.settings_section_playback
import app.danyfy.shared.resources.settings_solid_nav_detail
import app.danyfy.shared.resources.settings_solid_nav_title
import app.danyfy.shared.resources.settings_sources_none
import app.danyfy.shared.resources.settings_sources_tab
import app.danyfy.shared.resources.settings_system_font_detail
import app.danyfy.shared.resources.settings_system_font_title
import app.danyfy.shared.resources.settings_tab_about
import app.danyfy.shared.resources.settings_tab_appearance
import app.danyfy.shared.resources.settings_title
import app.danyfy.shared.resources.settings_transitions_detail
import app.danyfy.shared.resources.settings_transitions_title
import app.danyfy.shared.resources.supporter_accent
import app.danyfy.shared.resources.video_settings_canvas_subtitle
import app.danyfy.shared.ui.components.bottomBarsSpace
import app.danyfy.shared.ui.navigation.Routes
import app.danyfy.shared.ui.navigation.solidNavBarEnabled
import app.danyfy.shared.ui.theme.AppAccent
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.AppMuted
import app.danyfy.shared.ui.theme.AppText
import app.danyfy.shared.ui.theme.oledBlackEnabled
import app.danyfy.shared.ui.theme.selectedAccent
import app.danyfy.shared.ui.theme.systemFontEnabled
import app.danyfy.shared.ui.theme.tone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val TAB_PLAYBACK = 0
private const val TAB_SOURCES = 1
private const val TAB_LYRICS = 2
private const val TAB_DOWNLOADS = 3
private const val TAB_LOOK = 4
private const val TAB_ABOUT = 5
private const val TAB_ACCOUNT = 6
private const val TAB_LIBRARY = 7
private val TAB_ORDER = listOf(TAB_ACCOUNT, TAB_SOURCES, TAB_PLAYBACK, TAB_LIBRARY, TAB_LYRICS, TAB_DOWNLOADS, TAB_LOOK, TAB_ABOUT)

@Composable
fun SettingsScreen(navController: NavController, initialTab: Int = TAB_PLAYBACK) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }

    var wifiQuality by remember { mutableStateOf(AudioQualityOption.HIGH) }
    var cellQuality by remember { mutableStateOf(AudioQualityOption.AUTO) }
    var downloadQuality by remember { mutableStateOf(DownloadQualityOption.CD) }
    var autoplay by remember { mutableStateOf(true) }
    var appShuffle by remember { mutableStateOf(true) }
    var nativeAudio by remember { mutableStateOf(true) }
    var dataSaver by remember { mutableStateOf(false) }
    var releaseNotify by remember { mutableStateOf(true) }
    var showLocalFiles by remember { mutableStateOf(true) }
    var wordSync by remember { mutableStateOf(true) }
    var extraLyrics by remember { mutableStateOf(true) }
    var downloadLyrics by remember { mutableStateOf(true) }
    var amoled by remember { mutableStateOf(oledBlackEnabled) }
    var canvasOn by remember { mutableStateOf(true) }
    var lyricsCard by remember { mutableStateOf(true) }
    var solidNav by remember { mutableStateOf(solidNavBarEnabled) }
    var hideDj by remember { mutableStateOf(false) }
    var sysFont by remember { mutableStateOf(systemFontEnabled) }
    var autoUpdate by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf("Spotify") }

    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.accessibility_navigate_back), tint = AppText)
            }
            Text(
                stringResource(Res.string.settings_title),
                color = AppText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { query = if (query == null) "" else null }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.settings_search), tint = AppText)
            }
        }

        query?.let { q ->
            OutlinedTextField(
                value = q,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.settings_search_hint), color = AppMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = AppMuted) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppText,
                    unfocusedTextColor = AppText,
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = tone(0xFF3E3E3E),
                    cursorColor = AppAccent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val tabs = listOf(
            Res.string.settings_section_playback,
            Res.string.settings_sources_tab,
            Res.string.settings_section_lyrics,
            Res.string.settings_section_downloads,
            Res.string.settings_tab_appearance,
            Res.string.settings_tab_about,
            Res.string.accounts_title,
            Res.string.settings_section_library,
        )
        val selectedIndex = TAB_ORDER.indexOf(tab).coerceAtLeast(0)
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = Color.Transparent,
            contentColor = AppText,
            edgePadding = 12.dp,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selectedIndex]), color = AppAccent)
            },
            divider = { HorizontalDivider(color = tone(0xFF2A2A2A)) },
        ) {
            TAB_ORDER.forEach { i ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(stringResource(tabs[i]), fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = AppText,
                    unselectedContentColor = AppMuted,
                )
            }
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            when (tab) {
                TAB_PLAYBACK -> {
                    SettingsSection(Res.string.settings_section_playback)
                    QualityRadioGroup(
                        title = stringResource(Res.string.quality_streaming_wifi_title),
                        detail = stringResource(Res.string.quality_streaming_detail),
                        options = streamingOptions(),
                        selected = wifiQuality,
                        onSelect = { wifiQuality = it },
                    )
                    QualityRadioGroup(
                        title = stringResource(Res.string.quality_streaming_cell_title),
                        detail = stringResource(Res.string.quality_streaming_detail),
                        options = streamingOptions(),
                        selected = cellQuality,
                        onSelect = { cellQuality = it },
                    )
                    OptionRow(stringResource(Res.string.settings_autoplay_title), stringResource(Res.string.settings_autoplay_detail), autoplay) { autoplay = it }
                    OptionRow(stringResource(Res.string.myspoti_shuffle_title), stringResource(Res.string.myspoti_shuffle_detail), appShuffle) { appShuffle = it }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.settings_transitions_title), color = AppText, fontSize = 16.sp)
                            Text(stringResource(Res.string.settings_transitions_detail), color = AppMuted, fontSize = 12.sp)
                        }
                        Text(stringResource(Res.string.settings_crossfade_off), color = AppAccent, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppMuted)
                    }
                    Column(
                        Modifier.fillMaxWidth().clickable { navController.navigate(Routes.Equalizer.route) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(stringResource(Res.string.equalizer_title), color = AppText, fontSize = 16.sp)
                        Text(stringResource(Res.string.equalizer_settings_detail), color = AppMuted, fontSize = 12.sp)
                    }
                    SettingsSection(Res.string.settings_section_controls)
                    OptionRow(stringResource(Res.string.settings_native_audio_title), stringResource(Res.string.settings_native_audio_detail), nativeAudio) { nativeAudio = it }
                    SettingsSection(Res.string.settings_section_data)
                    OptionRow(stringResource(Res.string.data_saver_title), stringResource(Res.string.data_saver_summary), dataSaver) { dataSaver = it }
                }
                TAB_SOURCES -> {
                    Text(stringResource(Res.string.settings_sources_none), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                    SettingsSection(Res.string.metadata_title)
                    Text(stringResource(Res.string.metadata_footer), color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                TAB_LIBRARY -> {
                    SettingsSection(Res.string.settings_section_library)
                    OptionRow(stringResource(Res.string.settings_release_notify), stringResource(Res.string.settings_release_notify_detail), releaseNotify) { releaseNotify = it }
                    OptionRow(stringResource(Res.string.settings_local_files_shown), stringResource(Res.string.settings_local_files_shown_detail), showLocalFiles) { showLocalFiles = it }
                }
                TAB_LYRICS -> {
                    SettingsSection(Res.string.settings_section_lyrics)
                    OptionRow(
                        label = stringResource(Res.string.lyrics_word_sync_title),
                        detail = stringResource(if (wordSync) Res.string.lyrics_word_sync_on else Res.string.lyrics_word_sync_off),
                        checked = wordSync,
                    ) { wordSync = it }
                    OptionRow(stringResource(Res.string.lyrics_extra_title), stringResource(Res.string.lyrics_extra_summary), extraLyrics) { extraLyrics = it }
                }
                TAB_DOWNLOADS -> {
                    SettingsSection(Res.string.settings_section_downloads)
                    QualityRadioGroup(
                        title = stringResource(Res.string.quality_download_title),
                        detail = stringResource(Res.string.quality_download_detail),
                        options = downloadOptions(),
                        selected = downloadQuality,
                        onSelect = { downloadQuality = it },
                    )
                    OptionRow(stringResource(Res.string.settings_download_lyrics_title), stringResource(Res.string.settings_download_lyrics_detail), downloadLyrics) { downloadLyrics = it }
                }
                TAB_LOOK -> {
                    SettingsSection(Res.string.settings_section_display)
                    OptionRow(stringResource(Res.string.settings_amoled_theme), stringResource(Res.string.settings_amoled_theme_subtitle), amoled) {
                        amoled = it; oledBlackEnabled = it
                    }
                    OptionRow(stringResource(Res.string.settings_item_canvas_title), stringResource(Res.string.video_settings_canvas_subtitle), canvasOn) { canvasOn = it }
                    OptionRow(stringResource(Res.string.settings_player_lyrics_card_title), stringResource(Res.string.settings_player_lyrics_card_detail), lyricsCard) { lyricsCard = it }
                    OptionRow(stringResource(Res.string.settings_solid_nav_title), stringResource(Res.string.settings_solid_nav_detail), solidNav) {
                        solidNav = it; solidNavBarEnabled = it
                    }
                    OptionRow(stringResource(Res.string.settings_hide_dj_title), stringResource(Res.string.settings_hide_dj_detail), hideDj) { hideDj = it }
                    AccentRow()
                    OptionRow(stringResource(Res.string.settings_system_font_title), stringResource(Res.string.settings_system_font_detail), sysFont) {
                        sysFont = it; systemFontEnabled = it
                    }
                    LookRow()
                }
                TAB_ABOUT -> {
                    SettingsSection(Res.string.settings_section_app)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(stringResource(Res.string.settings_about_version, "0.1.0 (iOS Preview)"), color = AppText, fontSize = 16.sp)
                        Text("Offline UI preview — no playback yet", color = AppMuted, fontSize = 12.sp)
                    }
                    OptionRow(stringResource(Res.string.settings_auto_update_title), stringResource(Res.string.settings_auto_update_detail), autoUpdate) { autoUpdate = it }
                }
                TAB_ACCOUNT -> {
                    SettingsSection(Res.string.accounts_title)
                    val ytmApi = LocalYtmApi.current
                    var signedInState by remember { mutableStateOf(ytmApi.isSignedIn) }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text("YouTube Music", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (signedInState) "Signed in with Google / YouTube Music" else "Streaming anonymously. Sign in to access your private library, Liked Songs & playlists.",
                            color = AppMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!signedInState) {
                                Text(
                                    "Sign In",
                                    color = Color.Black,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(AppAccent)
                                        .clickable {
                                            PlatformRegistry.auth.openSignIn(
                                                onSuccess = { cookies ->
                                                    ytmApi.cookies = cookies
                                                    signedInState = true
                                                },
                                                onCancel = {},
                                            )
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                )
                            } else {
                                Text(
                                    "Sign Out",
                                    color = AppText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(tone(0xFF333333))
                                        .clickable {
                                            ytmApi.cookies = null
                                            PlatformRegistry.auth.clearCookies()
                                            signedInState = false
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(bottomBarsSpace()))
        }
    }
}

private data class QualityOpt<T>(val value: T, val label: StringResource, val detail: StringResource)

private fun streamingOptions() = listOf(
    QualityOpt(AudioQualityOption.AUTO, Res.string.quality_streaming_auto, Res.string.quality_streaming_auto_detail),
    QualityOpt(AudioQualityOption.LOW, Res.string.quality_streaming_low, Res.string.quality_streaming_low_detail),
    QualityOpt(AudioQualityOption.NORMAL, Res.string.quality_streaming_normal, Res.string.quality_streaming_normal_detail),
    QualityOpt(AudioQualityOption.HIGH, Res.string.quality_streaming_high, Res.string.quality_streaming_high_detail),
    QualityOpt(AudioQualityOption.VERY_HIGH, Res.string.quality_streaming_very_high, Res.string.quality_streaming_very_high_detail),
)

private fun downloadOptions() = listOf(
    QualityOpt(DownloadQualityOption.OFF, Res.string.quality_download_off, Res.string.quality_download_off_detail),
    QualityOpt(DownloadQualityOption.SMALL, Res.string.quality_download_small, Res.string.quality_download_small_detail),
    QualityOpt(DownloadQualityOption.CD, Res.string.quality_download_cd, Res.string.quality_download_cd_detail),
    QualityOpt(DownloadQualityOption.HI_RES, Res.string.quality_download_hires, Res.string.quality_download_hires_detail),
    QualityOpt(DownloadQualityOption.ASK, Res.string.quality_download_ask, Res.string.quality_download_ask_detail),
)

@Composable
private fun <T> QualityRadioGroup(
    title: String,
    detail: String,
    options: List<QualityOpt<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(title, color = AppText, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Text(detail, color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
        for (o in options) {
            val on = o.value == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(o.value) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                RadioButton(
                    selected = on,
                    onClick = null,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    colors = RadioButtonDefaults.colors(selectedColor = AppAccent, unselectedColor = AppMuted),
                )
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(o.label), color = if (on) AppAccent else AppText, fontSize = 16.sp)
                    Text(stringResource(o.detail), color = AppMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: StringResource) {
    Text(
        stringResource(title).uppercase(),
        color = AppAccent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun AccentRow() {
    val accents = listOf(
        Color(0xFF1ED760) to Res.string.color_green,
        Color(0xFF509BF5) to Res.string.color_blue,
        Color(0xFFAF7AF6) to Res.string.color_purple,
        Color(0xFFF573A0) to Res.string.color_pink,
        Color(0xFFFF9E3D) to Res.string.color_orange,
    )
    var current by remember { mutableStateOf(selectedAccent) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(Res.string.supporter_accent), color = AppText, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            for ((color, labelRes) in accents) {
                val selected = current == color
                val name = stringResource(labelRes)
                Box(
                    Modifier
                        .size(40.dp)
                        .semantics { contentDescription = name; role = Role.RadioButton }
                        .clip(CircleShape)
                        .background(color)
                        .then(if (selected) Modifier.border(3.dp, AppText, CircleShape) else Modifier)
                        .clickable { current = color; selectedAccent = color },
                )
            }
        }
    }
}

@Composable
private fun LookRow() {
    val store = LocalLibraryStore.current
    val currentLook = LocalLook.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(Res.string.settings_look_title), color = AppText, fontSize = 16.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { store.setLookPreference(Look.CLASSIC) }
                .padding(vertical = 8.dp),
        ) {
            RadioButton(
                selected = currentLook == Look.CLASSIC,
                onClick = { store.setLookPreference(Look.CLASSIC) },
                colors = RadioButtonDefaults.colors(selectedColor = AppAccent),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(Res.string.look_classic), color = AppText, fontSize = 15.sp)
                Text(stringResource(Res.string.look_classic_detail), color = AppMuted, fontSize = 12.sp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { store.setLookPreference(Look.IMMERSIVE) }
                .padding(vertical = 8.dp),
        ) {
            RadioButton(
                selected = currentLook == Look.IMMERSIVE,
                onClick = { store.setLookPreference(Look.IMMERSIVE) },
                colors = RadioButtonDefaults.colors(selectedColor = AppAccent),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(Res.string.look_immersive), color = AppText, fontSize = 15.sp)
                Text(stringResource(Res.string.look_immersive_detail), color = AppMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AppText, fontSize = 16.sp)
            Text(detail, color = AppMuted, fontSize = 12.sp)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
