package app.danyfy.shared.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.VisibilityThreshold
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.ic_playing
import app.danyfy.shared.resources.play_svgrepo_com
import org.jetbrains.compose.resources.painterResource

@Composable
fun rememberReduceMotion(): Boolean = false

/**
 * Official-app press feedback: the control shrinks a little under the finger and
 * springs back on release, no ripple. Animates only while pressed.
 */
fun Modifier.pressable(
    pressedScale: Float = 0.9f,
    enabled: Boolean = true,
    onPressHint: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hinted = if (onPressHint == null) Modifier else Modifier.pressHint(onPressHint)
    hinted.then(pressScale(source, pressedScale))
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

val MinTouch = 48.dp

fun Modifier.pressHint(onHint: () -> Unit): Modifier = composed {
    val currentOnHint by rememberUpdatedState(onHint)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            var moved = false
            withTimeoutOrNull(80) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > slop) { moved = true; break }
                    if (!change.pressed) break
                }
            }
            if (!moved) currentOnHint()
        }
    }
}

fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.9f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

object Motion {
    const val FADE_MS = 150
    const val CONTENT_MS = 220
}

@Composable
fun <T> GlyphSwap(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val reduce = rememberReduceMotion()
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(90)))
        },
        contentAlignment = Alignment.Center,
        label = "glyphSwap",
    ) { content(it) }
}

@Composable
fun PlayPauseIcon(
    playing: Boolean,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    GlyphSwap(playing) { isPlaying ->
        Icon(
            painter = painterResource(if (isPlaying) Res.drawable.ic_playing else Res.drawable.play_svgrepo_com),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
    }
}

@Composable
fun LoadedFade(loading: Boolean, modifier: Modifier = Modifier, content: @Composable (loading: Boolean) -> Unit) {
    val reduce = rememberReduceMotion()
    Crossfade(
        targetState = loading,
        modifier = modifier,
        animationSpec = if (reduce) snap() else tween(Motion.CONTENT_MS),
        label = "loadedFade",
    ) { content(it) }
}

@Composable
fun LazyItemScope.arrivingRow(): Modifier =
    if (rememberReduceMotion()) Modifier
    else Modifier.animateItem(fadeInSpec = tween(Motion.CONTENT_MS), placementSpec = null, fadeOutSpec = null)

@Composable
fun LazyItemScope.leavingPlaceholder(): Modifier =
    if (rememberReduceMotion()) Modifier
    else Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = tween(Motion.CONTENT_MS))

@Composable
fun LazyItemScope.animatedRow(): Modifier = Modifier.animateItem(
    fadeInSpec = null,
    fadeOutSpec = null,
    placementSpec = if (rememberReduceMotion()) null else spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    ),
)
