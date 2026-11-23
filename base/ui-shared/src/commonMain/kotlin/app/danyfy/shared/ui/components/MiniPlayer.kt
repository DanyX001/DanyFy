package app.danyfy.shared.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.danyfy.shared.data.PreviewPlayerState
import app.danyfy.shared.data.SampleArt
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.aligned_curation_context_menu_item_add_to_liked_songs
import app.danyfy.shared.resources.content_description_pause_button
import app.danyfy.shared.resources.content_description_play_button
import app.danyfy.shared.resources.ic_add
import app.danyfy.shared.resources.ic_devices
import app.danyfy.shared.resources.mr_user_route_category_name
import app.danyfy.shared.resources.remove_from_liked
import app.danyfy.shared.ui.navigation.PlayerSheet
import app.danyfy.shared.ui.theme.AppAccent
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.AppText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

private val MINI_TOUCH = 44.dp

private data class MiniSong(val id: String, val title: String, val singer: String, val art: SampleArt)

private val CheckVector: ImageVector = ImageVector.Builder("check", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 3f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }
}.build()

@Composable
fun LikeButton(liked: Boolean, size: Dp = 28.dp, touch: Dp = size, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val firstShown = remember { booleanArrayOf(true) }
    val reduce = rememberReduceMotion()
    LaunchedEffect(liked) {
        if (firstShown[0]) { firstShown[0] = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        scale.snapTo(0.6f)
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    val mod = Modifier
        .size(maxOf(size, touch))
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }
        .wrapContentSize()
        .size(size)
    val disc by animateColorAsState(
        if (liked) AppAccent else Color.Transparent,
        if (reduce) snap() else tween(180),
        label = "likeDisc",
    )
    Box(contentAlignment = Alignment.Center, modifier = mod.background(disc, CircleShape)) {
        Crossfade(
            liked,
            animationSpec = if (reduce) snap() else tween(180),
            label = "likeGlyph",
        ) { on ->
            if (on) {
                Icon(CheckVector, contentDescription = stringResource(Res.string.remove_from_liked), tint = Color.Black, modifier = Modifier.size(size * 0.7f))
            } else {
                Icon(painterResource(Res.drawable.ic_add), contentDescription = stringResource(Res.string.aligned_curation_context_menu_item_add_to_liked_songs), tint = Color.White, modifier = Modifier.size(size))
            }
        }
    }
}

@Composable
fun MiniPlayer() {
    val song = PreviewPlayerState.currentSong
    val prev = PreviewPlayerState.prevSong
    val next = PreviewPlayerState.nextSong
    val playing = PreviewPlayerState.isPlaying
    val liked = PreviewPlayerState.isLiked(song.id)

    val tint = artworkTint(song.art)
    val animatedTint by animateColorAsState(tint, tween(400), label = "miniTint")

    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    LaunchedEffect(song.id) { if (pager.currentPage != 1) pager.scrollToPage(1) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            when (page) {
                2 -> PreviewPlayerState.next()
                0 -> PreviewPlayerState.previous()
            }
        }
    }

    Column(
        modifier = Modifier
            .pressable(0.96f) { PlayerSheet.show() }
            .onGloballyPositioned { PlayerSheet.miniTop = it.boundsInWindow().top }
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { drawRect(animatedTint) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(start = 8.dp, end = 3.dp)
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
            ) { page ->
                val s = when (page) {
                    0 -> prev
                    2 -> next
                    else -> song
                }
                val shown = MiniSong(s.id, s.title, s.singer, s.art)
                Crossfade(
                    targetState = shown,
                    animationSpec = tween(if (page == 1 && pager.currentPage == 1) 220 else 0),
                    label = "miniSong",
                ) { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        SampleArtwork(
                            art = m.art,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.size(40.dp),
                        )
                        Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp)) {
                            Text(
                                text = m.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.titleMarquee(iterations = 1),
                            )
                            Text(
                                text = m.singer,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                lineHeight = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.titleMarquee(iterations = 1),
                            )
                        }
                    }
                }
            }
            Icon(
                painter = painterResource(Res.drawable.ic_devices),
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(MINI_TOUCH)
                    .pressable(0.96f) { PlayerSheet.show() }
                    .wrapContentSize()
                    .size(22.dp),
                contentDescription = stringResource(Res.string.mr_user_route_category_name),
            )
            LikeButton(liked = liked, size = 24.dp, touch = MINI_TOUCH) {
                PreviewPlayerState.toggleLike(song.id)
            }
            Spacer(modifier = Modifier.width(1.dp))
            PlayPauseIcon(
                playing = playing,
                contentDescription = stringResource(if (playing) Res.string.content_description_pause_button else Res.string.content_description_play_button),
                tint = Color.White,
                modifier = Modifier
                    .size(MINI_TOUCH)
                    .pressable(0.96f) { PreviewPlayerState.togglePlay() }
                    .wrapContentSize()
                    .size(26.dp),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .graphicsLayer()
        ) {
            val p = PreviewPlayerState.progressFraction.coerceIn(0f, 1f)
            drawRect(Color.White.copy(alpha = 0.25f))
            drawRect(Color.White, size = size.copy(width = size.width * p))
        }
    }
}

@Composable
fun Snackbar(showMessage: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AppText),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            fontWeight = FontWeight.W500,
            fontSize = 14.sp,
            color = AppBackground,
            text = showMessage,
        )
    }
}
