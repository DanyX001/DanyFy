package app.danyfy.shared.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.danyfy.shared.data.PreviewPlayerState
import app.danyfy.shared.data.SampleCatalog
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.a11y_close_player
import app.danyfy.shared.resources.action_queue_label
import app.danyfy.shared.resources.action_share_label
import app.danyfy.shared.resources.content_description_context_menu
import app.danyfy.shared.resources.context_type_description_playlist
import app.danyfy.shared.resources.explicit_badge
import app.danyfy.shared.resources.ic_player_back
import app.danyfy.shared.resources.ic_player_shuffle
import app.danyfy.shared.resources.ic_player_skip
import app.danyfy.shared.resources.ic_share
import app.danyfy.shared.resources.ic_shuffle_smart_active
import app.danyfy.shared.resources.mr_user_route_category_name
import app.danyfy.shared.resources.notif_action_pause
import app.danyfy.shared.resources.notif_action_play
import app.danyfy.shared.resources.notif_shuffle_disable
import app.danyfy.shared.resources.notif_shuffle_enable
import app.danyfy.shared.resources.np_content_desc_next
import app.danyfy.shared.resources.np_content_desc_prev
import app.danyfy.shared.resources.playback_seek_description
import app.danyfy.shared.resources.repeat_off
import app.danyfy.shared.resources.repeat_on
import app.danyfy.shared.resources.repeat_once
import app.danyfy.shared.resources.repeat_one
import app.danyfy.shared.ui.components.LikeButton
import app.danyfy.shared.ui.components.MinTouch
import app.danyfy.shared.ui.components.PlayPauseIcon
import app.danyfy.shared.ui.components.RepeatIcons
import app.danyfy.shared.ui.components.SampleArtwork
import app.danyfy.shared.ui.components.artworkTint
import app.danyfy.shared.ui.components.pressable
import app.danyfy.shared.ui.components.rememberPullToDismiss
import app.danyfy.shared.ui.components.titleMarquee
import app.danyfy.shared.ui.components.windowSize
import app.danyfy.shared.ui.navigation.LocalPlayerReveal
import app.danyfy.shared.ui.navigation.PlayerSheet
import app.danyfy.shared.ui.navigation.Routes
import app.danyfy.shared.ui.navigation.albumRoute
import app.danyfy.shared.ui.navigation.artistRoute
import app.danyfy.shared.ui.theme.AppAccent
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val PLAYER_PEEK_DP = 65
private const val SHORT_PLAYER_PEEK_DP = 40
private const val SHORT_PLAYER_DP = 640
private val PLAYER_BASE = Color(0xFF0F1114)

@Composable
fun PlayerScreen(navController: NavController) {
    val song = PreviewPlayerState.currentSong
    val prevSong = PreviewPlayerState.prevSong
    val nextSong = PreviewPlayerState.nextSong
    val playing = PreviewPlayerState.isPlaying
    val liked = PreviewPlayerState.isLiked(song.id)
    val shuffle = PreviewPlayerState.shuffle
    val repeatMode = PreviewPlayerState.repeatMode

    val backdropTarget = artworkTint(song.art)
    val backdropColor by animateColorAsState(backdropTarget, tween(450), label = "backdrop")

    val artworkPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(song.id) {
        if (artworkPagerState.currentPage != 1) artworkPagerState.scrollToPage(1)
    }
    var artworkSwiped by remember { mutableStateOf(false) }
    LaunchedEffect(artworkPagerState) {
        artworkPagerState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) artworkSwiped = true
        }
    }
    LaunchedEffect(artworkPagerState) {
        snapshotFlow { artworkPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page == 1) return@collect
                if (!artworkSwiped) { artworkPagerState.scrollToPage(1); return@collect }
                artworkSwiped = false
                when (page) {
                    2 -> PreviewPlayerState.next()
                    0 -> PreviewPlayerState.previous()
                }
            }
    }

    val pullToClose = rememberPullToDismiss(PlayerSheet.open.value) { PlayerSheet.close() }

    BoxWithConstraints(
        modifier = Modifier
            .graphicsLayer { translationY = pullToClose.offset }
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to backdropColor,
                        0.55f to lerp(backdropColor, PLAYER_BASE, 0.40f),
                        0.80f to lerp(backdropColor, PLAYER_BASE, 0.78f),
                        1f to PLAYER_BASE,
                    )
                )
            }
    ) {
        val pageHeight = maxHeight
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val shortWindow = windowSize().heightDp < SHORT_PLAYER_DP
        val lyricsPeek = if (shortWindow) SHORT_PLAYER_PEEK_DP else PLAYER_PEEK_DP
        val reveal = LocalPlayerReveal.current
        val revealShift = with(LocalDensity.current) { 24.dp.toPx() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { val r = reveal(); alpha = r; translationY = (1f - r) * revealShift }
                .nestedScroll(pullToClose.connection)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                BoxWithConstraints(
                    modifier = Modifier
                        .height((pageHeight - statusTop - lyricsPeek.dp).coerceAtLeast(300.dp))
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val twoPane = windowSize().playerSideBySide(maxWidth, maxHeight, minWidth = 600.dp)
                    val contentWidth = if (twoPane) ((maxWidth - 72.dp) / 2).coerceIn(0.dp, 560.dp)
                        else maxWidth.coerceAtMost(520.dp)

                    val topBar: @Composable () -> Unit = {
                        PlayerTopBar(navController)
                        Spacer(modifier = Modifier.height(if (shortWindow) 4.dp else 14.dp))
                    }

                    val mediaArea: @Composable (Modifier, Alignment) -> Unit = { mediaModifier, mediaAlignment ->
                        BoxWithConstraints(modifier = mediaModifier, contentAlignment = mediaAlignment) {
                            val artSide = minOf(contentWidth, maxHeight).coerceAtLeast(0.dp)
                            val coverInset = ((artSide - minOf(contentWidth - 48.dp, maxHeight)) / 2).coerceAtLeast(0.dp)
                            Box(modifier = Modifier.size(artSide)) {
                                HorizontalPager(
                                    state = artworkPagerState,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                ) { page ->
                                    val target = when (page) {
                                        0 -> prevSong
                                        2 -> nextSong
                                        else -> song
                                    }
                                    val squareModifier = Modifier
                                        .padding(horizontal = coverInset)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        SampleArtwork(art = target.art, modifier = squareModifier)
                                    }
                                }
                            }
                        }
                    }

                    val controls: @Composable ColumnScope.() -> Unit = {
                        Box(modifier = Modifier.fillMaxWidth().height(if (shortWindow) 36.dp else 52.dp))
                        PlayerInfo(
                            songTitle = song.title,
                            songSinger = song.singer,
                            explicit = song.explicit,
                            liked = liked,
                            onLikeClick = { PreviewPlayerState.toggleLike(song.id) },
                            onTitleClick = { navController.navigate(albumRoute(song.album, song.singer, song.id)) },
                            onArtistClick = { navController.navigate(artistRoute(song.singer)) },
                        )

                        var isDragging by remember { mutableStateOf(false) }
                        var dragValue by remember { mutableStateOf(PreviewPlayerState.progressFraction) }
                        val shownFrac = if (isDragging) dragValue else PreviewPlayerState.progressFraction
                        SpotifySeekBar(
                            fraction = { shownFrac },
                            onDrag = { isDragging = true; dragValue = it },
                            onRelease = {
                                PreviewPlayerState.progressFraction = dragValue
                                isDragging = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = if (shortWindow) 6.dp else 11.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val elapsedMs = (shownFrac * song.durationMs).toLong()
                            Text(
                                text = SampleCatalog.formatDuration(elapsedMs),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                lineHeight = 14.sp,
                            )
                            Text(
                                text = SampleCatalog.formatDuration(song.durationMs),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                lineHeight = 14.sp,
                            )
                        }

                        PlayerFull(
                            songPlayingState = playing,
                            shuffle = shuffle,
                            repeatMode = repeatMode,
                            compact = shortWindow,
                        )

                        PlayerConnectRow(
                            onDevices = {},
                            onQueue = { navController.navigate(Routes.Queue.route) },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (twoPane) {
                        Column(
                            modifier = Modifier.fillMaxHeight().fillMaxWidth().navigationBarsPadding(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.width(contentWidth * 2 + 72.dp)) { Column { topBar() } }
                            Row(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                mediaArea(Modifier.width(contentWidth).fillMaxHeight(), Alignment.Center)
                                Column(
                                    Modifier.width(contentWidth).verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) { controls() }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxHeight().width(contentWidth).navigationBarsPadding(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            topBar()
                            mediaArea(Modifier.fillMaxWidth().weight(1f), Alignment.TopCenter)
                            controls()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(navController: NavController) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 9.dp, end = 8.dp, top = 2.5.dp, bottom = 2.5.dp),
    ) {
        Icon(
            modifier = Modifier
                .size(MinTouch)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { PlayerSheet.close() }
                .wrapContentSize()
                .size(26.dp),
            imageVector = PlayerIcons.chevronDown,
            tint = Color.White,
            contentDescription = stringResource(Res.string.a11y_close_player),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).padding(start = 1.dp),
        ) {
            Text(
                text = stringResource(Res.string.context_type_description_playlist),
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Daily Mix 1",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.titleMarquee(iterations = 3),
            )
        }
        Icon(
            imageVector = Icons.Default.MoreVert,
            tint = Color.White,
            modifier = Modifier
                .size(MinTouch)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    navController.navigate(Routes.Queue.route)
                }
                .wrapContentSize()
                .size(24.dp),
            contentDescription = stringResource(Res.string.content_description_context_menu),
        )
    }
}

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(16.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.65f)),
    ) {
        Text(
            stringResource(Res.string.explicit_badge),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                fontSize = 10.sp,
                lineHeight = 10.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

@Composable
private fun PlayerInfo(
    songTitle: String,
    songSinger: String,
    explicit: Boolean,
    liked: Boolean,
    onLikeClick: () -> Unit,
    onArtistClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp),
    ) {
        val textAlpha = remember { Animatable(1f) }
        var shownTitle by remember { mutableStateOf(songTitle) }
        LaunchedEffect(songTitle) {
            if (songTitle == shownTitle) return@LaunchedEffect
            shownTitle = songTitle
            textAlpha.snapTo(0f)
            textAlpha.animateTo(1f, tween(200))
        }
        Column(modifier = Modifier.weight(1f).padding(end = 6.dp).graphicsLayer { alpha = textAlpha.value }) {
            Text(
                modifier = Modifier
                    .then(
                        if (onTitleClick != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onTitleClick() } else Modifier
                    )
                    .titleMarquee(iterations = 3),
                text = songTitle,
                color = Color.White,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    ExplicitBadge()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = songSinger,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = (
                        if (onArtistClick != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onArtistClick() } else Modifier
                    ).titleMarquee(iterations = 3),
                )
            }
        }
        LikeButton(liked = liked, touch = MinTouch, onClick = onLikeClick)
    }
}

@Composable
private fun SpotifySeekBar(
    fraction: () -> Float,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(1f) }
    val seekDescription = stringResource(Res.string.playback_seek_description)
    val semanticProgress = remember(fraction) {
        derivedStateOf {
            val value = fraction()
            if (value.isFinite()) (value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f else 0f
        }
    }
    Canvas(
        modifier = modifier
            .height(24.dp)
            .graphicsLayer()
            .semantics {
                contentDescription = seekDescription
                progressBarRangeInfo = ProgressBarRangeInfo(semanticProgress.value, 0f..1f)
                setProgress { requested ->
                    if (!requested.isFinite()) return@setProgress false
                    onDrag(requested.coerceIn(0f, 1f))
                    onRelease()
                    true
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)); onRelease() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)) },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, _ ->
                    change.consume()
                    onDrag((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            }
    ) {
        val y = size.height / 2
        val track = 2.dp.toPx()
        val f = fraction().coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
        drawLine(Color.White, Offset(0f, y), Offset(size.width * f, y), track, StrokeCap.Round)
        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(size.width * f, y))
    }
}

@Composable
private fun PlayerFull(
    songPlayingState: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    compact: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (compact) 8.dp else 18.dp, bottom = if (compact) 4.dp else 18.dp)
            .height(64.dp),
    ) {
        Icon(
            modifier = Modifier
                .size(MinTouch)
                .pressable { PreviewPlayerState.shuffle = !PreviewPlayerState.shuffle }
                .wrapContentSize()
                .size(28.dp)
                .drawWithContent {
                    drawContent()
                    if (shuffle) {
                        drawCircle(AppAccent, radius = 2.dp.toPx(), center = Offset(size.width / 2, size.height + 7.dp.toPx()))
                    }
                },
            tint = if (shuffle) AppAccent else Color.White,
            painter = painterResource(if (shuffle) Res.drawable.ic_shuffle_smart_active else Res.drawable.ic_player_shuffle),
            contentDescription = stringResource(if (shuffle) Res.string.notif_shuffle_disable else Res.string.notif_shuffle_enable),
        )
        Icon(
            modifier = Modifier
                .size(MinTouch)
                .pressable { PreviewPlayerState.previous() }
                .wrapContentSize()
                .size(40.dp),
            tint = Color.White,
            painter = painterResource(Res.drawable.ic_player_back),
            contentDescription = stringResource(Res.string.np_content_desc_prev),
        )
        Box(
            modifier = Modifier
                .requiredSize(64.dp)
                .pressable(pressedScale = 0.92f) { PreviewPlayerState.togglePlay() }
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseIcon(
                modifier = Modifier.size(36.dp),
                tint = Color.Black,
                playing = songPlayingState,
                contentDescription = stringResource(if (songPlayingState) Res.string.notif_action_pause else Res.string.notif_action_play),
            )
        }
        Icon(
            modifier = Modifier
                .size(MinTouch)
                .pressable { PreviewPlayerState.next() }
                .wrapContentSize()
                .size(40.dp),
            tint = Color.White,
            painter = painterResource(Res.drawable.ic_player_skip),
            contentDescription = stringResource(Res.string.np_content_desc_next),
        )
        Icon(
            modifier = Modifier
                .size(MinTouch)
                .pressable { PreviewPlayerState.repeatMode = (PreviewPlayerState.repeatMode + 1) % 4 }
                .wrapContentSize()
                .size(28.dp),
            tint = if (repeatMode > 0) AppAccent else Color.White,
            imageVector = when (repeatMode) {
                2 -> PlayerIcons.repeatOne
                3 -> RepeatIcons.once
                else -> PlayerIcons.repeat
            },
            contentDescription = stringResource(
                when (repeatMode) {
                    1 -> Res.string.repeat_on
                    2 -> Res.string.repeat_one
                    3 -> Res.string.repeat_once
                    else -> Res.string.repeat_off
                }
            ),
        )
    }
}

@Composable
private fun PlayerConnectRow(
    onDevices: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .padding(start = 20.dp, end = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = MinTouch)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDevices() },
        ) {
            Icon(
                imageVector = PlayerIcons.device,
                tint = AppAccent,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(Res.string.mr_user_route_category_name),
            )
            Column(modifier = Modifier.padding(start = 6.dp).widthIn(max = 220.dp)) {
                Text(
                    text = "This iPhone",
                    color = AppAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(Res.drawable.ic_share),
                tint = Color.White,
                modifier = Modifier
                    .size(MinTouch)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .wrapContentSize()
                    .size(24.dp),
                contentDescription = stringResource(Res.string.action_share_label),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = PlayerIcons.queue,
                tint = Color.White,
                modifier = Modifier
                    .size(MinTouch)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onQueue() }
                    .wrapContentSize()
                    .size(24.dp),
                contentDescription = stringResource(Res.string.action_queue_label),
            )
        }
    }
}

private object PlayerIcons {
    private fun stroked(name: String, w: Float = 2f, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = w,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = build,
            )
        }.build()

    val chevronDown: ImageVector = stroked("chevronDown", 1.8f) {
        moveTo(4f, 8.5f); lineTo(12f, 16.5f); lineTo(20f, 8.5f)
    }
    private fun PathBuilder.loop() {
        moveTo(3f, 12f); verticalLineTo(9f); arcTo(4f, 4f, 0f, false, true, 7f, 5f)
        horizontalLineTo(17f); arcTo(4f, 4f, 0f, false, true, 21f, 9f)
        verticalLineTo(12f); arcTo(4f, 4f, 0f, false, true, 17f, 16f)
        horizontalLineTo(8f)
        moveTo(11f, 12.5f); lineTo(7.5f, 16f); lineTo(11f, 19.5f)
    }
    val repeat: ImageVector = stroked("repeat") { loop() }
    val repeatOne: ImageVector = stroked("repeatOne") {
        loop()
        moveTo(10.5f, 8.8f); lineTo(12.5f, 7.8f); verticalLineTo(13f)
    }
    val device: ImageVector = stroked("device") {
        moveTo(6f, 5f); horizontalLineTo(18f); arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 6.5f)
        verticalLineTo(14f); arcTo(1.5f, 1.5f, 0f, false, true, 18f, 15.5f)
        horizontalLineTo(6f); arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 14f)
        verticalLineTo(6.5f); arcTo(1.5f, 1.5f, 0f, false, true, 6f, 5f)
        moveTo(3f, 19.5f); horizontalLineTo(21f)
    }
    val queue: ImageVector = stroked("queue", 1.8f) {
        moveTo(8.5f, 5f); horizontalLineTo(15.5f)
        moveTo(4.5f, 10f); horizontalLineTo(19.5f)
        moveTo(4.5f, 14.5f); horizontalLineTo(19.5f)
        moveTo(4.5f, 19f); horizontalLineTo(19.5f)
    }
}
