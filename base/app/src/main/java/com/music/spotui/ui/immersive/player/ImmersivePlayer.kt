/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: only the portrait layout is taken: the artwork with its step back on pause, the move
 * of the artwork to a small header (title beside it) when the lyrics or the queue open, the
 * title row with its round glyphs, the scrubber, transport, volume and bottom rows, and the
 * sheet's drag to close. The full-bleed banner, Canvas clips, BitChord's track-swipe on the cover,
 * version switching, the lyric strip and the dock are left out. The landscape layout (cover beside
 * the controls), the Canvas behind the player (with the cover opening into it) and the swipe
 * along the cover that changes track (the mini player's) are Myspoti's own.
 */
package com.music.spotui.ui.immersive.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.spotui.ui.navigation.PlayerSheet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.components.DevicesSheet
import com.music.spotui.ui.immersive.sheets.ImmersiveSongOptionsSheet
import com.music.spotui.ui.immersive.sheets.LocalImmersiveSheetTint
import com.music.spotui.ui.immersive.sheets.rememberImmersiveSheetTint
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.lyrics.ImmersiveLyricsPanel
import com.music.spotui.ui.immersive.lyrics.ImmersiveQueuePanel
import com.music.spotui.ui.look.MeshGradientBackground
import com.music.spotui.ui.look.MeshPalette
import com.music.spotui.ui.look.lerp
import com.music.spotui.ui.components.SongBlend
import com.music.spotui.ui.components.SongBlendCover
import com.music.spotui.ui.components.mixSqueeze
import com.music.spotui.ui.components.mixWave
import com.music.spotui.ui.components.titleMarquee
import com.music.spotui.di.Haptics
import com.music.spotui.ui.look.rememberArtworkColors
import com.music.spotui.ui.look.rememberPauseScale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Sizes and gaps of the portrait layout, as the player this follows has them.
private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
// Covers.large is 640px: the cover is always decoded at that, whatever size the box has right now.
private const val COVER_DECODE_PX = 640
private val ART_TITLE_GAP = 20.dp
private val ART_BOX_TOP_PAD = 8.dp
private val DISMISS_STRIP_HEIGHT = 32.dp

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp

/**
 * How wide the player's content is ever allowed to get: a sleeve and a volume slider stretched
 * across a tablet aren't a bigger player, just a coarser one.
 */
internal val PLAYER_MAX_WIDTH = 560.dp

/** How long the sleeve takes to travel between the full player and the header. */
private const val PANEL_TRAVEL_MS = com.music.spotui.ui.immersive.motion.ImmersiveMotion.LONG_MS

private val ArtCorner = RoundedCornerShape(8.dp)
/** How much bigger the cover is on an AutoMix beat (the player draws in around it meanwhile). */
private const val MIX_BEAT_GROW = 0.025f

/** What the middle of the player shows. */
private object Panel {
    const val NONE = 0
    const val LYRICS = 1
    const val QUEUE = 2
}

/**
 * The sheet the player sits in, as the player sees it: dragged down from the top of the player
 * (the handle, the artwork, the credits) it follows the finger, and lets go with its speed.
 */
internal interface PlayerSheetDrag {
    fun drag(delta: Float)
    fun release(velocity: Float)
}

internal val LocalPlayerSheetDrag = staticCompositionLocalOf<PlayerSheetDrag?> { null }

/**
 * The downward half of a vertical drag on this element, handed to the sheet. Upward is not ours:
 * the crossing of the touch slop is left alone and nothing is consumed, so a tap, a scroll or a
 * child's own gesture keeps working.
 */
@Composable
internal fun Modifier.dragToClose(): Modifier {
    val sheet = LocalPlayerSheetDrag.current ?: return this
    val current by rememberUpdatedState(sheet)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var pulled = 0f
            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f) {
                    pulled = overSlop
                    change.consume()
                }
            }
            if (drag == null || pulled <= 0f) return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(drag)
            current.drag(pulled)
            val finished = verticalDrag(drag.id) { change ->
                velocity.addPointerInputChange(change)
                current.drag(change.positionChange().y)
                change.consume()
            }
            current.release(if (finished) velocity.calculateVelocity().y else 0f)
        }
    }
}

/** The grab handle over the full player: a drag down closes the sheet, and so does a tap. */
@Composable
internal fun PlayerDismissStrip(tint: Color, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(DISMISS_STRIP_HEIGHT).dragToClose()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(tint.copy(alpha = 0.35f)))
    }
}

/** How far the cover is swiped along to change track (the mini player's distance). */
private val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * A swipe along the cover follows the finger on GPU layers and changes track past [TRACK_SWIPE_THRESHOLD].
 * Only a mostly sideways move is taken (and consumed); a vertical one is left untouched for [dragToClose]
 * and a tap for the cover's click. Off while [enabled] is false.
 */
@Composable
private fun Modifier.swipeToChangeTrack(
    enabled: Boolean,
    actions: ImmersivePlayerActions,
    dragX: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
): Modifier {
    val on by rememberUpdatedState(enabled)
    val current by rememberUpdatedState(actions)
    return pointerInput(Unit) {
        val threshold = TRACK_SWIPE_THRESHOLD.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!on) return@awaitEachGesture
            val slopped = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                val moved = change.position - down.position
                if (abs(moved.x) > abs(moved.y)) change.consume()
            } ?: return@awaitEachGesture
            var dragged = (slopped.position - down.position).x
            var detentHit = false
            scope.launch { dragX.snapTo(dragged) }
            try {
                val finished = drag(slopped.id) { change ->
                    dragged += change.positionChange().x
                    change.consume()
                    scope.launch { dragX.snapTo(dragged) }
                    if (!detentHit && abs(dragged) >= threshold) {
                        detentHit = true
                        Haptics.tick()
                    }
                }
                if (finished) {
                    when {
                        dragged <= -threshold -> current.next()
                        dragged >= threshold -> current.previous()
                    }
                }
            } finally {
                scope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 420f)) }
            }
        }
    }
}

/**
 * The Immersive look's player: the cover's colours drifting behind a large cover, the credits,
 * a scrubber, the transport, the volume and a row of lyrics, devices and queue.
 *
 * Opening the lyrics or the queue moves the cover up into a small header with the credits beside
 * it; [ImmersiveLyricsPanel] or [ImmersiveQueuePanel] takes the middle and the controls below
 * stay where they are. [navController] is for the "more" sheet (artist, album, playlists…):
 * without it the button is not shown.
 */
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun ImmersivePlayer(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    modifier: Modifier = Modifier,
    navController: NavController? = null,
) {
    val context = LocalContext.current
    val palette = rememberArtworkColors(state.coverUrl.takeIf { it.isNotBlank() })
    // A crossfade: the colours of the song going out, as they were when it began (the new cover's
    // own come once it is loaded, and rememberArtworkColors keeps the old ones until then).
    val blend = state.blend
    val outgoingPalette = remember(blend) { palette }
    // The tint is the words' colour: it changes with them, half way.
    val tintPalette = if (blend?.outgoingWords == true) outgoingPalette else palette
    val tint by animateColorAsState(playerTintOf(tintPalette), tween(com.music.spotui.ui.immersive.motion.ImmersiveMotion.TINT_MS), label = "playerTint")

    var panel by rememberSaveable { mutableIntStateOf(Panel.NONE) }
    var showMenu by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showNerd by remember { mutableStateOf(false) }
    val lyricsOpen = panel == Panel.LYRICS
    val queueOpen = panel == Panel.QUEUE

    // 0 with the full player, 1 with the cover in the header. Read at measure, placement and draw.
    val collapse = animateFloatAsState(
        targetValue = if (panel == Panel.NONE) 0f else 1f,
        animationSpec = tween(PANEL_TRAVEL_MS, easing = FastOutSlowInEasing),
        label = "playerCollapse",
    )
    val pauseScale = rememberPauseScale(state.playing)
    val volume = rememberPlayerVolume()

    val mv = com.music.spotui.video.MusicVideoController
    androidx.compose.runtime.DisposableEffect(mv) {
        mv.attachPlayerScreen()
        onDispose { mv.detachPlayerScreen() }
    }
    val mvTrackId = com.music.spotui.ui.components.spotifyCanvasTrackIdFromRaw(state.trackId)
    LaunchedEffect(mvTrackId, state.title, state.buffering, state.adPlaying) {
        if (!state.buffering && !state.adPlaying && com.music.spotui.data.preferences.MusicVideoPref.on && mvTrackId.isNotBlank()) {
            mv.prepare(mvTrackId, state.title, state.artist, state.durationMs)
        }
    }
    LaunchedEffect(com.music.spotui.data.DataSaver.active) {
        if (com.music.spotui.data.DataSaver.active && mv.on.value) mv.setOn(false)
    }
    val remote = state.deviceName != null
    val readyVideo = mv.state.value as? com.music.spotui.video.MusicVideoController.State.Ready
    val videoMatchesTrack = mvTrackId.isNotBlank() && readyVideo?.trackId == mvTrackId
    val videoReady = videoMatchesTrack && !remote && !state.buffering && !state.adPlaying &&
        !com.music.spotui.data.DataSaver.active && com.music.spotui.data.preferences.MusicVideoPref.on
    val videoOn = mv.on.value && videoMatchesTrack && !remote && !state.adPlaying &&
        com.music.spotui.data.preferences.MusicVideoPref.on
    val settled by com.music.spotui.ui.navigation.LocalPageSettled.current
    var mediaReady by remember { mutableStateOf(false) }
    LaunchedEffect(settled) { if (settled) mediaReady = true }
    LaunchedEffect(mv.state.value, remote, state.buffering, mvTrackId) {
        if (videoReady && !state.buffering) mv.warm()
    }
    val showVideoSurface = videoOn && !mv.fullscreen.value && mediaReady

    // The song's Canvas, behind the player once its first frame is drawn. The cover opens into it:
    // the clip fades in from a little closer while the sleeve grows and fades away. The lyrics or
    // the queue close it the same way back: the clip (paused under them) fades out to the cover's
    // backdrop and the sleeve returns as the header's thumbnail, rather than a still clip showing
    // around the panel; closing the panel opens the cover into the clip again.
    val inSquare = com.music.spotui.data.preferences.PlayerCanvasSquarePref.on
    val canvasUrl = rememberImmersiveCanvas(state)
    var canvasShown by remember(canvasUrl) {
        mutableStateOf(canvasUrl != null && com.music.spotui.ui.components.CanvasPlayer.isReady(canvasUrl))
    }
    val canvasOn = canvasUrl != null && canvasShown && !state.adPlaying && panel == Panel.NONE && !videoOn
    // Opening on a clip already showing starts at the end; every later change animates from where it is.
    val canvasOpen = remember { androidx.compose.animation.core.Animatable(if (canvasOn) 1f else 0f) }
    // Opening the player: the heart of the song shown is checked again if its state is old.
    LaunchedEffect(Unit) { com.music.spotui.di.SongPlayer.refreshLikedIfStale() }
    LaunchedEffect(canvasOn) {
        val target = if (canvasOn) 1f else 0f
        val left = kotlin.math.abs(target - canvasOpen.value)
        // A panel opening: the clip leaves with the cover's travel to the header, not after it.
        val ms = if (!canvasOn && panel != Panel.NONE) PANEL_TRAVEL_MS else CANVAS_OPEN_MS
        if (left > 0f) canvasOpen.animateTo(target, tween((ms * left).toInt(), easing = FastOutSlowInEasing))
    }

    val sheetTint = rememberImmersiveSheetTint(state.coverUrl.takeIf { it.isNotBlank() })
    val dock = LocalPlayerDock.current

    // Back leaves the panel first; the sheet (outside) closes on the next one.
    BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

    // The wave that leaves the cover when a mix is over, over everything.
    // Not over an open panel: the lyrics or the queue drawing in and springing back with the mix
    // moved the lines being read (reported as particularly bad on the lyrics). Nor over a full-screen Canvas:
    // the clip stays full screen, so the controls sprang about over it and the wave crossed the
    // video (reported as the end of the mix bugging on a Canvas).
    val mixMotion = state.mixPulse.takeIf { panel == Panel.NONE && (!canvasOn || inSquare) }
    BoxWithConstraints(modifier.fillMaxSize().mixWave(mixMotion, Color.White)) {
        // Wider than tall (a phone on its side, a tablet), or a large portrait tablet: the cover
        // beside the controls. Very wide windows (DeX, large tablets): the lyrics and the queue
        // open in a third pane instead of over the cover.
        val window = com.music.spotui.ui.components.windowSize()
        // Never a phone held upright, whatever this box measures for a moment (WindowSize.portraitPhone).
        val landscape = window.playerSideBySide(maxWidth, maxHeight)
        val sidePane = window.large && maxWidth >= SIDE_PANE_MIN_WIDTH
        val playerWidth = maxWidth
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }.background(Color.Black)) {
            PlayerBackdrop(palette, outgoingPalette, blend, state.coverUrl)
        }
        // Landscape keeps Canvas inside the left square tile even when Portrait Fullscreen Canvas is enabled.
        if (!landscape && !inSquare && canvasUrl != null && !videoOn) {
            com.music.spotui.ui.screens.CanvasVideo(
                url = canvasUrl,
                onFirstFrame = { canvasShown = true },
                // Still while the sheet is closed, the song paused or a panel covers it.
                playing = PlayerSheet.open.value && state.playing && !state.buffering && panel == Panel.NONE,
                fullScreen = true,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val o = canvasOpen.value
                    alpha = o * (dock?.chromeAlpha ?: 1f)
                    // Settles from slightly closer as it opens.
                    val s = 1f + CANVAS_OPEN_ZOOM * (1f - o)
                    scaleX = s
                    scaleY = s
                },
            )
            // The words and the controls read over any clip; darker again under an open panel.
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = canvasOpen.value * (0.55f + 0.45f * collapse.value) * (dock?.chromeAlpha ?: 1f) }
                    .background(CanvasShade),
            )
        }
        // An AutoMix mix: the content draws in with it and springs back after it, the backdrop stays.
        Box(Modifier.fillMaxSize().mixSqueeze(mixMotion)) {
        CompositionLocalProvider(LocalPlayerTint provides tint) {
            if (landscape) LandscapePlayer(
                state = state,
                actions = actions,
                tint = tint,
                panel = panel,
                pauseScale = pauseScale,
                volume = volume,
                videoReady = videoReady,
                videoOn = videoOn,
                showVideo = showVideoSurface && (panel == Panel.NONE || sidePane),
                canvasUrl = if (!videoOn && !state.adPlaying) canvasUrl else null,
                canvasShown = canvasShown,
                onCanvasFirstFrame = { canvasShown = true },
                onToggleLyrics = { panel = if (lyricsOpen) Panel.NONE else Panel.LYRICS },
                onToggleQueue = { panel = if (queueOpen) Panel.NONE else Panel.QUEUE },
                onOpenDevices = { showDevices = true },
                onMore = if (navController != null && state.song != null) ({ showMenu = true }) else null,
                onSource = { showQuality = true },
                onSourceLong = { showNerd = true },
                sidePane = sidePane,
                dock = dock,
            ) else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // The handle: always hands its drags to the sheet.
                Box(Modifier.graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }) {
                    PlayerDismissStrip(tint) { actions.close() }
                }

                val fullScreenCanvas = !inSquare && canvasOn && panel == Panel.NONE
                val creditsBottomGap by animateDpAsState(if (fullScreenCanvas) 8.dp else 16.dp, tween(300), label = "creditsBottomGap")
                val controlsBottomPad by animateDpAsState(if (fullScreenCanvas) 6.dp else 18.dp, tween(300), label = "controlsBottomPad")
                val showVolume = com.music.spotui.data.preferences.PlayerVolumePref.on
                val hiddenVolumeSpacer by animateDpAsState(
                    targetValue = when {
                        showVolume -> 0.dp
                        fullScreenCanvas -> 16.dp
                        else -> 12.dp - 4.dp + VOLUME_ROW_HEIGHT + 6.dp
                    },
                    animationSpec = tween(300),
                    label = "hiddenVolumeSpacer",
                )

                // Large windows (a portrait tablet): the box below is no taller than the cover and
                // the credits need, and the two are centred with the controls under the handle.
                // Filled, the box's spare height went between the cover and the credits (190 dp).
                // Phones keep the box filling the height (the cap is then never reached).
                val blockMax = if (window.large) minOf(playerWidth - PLAYER_GUTTER * 2, PLAYER_MAX_WIDTH) +
                    ART_BOX_TOP_PAD + ART_TITLE_GAP + HEADER_HEIGHT + creditsBottomGap else Dp.Infinity
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                // Everything that changes between the full player and the panels lives in this
                // one weighted box, so the controls below it never move.
                BoxWithConstraints(Modifier.weight(1f, fill = !window.large).heightIn(max = blockMax).fillMaxWidth()) {
                    val contentWidth = minOf(maxWidth - PLAYER_GUTTER * 2, PLAYER_MAX_WIDTH)
                    val left = (maxWidth - contentWidth) / 2
                    val fullArt = minOf(contentWidth, maxHeight - ART_BOX_TOP_PAD - ART_TITLE_GAP - HEADER_HEIGHT - creditsBottomGap)
                        .coerceAtLeast(THUMB_SIZE)
                    // While the player is whole the credits sit just above the scrubber and the cover is
                    // centred in the room above them (centred as a pair, the spare height of a tall
                    // phone all went between the credits and the scrubber: reported as the like and
                    // more glyphs sitting far too high).
                    val spare = (maxHeight - ART_BOX_TOP_PAD - fullArt - ART_TITLE_GAP - HEADER_HEIGHT - creditsBottomGap)
                        .coerceAtLeast(0.dp)
                    val groupTop = spare / 2
                    // Functions of the collapse, called at placement and measure: composition never reads them.
                    fun p() = collapse.value
                    fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                    fun artTop(): Dp = ART_BOX_TOP_PAD + lerp(groupTop, 0.dp, p())
                    fun artStart(): Dp = left + lerp((contentWidth - fullArt) / 2, 0.dp, p())
                    fun titleTop(): Dp = ART_BOX_TOP_PAD + lerp(spare + fullArt + ART_TITLE_GAP, 0.dp, p())
                    fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())
                    // The credits only crawl while they are the screen: over a list they would pull the eye.
                    val scrolls by remember { derivedStateOf { collapse.value < 0.01f } }

                    // ---- The panel, under the header ----
                    val panelAlpha = { ((p() - 0.6f) / 0.4f).coerceIn(0f, 1f) * (dock?.chromeAlpha ?: 1f) }
                    Crossfade(
                        targetState = panel,
                        animationSpec = tween(250),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = ART_BOX_TOP_PAD + HEADER_HEIGHT)
                            .graphicsLayer { alpha = panelAlpha() },
                        label = "playerPanel",
                    ) { shown ->
                        val inset = PaddingValues(start = left, end = left, top = 12.dp, bottom = 12.dp)
                        when (shown) {
                            Panel.LYRICS -> ImmersiveLyricsPanel(Modifier.fillMaxSize(), inset)
                            Panel.QUEUE -> ImmersiveQueuePanel(Modifier.fillMaxSize(), inset)
                            else -> Box(Modifier.fillMaxSize())
                        }
                    }

                    // ---- The cover: the whole sleeve, then the header's thumbnail ----
                    val portraitSwipeX = remember { Animatable(0f) }
                    val portraitSwipeScope = rememberCoroutineScope()
                    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { TRACK_SWIPE_THRESHOLD.toPx() }.coerceAtLeast(1f)
                    var portraitSlotBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                            .layout { measurable, _ ->
                                val side = artSize().roundToPx()
                                val placeable = measurable.measure(Constraints.fixed(side, side))
                                layout(side, side) { placeable.place(0, 0) }
                            }
                            .onGloballyPositioned { coords -> portraitSlotBounds = measureSlotInSheet(dock, coords) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // The pause step back, let go of as the cover collapses into the header.
                                    // In full-screen Canvas mode, the sleeve opens into the clip: it grows and fades as the
                                    // clip comes in, and is the thumbnail again in the header. In square mode it stays in place.
                                    val dockTx = dockTransformForSlot(dock, portraitSlotBounds)
                                    val opened = if (inSquare) 0f else canvasOpen.value * (1f - p())
                                    val beat = 1f + MIX_BEAT_GROW * (state.mixPulse?.level() ?: 0f) * (1f - p())
                                    val dx = portraitSwipeX.value * (1f - p())
                                    val swipeFrac = (abs(dx) / thresholdPx).coerceIn(0f, 1.3f)
                                    val scale = (pauseScale.value + (1f - pauseScale.value) * p()) *
                                        (1f + CANVAS_COVER_GROW * opened) * beat * (1f - 0.045f * swipeFrac) * dockTx.scale
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = dx * 0.42f + dockTx.translationX
                                    translationY = dockTx.translationY
                                    rotationZ = (dx / thresholdPx).coerceIn(-1.2f, 1.2f) * 3.5f
                                    alpha = (1f - opened) * (1f - 0.25f * swipeFrac).coerceIn(0.6f, 1f)
                                    shadowElevation = 10.dp.toPx() * (1f - p()) * (1f - opened) * (dock?.chromeAlpha ?: 1f)
                                    shape = ArtCorner
                                    clip = true
                                }
                                .background(Color.Black.copy(alpha = 0.18f))
                                .dragToClose()
                                .swipeToChangeTrack(enabled = panel == Panel.NONE, actions = actions, dragX = portraitSwipeX, scope = portraitSwipeScope)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    if (panel != Panel.NONE) panel = Panel.NONE
                                },
                        ) {
                            if (showVideoSurface && panel == Panel.NONE) {
                                ImmersiveVideoFrame()
                            } else {
                                GlideImage(
                                    model = state.coverUrl.takeIf { it.isNotBlank() },
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    loading = placeholder(R.drawable.placeholder),
                                    failure = placeholder(R.drawable.placeholder),
                                    modifier = Modifier.fillMaxSize(),
                                    // The box shrinks to the 60dp header thumbnail and grows back: Glide sizes the
                                    // request from the constraints it first sees, so without a fixed size a cover
                                    // first measured (or reloaded) while collapsed stays a small bitmap.
                                    requestBuilderTransform = { it.override(COVER_DECODE_PX) },
                                )
                                SongBlendCover(blend, Modifier.fillMaxSize()) { it.override(COVER_DECODE_PX) }
                                if (inSquare && canvasUrl != null && !videoOn && !state.adPlaying) {
                                    com.music.spotui.ui.screens.CanvasVideo(
                                        url = canvasUrl,
                                        onFirstFrame = { canvasShown = true },
                                        // Still while the sheet is closed, the song paused or a panel covers it.
                                        playing = PlayerSheet.open.value && state.playing && !state.buffering && panel == Panel.NONE,
                                        modifier = Modifier.fillMaxSize().graphicsLayer {
                                            alpha = canvasOpen.value * (1f - p())
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // ---- Title + round glyphs ----
                    Row(
                        modifier = Modifier
                            .offset {
                                // Collapsed, the row shares the header with the thumbnail and the two
                                // are not the same height: centred on it.
                                IntOffset(
                                    left.roundToPx(),
                                    (titleTop() - lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())).roundToPx(),
                                )
                            }
                            .width(contentWidth)
                            .layout { measurable, constraints ->
                                val start = titleStart().roundToPx()
                                val placeable = measurable.measure(constraints.offset(horizontal = -start))
                                layout(
                                    constraints.constrainWidth(placeable.width + start),
                                    constraints.constrainHeight(placeable.height),
                                ) { placeable.placeRelative(start, 0) }
                            }
                            .height(HEADER_HEIGHT)
                            .graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }
                            .dragToClose(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerCredits(
                            state = state, actions = actions, tint = tint, scrolls = scrolls, collapse = { p() },
                            onMore = if (navController != null && state.song != null) ({ showMenu = true }) else null,
                        )
                    }
                }

                // ---- Bottom: scrubber, transport, volume, toggles ----
                // One block, measured at its natural height and pinned to the foot of the player.
                Column(
                    modifier = Modifier
                        .widthIn(max = PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }
                        .padding(horizontal = PLAYER_GUTTER)
                        .align(Alignment.CenterHorizontally),
                ) {
                    ImmersiveVideoSwitchSlot(
                        visible = (videoReady || videoOn) && panel == Panel.NONE,
                        videoOn = videoOn,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    PlayerControlsBlock(
                        state = state, actions = actions, volume = volume, lyricsOpen = lyricsOpen, queueOpen = queueOpen,
                        showVolume = showVolume,
                        // Lyrics and the queue are two things to show in one place: opening one closes the other.
                        onToggleLyrics = { panel = if (lyricsOpen) Panel.NONE else Panel.LYRICS },
                        onToggleQueue = { panel = if (queueOpen) Panel.NONE else Panel.QUEUE },
                        onOpenDevices = { showDevices = true },
                        onSource = { showQuality = true },
                        onSourceLong = { showNerd = true },
                        bottomPad = controlsBottomPad,
                    )
                    // In Square cover mode, a hidden volume bar keeps its room so the centered sleeve
                    // does not shift; in Full-Screen Canvas mode the bottom stack drops slightly lower.
                    if (hiddenVolumeSpacer > 0.dp) Spacer(Modifier.height(hiddenVolumeSpacer))
                }
                }
            }
        }
        }
    }

    if (videoOn && mv.fullscreen.value) com.music.spotui.ui.components.MusicVideoFullscreen()
    CompositionLocalProvider(LocalImmersiveSheetTint provides sheetTint) {
        if (showDevices) DevicesSheet { showDevices = false }
        if (showQuality) com.music.spotui.ui.components.QualitySheet { showQuality = false }
        if (showNerd) com.music.spotui.ui.components.StatsForNerdsSheet { showNerd = false }
    }
    val song = state.song
    if (showMenu && song != null && navController != null) {
        ImmersiveSongOptionsSheet(
            song = song,
            navController = navController,
            context = context,
            onDismiss = { showMenu = false },
            nowPlaying = true,
            onOpenQueue = { panel = Panel.QUEUE },
            lyricsOpen = lyricsOpen,
        )
    }
}

/** The cover opening into the Canvas, and the clip's fade in. */
private const val CANVAS_OPEN_MS = 720
/** How much closer the clip starts as it opens. */
private const val CANVAS_OPEN_ZOOM = 0.08f
/** How much the sleeve grows as it opens into the clip. */
private const val CANVAS_COVER_GROW = 0.12f
/** Over a Canvas: slim scrim under top bar and lowered bottom controls, leaving the middle clear. */
private val CanvasShade = Brush.verticalGradient(
    0.00f to Color.Black.copy(alpha = 0.42f),
    0.18f to Color.Black.copy(alpha = 0.02f),
    0.58f to Color.Black.copy(alpha = 0.04f),
    0.74f to Color.Black.copy(alpha = 0.40f),
    1.00f to Color.Black.copy(alpha = 0.84f),
)

/**
 * The Canvas of the song shown, as the Classic player finds it (same view model, cache and
 * settings: Canvases off, data saver, Wi-Fi only, energy saving); null when there is none.
 */
@Composable
private fun rememberImmersiveCanvas(state: ImmersivePlayerState): String? {
    val context = LocalContext.current
    val vm: com.music.spotui.ui.viewmodel.PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val allowed = com.music.spotui.ui.components.isCanvasAllowed(context, energy.policy)
    val extRevision by com.music.spotui.extensions.Extensions.canvasRevision
    val hasExtCanvas = com.music.spotui.extensions.Extensions.hasCanvas()
    val trackId = com.music.spotui.ui.components.canvasLookupTrackId(
        rawTrackId = state.trackId,
        hasExtension = hasExtCanvas,
        title = state.title,
        artist = state.artist,
    )
    LaunchedEffect(trackId, allowed, state.buffering, extRevision, state.title, state.artist) {
        vm.loadCanvas(
            trackId = if (allowed) trackId else "",
            allowFetch = !state.buffering,
            title = state.title,
            artist = state.artist,
            album = state.album,
            durationMs = state.durationMs,
            cover = state.coverUrl,
            revision = extRevision,
        )
    }
    val canvas = vm.canvasState.value
    // Known already (looked up when the song started): this very frame, not one later.
    val known = remember(trackId, extRevision) { com.music.spotui.di.Graph.api.knownCanvas(trackId)?.getOrNull() }
    return (if (canvas.trackId == trackId) canvas.url else known).takeIf { allowed && trackId.isNotBlank() && !state.adPlaying }
}

/** Shared by Portrait and Landscape Immersive player layouts: renders the synced video and a theme-blended fullscreen button. */
@Composable
private fun ImmersiveVideoFrame() {
    val tint = LocalPlayerTint.current
    val backdrop = Color.Black.copy(alpha = 0.28f)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        com.music.spotui.ui.components.VideoSurface(
            onTap = { com.music.spotui.video.MusicVideoController.fullscreen.value = true },
        )
        com.music.spotui.ui.components.VideoFullscreenButton(
            modifier = Modifier.align(Alignment.BottomEnd),
            containerColor = tint.copy(alpha = 0.16f).compositeOver(backdrop),
            contentColor = tint.copy(alpha = 0.92f),
            borderColor = tint.copy(alpha = 0.24f),
        )
    }
}

@Composable
private fun ImmersiveVideoSwitchSlot(
    visible: Boolean,
    videoOn: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut(tween(160)) + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        ImmersiveVideoPill(on = videoOn, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/**
 * The mesh behind the player. During a crossfade its colours go from [outgoing] to [palette] with
 * the fade's progress, read here so only the backdrop follows it frame by frame, not the player.
 */
@Composable
private fun PlayerBackdrop(palette: MeshPalette, outgoing: MeshPalette, blend: SongBlend?, trackKey: String) {
    MeshGradientBackground(
        palette = if (blend == null) palette else lerp(outgoing, palette, blend.progress()),
        trackKey = trackKey,
        // Following the fade, a short smoothing only: the new cover's colours, known a moment
        // into it, do not jump in.
        colorFadeMillis = if (blend == null) 1400 else 250,
    )
}

/**
 * The credits row of the player: title (with its explicit badge) and artist,
 * then the like glyph and, when [onMore] is given, the more glyph. [collapse] is the cover's
 * travel to the header (0 whole, 1 collapsed); the credits shrink with it.
 */
@Composable
private fun RowScope.PlayerCredits(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    tint: Color,
    scrolls: Boolean,
    collapse: () -> Float,
    onMore: (() -> Unit)?,
) {
    val lineH = if (state.adPlaying) 24.sp else 22.sp
    Column(
        Modifier
            .weight(1f)
            // Measured at its own height and centred on the header's: held to HEADER_HEIGHT, the
            // title/artist block can clip at the bottom with a larger font.
            .wrapContentHeight(unbounded = true)
            // Scaling the already measured type is a draw-only operation.
            .graphicsLayer {
                val scale = 1f - 0.2f * collapse()
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                // A crossfade: the outgoing song's words fade out, the new one's in, once.
                alpha = state.blend?.wordsAlpha() ?: 1f
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.adPlaying) stringResource(R.string.immersive_player_ad) else state.title,
                style = Immersive.Title.copy(fontSize = 20.sp, lineHeight = lineH),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    // The marquee inside the tap target: scrolling leaves the target as it was.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.openAlbum() }
                    .titleMarquee(enabled = scrolls, iterations = Int.MAX_VALUE),
            )
            if (state.explicit && !state.adPlaying) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(tint.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.explicit_badge),
                        style = Immersive.Caption.copy(fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold),
                        color = Color.Black,
                    )
                }
            }
        }
        if (!state.adPlaying) {
            // One tap per artist when the song's artists are known; the whole line otherwise.
            val credit = com.music.spotui.ui.components.rememberArtistCredit(state.trackId, state.artist, tint.copy(alpha = 0.55f)) { a -> actions.openArtistRef(a) }
            // The names are tap targets only while the line holds still: a scrolling line draws them
            // away from where their taps land, and is one target for the choice of its artists.
            val lineScrolls = com.music.spotui.ui.components.rememberArtistLineScrolls(state.artist)
            Text(
                text = credit?.takeIf { !lineScrolls.value } ?: androidx.compose.ui.text.AnnotatedString(state.artist),
                style = Immersive.Title.copy(fontSize = 20.sp, lineHeight = lineH, fontWeight = FontWeight.W500),
                color = tint.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) lineScrolls.value = true },
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.openArtist() }
                    .titleMarquee(enabled = scrolls && lineScrolls.value, iterations = Int.MAX_VALUE),
            )
        }
    }
    Spacer(Modifier.width(10.dp))
    // Beside the credits rather than in the toggle row: liking is about this song.
    CircleGlyph(
        icon = if (state.liked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        contentDescription = stringResource(if (state.liked) R.string.immersive_player_unlike else R.string.immersive_player_like),
        onClick = actions.toggleLike,
        active = state.liked,
    )
    if (onMore != null) {
        Spacer(Modifier.width(8.dp))
        CircleGlyph(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = stringResource(R.string.immersive_player_more),
            onClick = onMore,
        )
    }
}

/** Scrubber, transport, volume, the bottom row and the extensions' buttons: the controls under the cover. */
@Composable
private fun PlayerControlsBlock(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    volume: PlayerVolume,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    showVolume: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenDevices: () -> Unit,
    onSource: () -> Unit = {},
    onSourceLong: () -> Unit = {},
    compact: Boolean = false,
    bottomPad: Dp? = null,
) {
    PlayerScrubber(state = state, onSeek = actions.seekTo, onSource = onSource, onSourceLong = onSourceLong)
    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
    TransportRow(
        isPlaying = state.playing,
        isLoading = state.buffering,
        onPrevious = actions.previous,
        onPlayPause = actions.playPause,
        onNext = actions.next,
        compact = compact,
    )
    Spacer(Modifier.height(if (showVolume) (if (compact) 4.dp else 12.dp) else (if (compact) 2.dp else 4.dp)))
    if (showVolume) {
        VolumeRow(volume)
        // The volume slider already has room below its drawn track.
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
    }
    PlayerActionRow(
        lyricsOpen = lyricsOpen,
        queueOpen = queueOpen,
        shuffleEnabled = state.shuffle || state.smart,
        smartEnabled = state.smart,
        repeatMode = state.repeatMode,
        deviceActive = state.deviceName != null,
        onToggleLyrics = onToggleLyrics,
        onToggleQueue = onToggleQueue,
        onToggleShuffle = actions.toggleShuffle,
        onCycleRepeat = actions.cycleRepeat,
        onOpenDevices = onOpenDevices,
    )
    // Buttons from extensions (player.buttons), under the player's own controls.
    val extTrack = remember(state.title, state.artist) { com.music.spotui.extensions.Extensions.currentTrack() }
    com.music.spotui.extensions.MirrorIndicator()
    com.music.spotui.extensions.ExtensionPlayerButtons(extTrack)
    Spacer(Modifier.height(bottomPad ?: if (compact) 6.dp else 18.dp))
}

/**
 * The landscape artwork/Canvas tile: stays pinned on the left both during playback controls and
 * while Lyrics/Queue is expanded on phones (Apple Music split layout). Tapping it in split mode
 * toggles the temporary Portrait-style controls on the right Lyrics/Queue column.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LandscapeArtworkPane(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    pauseScale: State<Float>,
    showVideo: Boolean,
    videoOn: Boolean,
    canvasUrl: String?,
    canvasShown: Boolean,
    onCanvasFirstFrame: () -> Unit,
    framedSplit: Boolean,
    swipeEnabled: Boolean,
    onTapArtwork: (() -> Unit)?,
    dock: PlayerDockController? = null,
) {
    val swipeX = remember { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { TRACK_SWIPE_THRESHOLD.toPx() }.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight) * (if (framedSplit) 0.90f else 1f)
        var slotBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        Box(
            Modifier
                .size(side)
                .onGloballyPositioned { coords -> slotBounds = measureSlotInSheet(dock, coords) },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("landscapeArtwork")
                    .graphicsLayer {
                        // The pause step back, as in the portrait layout, and the mix's beat.
                        val dockTx = dockTransformForSlot(dock, slotBounds)
                        val beat = 1f + MIX_BEAT_GROW * (state.mixPulse?.level() ?: 0f)
                        val dx = swipeX.value
                        val swipeFrac = (abs(dx) / thresholdPx).coerceIn(0f, 1.3f)
                        val scale = pauseScale.value * beat * (1f - 0.045f * swipeFrac) * dockTx.scale
                        scaleX = scale
                        scaleY = scale
                        translationX = dx * 0.42f + dockTx.translationX
                        translationY = dockTx.translationY
                        rotationZ = (dx / thresholdPx).coerceIn(-1.2f, 1.2f) * 3.5f
                        alpha = (1f - 0.25f * swipeFrac).coerceIn(0.6f, 1f)
                        shadowElevation = 10.dp.toPx() * (dock?.chromeAlpha ?: 1f)
                        shape = ArtCorner
                        clip = true
                    }
                    .background(Color.Black.copy(alpha = 0.18f))
                    .dragToClose()
                    .swipeToChangeTrack(enabled = swipeEnabled, actions = actions, dragX = swipeX, scope = swipeScope),
            ) {
                if (showVideo) {
                    ImmersiveVideoFrame()
                } else {
                    GlideImage(
                        model = state.coverUrl.takeIf { it.isNotBlank() },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        modifier = Modifier.fillMaxSize(),
                        requestBuilderTransform = { it.override(COVER_DECODE_PX) },
                    )
                    SongBlendCover(state.blend, Modifier.fillMaxSize()) { it.override(COVER_DECODE_PX) }
                    if (canvasUrl != null && !videoOn) {
                        val landscapeAlpha by animateFloatAsState(
                            targetValue = if (canvasShown) 1f else 0f,
                            animationSpec = tween(CANVAS_OPEN_MS),
                            label = "landscapeCanvasAlpha",
                        )
                        com.music.spotui.ui.screens.CanvasVideo(
                            url = canvasUrl,
                            onFirstFrame = onCanvasFirstFrame,
                            playing = PlayerSheet.open.value && state.playing && !state.buffering,
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = landscapeAlpha },
                        )
                    }
                }
                // Topmost bare-tap catcher above AndroidView (CanvasVideo/PlayerView) in split mode so
                // tapping the artwork/Canvas reveals or hides playback controls on the lyrics side.
                if (onTapArtwork != null) {
                    val tapHandler by rememberUpdatedState(onTapArtwork)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        up.consume()
                                        tapHandler()
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * The landscape layout: keeps the cover on the left (matching Apple Music's landscape view where
 * Lyrics or Queue fills the right column directly beneath `Title / Artist / ★ / •••` on phones,
 * with temporary Portrait-style controls shown on the Lyrics side when tapping outside the panel).
 */
@Composable
private fun LandscapePlayer(
    state: ImmersivePlayerState,
    actions: ImmersivePlayerActions,
    tint: Color,
    panel: Int,
    pauseScale: State<Float>,
    volume: PlayerVolume,
    videoReady: Boolean,
    videoOn: Boolean,
    showVideo: Boolean,
    canvasUrl: String? = null,
    canvasShown: Boolean = false,
    onCanvasFirstFrame: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenDevices: () -> Unit,
    onMore: (() -> Unit)?,
    onSource: () -> Unit,
    onSourceLong: () -> Unit = {},
    sidePane: Boolean = false,
    dock: PlayerDockController? = null,
) {
    val splitPanel = !sidePane && panel != Panel.NONE
    val leftWeight by animateFloatAsState(
        targetValue = if (splitPanel) 0.88f else 1f,
        animationSpec = tween(250),
        label = "landscapeLeftWeight",
    )
    val rightWeight by animateFloatAsState(
        targetValue = if (splitPanel) 1.12f else 1f,
        animationSpec = tween(250),
        label = "landscapeRightWeight",
    )

    var splitControlsVisible by rememberSaveable { mutableStateOf(false) }
    var splitControlsTick by remember { mutableIntStateOf(0) }
    var splitControlsInteracting by remember { mutableStateOf(false) }
    val controlsShowing = splitPanel && splitControlsVisible

    LaunchedEffect(splitPanel, panel) {
        splitControlsVisible = false
        splitControlsInteracting = false
    }

    LaunchedEffect(splitPanel, splitControlsVisible, splitControlsTick, splitControlsInteracting) {
        if (splitPanel && splitControlsVisible && !splitControlsInteracting) {
            delay(LANDSCAPE_CONTROLS_TIMEOUT_MS)
            splitControlsVisible = false
        }
    }

    val toggleOrRevealSplitControls = {
        if (splitPanel) {
            if (splitControlsVisible) {
                splitControlsVisible = false
            } else {
                splitControlsVisible = true
                splitControlsTick++
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .then(
                if (splitPanel) {
                    Modifier.pointerInput(panel) {
                        detectTapGestures { toggleOrRevealSplitControls() }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // The handle: always hands its drags to the sheet.
        Box(Modifier.graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }) {
            PlayerDismissStrip(tint) { actions.close() }
        }
        val splitOuterEnd = 10.dp
        val splitContentEnd = (PLAYER_GUTTER - splitOuterEnd).coerceAtLeast(0.dp)
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(
                start = PLAYER_GUTTER,
                end = if (splitPanel) splitOuterEnd else PLAYER_GUTTER,
                bottom = if (splitPanel) 4.dp else 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(PLAYER_GUTTER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(leftWeight).fillMaxHeight(), contentAlignment = Alignment.Center) {
                LandscapeArtworkPane(
                    state = state,
                    actions = actions,
                    pauseScale = pauseScale,
                    showVideo = showVideo,
                    videoOn = videoOn,
                    canvasUrl = canvasUrl,
                    canvasShown = canvasShown,
                    onCanvasFirstFrame = onCanvasFirstFrame,
                    framedSplit = splitPanel,
                    swipeEnabled = !controlsShowing,
                    onTapArtwork = if (splitPanel) toggleOrRevealSplitControls else null,
                    dock = dock,
                )
            }
            BoxWithConstraints(Modifier.weight(rightWeight).fillMaxHeight().graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }, contentAlignment = Alignment.Center) {
                val compactHeight = maxHeight < 430.dp
                val showVideoInSplitOverlay = (videoReady || videoOn) && maxHeight >= 350.dp
                // The volume bar only drops out when the window is extremely short: phones keep it with compact controls.
                val showVolume = com.music.spotui.data.preferences.PlayerVolumePref.on && maxHeight >= LANDSCAPE_VOLUME_MIN_HEIGHT
                Crossfade(
                    targetState = if (sidePane) Panel.NONE else panel,
                    animationSpec = tween(250),
                    modifier = Modifier.fillMaxSize(),
                    label = "playerLandscapeRightPane",
                ) { shown ->
                    when (shown) {
                        Panel.LYRICS, Panel.QUEUE -> Column(
                            Modifier
                                .fillMaxSize()
                                .widthIn(max = 640.dp)
                                .padding(top = if (compactHeight) 0.dp else 4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(end = splitContentEnd),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayerCredits(state, actions, tint, scrolls = true, collapse = { 0f }, onMore = onMore)
                            }
                            Spacer(Modifier.height(4.dp))
                            val panelAlpha by animateFloatAsState(
                                targetValue = if (controlsShowing) 0.10f else 1f,
                                animationSpec = tween(220),
                                label = "landscapePanelDimAlpha",
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = panelAlpha }
                                        .testTag("landscapePanelContent")
                                        .pointerInput(Unit) {
                                            // Keep taps inside the Lyrics/Queue pane from revealing playback controls.
                                            awaitEachGesture {
                                                awaitFirstDown(requireUnconsumed = false)
                                                val up = waitForUpOrCancellation()
                                                up?.consume()
                                            }
                                        },
                                ) {
                                    if (shown == Panel.LYRICS) {
                                        ImmersiveLyricsPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp, end = splitContentEnd),
                                            landscapeSplit = true,
                                        )
                                    } else {
                                        ImmersiveQueuePanel(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp, end = splitContentEnd),
                                        )
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = controlsShowing,
                                    enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 10 },
                                    exit = fadeOut(tween(240)),
                                    modifier = Modifier.fillMaxSize().padding(end = splitContentEnd).testTag("landscapeSplitControls"),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    var wasPressed = false
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        val pressed = event.changes.any { it.pressed }
                                                        if (pressed != splitControlsInteracting) {
                                                            splitControlsInteracting = pressed
                                                        }
                                                        if (wasPressed && !pressed) {
                                                            splitControlsTick++
                                                        }
                                                        wasPressed = pressed
                                                    }
                                                }
                                            }
                                            .pointerInput(Unit) {
                                                detectTapGestures { toggleOrRevealSplitControls() }
                                            },
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .widthIn(max = PLAYER_MAX_WIDTH)
                                                .fillMaxWidth()
                                                .verticalScroll(rememberScrollState())
                                                .pointerInput(Unit) {
                                                    // Absorb bare taps between control rows so slight misses reset the timer instead of dismissing.
                                                    detectTapGestures { splitControlsTick++ }
                                                },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            ImmersiveVideoSwitchSlot(
                                                visible = showVideoInSplitOverlay,
                                                videoOn = videoOn,
                                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                            )
                                            PlayerControlsBlock(
                                                state = state,
                                                actions = actions,
                                                volume = volume,
                                                lyricsOpen = panel == Panel.LYRICS,
                                                queueOpen = panel == Panel.QUEUE,
                                                showVolume = showVolume,
                                                onToggleLyrics = onToggleLyrics,
                                                onToggleQueue = onToggleQueue,
                                                onOpenDevices = onOpenDevices,
                                                onSource = onSource,
                                                onSourceLong = onSourceLong,
                                                compact = compactHeight,
                                                bottomPad = 2.dp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(Modifier.widthIn(max = PLAYER_MAX_WIDTH).verticalScroll(rememberScrollState())) {
                                Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                                    PlayerCredits(state, actions, tint, scrolls = true, collapse = { 0f }, onMore = onMore)
                                }
                                Spacer(Modifier.height(if (compactHeight) 4.dp else 8.dp))
                                ImmersiveVideoSwitchSlot(
                                    visible = (videoReady || videoOn) && (panel == Panel.NONE || sidePane),
                                    videoOn = videoOn,
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                )
                                PlayerControlsBlock(
                                    state = state, actions = actions, volume = volume,
                                    lyricsOpen = panel == Panel.LYRICS, queueOpen = panel == Panel.QUEUE, showVolume = showVolume,
                                    onToggleLyrics = onToggleLyrics, onToggleQueue = onToggleQueue, onOpenDevices = onOpenDevices,
                                    onSource = onSource, onSourceLong = onSourceLong,
                                    compact = compactHeight,
                                )
                            }
                        }
                    }
                }
            }
            if (sidePane && panel != Panel.NONE) Box(Modifier.weight(1.1f).fillMaxHeight().graphicsLayer { alpha = dock?.chromeAlpha ?: 1f }) {
                Crossfade(targetState = panel, animationSpec = tween(250), modifier = Modifier.fillMaxSize(), label = "playerSidePane") { shown ->
                    when (shown) {
                        Panel.LYRICS -> ImmersiveLyricsPanel(Modifier.fillMaxSize(), PaddingValues(vertical = 4.dp))
                        Panel.QUEUE -> ImmersiveQueuePanel(Modifier.fillMaxSize(), PaddingValues(vertical = 4.dp))
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** How long the temporary Portrait-style controls stay visible in landscape split mode before fading out. */
private const val LANDSCAPE_CONTROLS_TIMEOUT_MS = 2800L

/** A window at least this wide shows the lyrics and the queue beside the cover and the controls. */
private val SIDE_PANE_MIN_WIDTH = 1100.dp

/** Minimum right-column height to show the swipeable volume bar in landscape mode (fits 411dp phones). */
private val LANDSCAPE_VOLUME_MIN_HEIGHT = 260.dp
