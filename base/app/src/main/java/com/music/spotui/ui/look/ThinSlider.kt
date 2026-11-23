/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ThinSlider.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: only the slider and its loading sheen (lines 54-245 and the sheen it draws);
 * the Automix beat pulse, the mix crossfade and the transition window are left out (Myspoti's
 * own AutoMix stretch is drawn by ui/components/AutoMixVisuals.kt).
 */
package com.music.spotui.ui.look

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Apple Music-style variable scrubbing speed factors when sliding your finger vertically away from the scrubber. */
enum class ScrubSpeed(val factor: Float) {
    FULL(1f),
    HALF(0.5f),
    QUARTER(0.25f),
    FINE(0.1f),
}

internal fun scrubSpeedForVerticalDp(verticalDp: Float): ScrubSpeed = when {
    verticalDp >= 116f -> ScrubSpeed.FINE
    verticalDp >= 74f -> ScrubSpeed.QUARTER
    verticalDp >= 36f -> ScrubSpeed.HALF
    else -> ScrubSpeed.FULL
}

/**
 * The Immersive scrubber: a hairline capsule with no thumb knob, which
 * thickens under your finger and settles back when you let go. Material's
 * Slider can't be shaped like this — it always draws a thumb and a tall
 * track — so this is drawn directly.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    loading: Boolean = false,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
    swipeOnly: Boolean = false,
    onGrabChanged: ((Boolean) -> Unit)? = null,
    variableScrubSpeed: Boolean = false,
    onScrubSpeedChange: ((ScrubSpeed?) -> Unit)? = null,
) = ThinSlider(
    value = { value },
    onValueChange = onValueChange,
    modifier = modifier,
    onValueChangeFinished = onValueChangeFinished,
    loading = loading,
    idleHeight = idleHeight,
    activeHeight = activeHeight,
    activeColor = activeColor,
    inactiveColor = inactiveColor,
    swipeOnly = swipeOnly,
    onGrabChanged = onGrabChanged,
    variableScrubSpeed = variableScrubSpeed,
    onScrubSpeedChange = onScrubSpeedChange,
)

/**
 * The same bar, with the position read by [value] rather than passed in. [value] is read only
 * where the bar is drawn, so a value that moves every frame redraws the bar without recomposing
 * the screen that holds it.
 */
@Composable
fun ThinSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * Sends a travelling sheen across the whole bar — played *and* unplayed,
     * the bar's full thickness — for as long as it is true.
     *
     * A wait with no measurable fraction to draw (the song still loading) gets
     * the bar itself rather than a second, indeterminate line beside it: no
     * extra chrome, nothing shifting under it, just motion along the bar,
     * pointing the way the music is going.
     */
    loading: Boolean = false,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
    /**
     * When true (used for the Immersive volume bar), bare taps never jump the value; the user
     * must swipe horizontally after touch-slop, applying smooth relative deltas while a
     * press-and-hold triggers the GPU-driven grab bounce animation.
     */
    swipeOnly: Boolean = false,
    onGrabChanged: ((Boolean) -> Unit)? = null,
    /**
     * When true (with [swipeOnly] == false), dragging downward away from the scrubber shifts
     * horizontal scrubbing into Half-Speed / Quarter-Speed / Fine precision modes.
     */
    variableScrubSpeed: Boolean = false,
    onScrubSpeedChange: ((ScrubSpeed?) -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    var grabbed by remember { mutableStateOf(false) }
    var sliding by remember { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()
    // The gesture loop below starts once; it calls the latest callbacks and reads the latest
    // value lambda, not stale initial captures across tracks.
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val grabChanged by rememberUpdatedState(onGrabChanged)
    val speedChanged by rememberUpdatedState(onScrubSpeedChange)

    val expandState = animateFloatAsState(
        targetValue = if (pressed || grabbed || sliding) 1f else 0f,
        animationSpec = spring(
            dampingRatio = if (swipeOnly) 0.50f else Spring.DampingRatioMediumBouncy,
            stiffness = if (swipeOnly) 420f else Spring.StiffnessMediumLow,
        ),
        label = "sliderExpand",
    )
    val grabAnim = remember { Animatable(1f) }
    LaunchedEffect(swipeOnly, pressed, grabbed) {
        if (!swipeOnly) {
            if (grabAnim.value != 1f) grabAnim.snapTo(1f)
            return@LaunchedEffect
        }
        when {
            grabbed -> {
                grabAnim.animateTo(
                    targetValue = GRAB_HELD_SCALE,
                    initialVelocity = GRAB_POP_VELOCITY,
                    animationSpec = spring(dampingRatio = 0.36f, stiffness = 560f),
                )
            }
            pressed -> {
                grabAnim.animateTo(
                    targetValue = GRAB_SQUEEZE_SCALE,
                    animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                )
            }
            else -> {
                grabAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.52f, stiffness = 460f),
                )
            }
        }
    }

    // The sheen gets a minimum beat even when the wait resolves instantly, so
    // its entry, its sweep and its hand-over to the progress bar always play
    // out as one continuous morph. A flip back to loading during the hold
    // cancels it and the sheen simply stays, so rapid toggling never blinks
    // the bar out mid-morph.
    var shownLoading by remember { mutableStateOf(loading) }
    var sheenStart by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(loading) {
        if (loading) {
            sheenStart = System.nanoTime()
            shownLoading = true
        } else {
            val remaining = LOADING_MIN_MS - (System.nanoTime() - sheenStart) / 1_000_000L
            if (remaining > 0) delay(remaining)
            shownLoading = false
        }
    }
    val sheenVisible = shownLoading
    val fillFactorState = animateFloatAsState(
        targetValue = if (sheenVisible) 0f else 1f,
        animationSpec = tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing),
        label = "fillFactor",
    )
    val trackSlotHeight = activeHeight + 6.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Generous invisible touch target — the visible bar is only ~7dp.
            .height(activeHeight + 22.dp)
            // One gesture loop for both drags and swipe-only relative scrubbing.
            .pointerInput(swipeOnly, variableScrubSpeed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var isGrabbed = false
                    var horizontalActive = !swipeOnly
                    var anchorX = down.position.x
                    var anchorValue = currentValue().coerceIn(0f, 1f)
                    var scrubSpeed = ScrubSpeed.FULL
                    var scrubFrac = (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    var prevX = down.position.x
                    var holdJob: Job? = null

                    if (swipeOnly) {
                        holdJob = gestureScope.launch {
                            delay(HOLD_GRAB_DELAY_MS)
                            if (pressed && !isGrabbed) {
                                isGrabbed = true
                                grabbed = true
                                grabChanged?.invoke(true)
                            }
                        }
                    } else {
                        sliding = true
                        if (variableScrubSpeed) speedChanged?.invoke(ScrubSpeed.FULL)
                        change(scrubFrac)
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) {
                                if (horizontalActive) pointer.consume()
                                break
                            }
                            if (!horizontalActive) {
                                val dx = pointer.position.x - down.position.x
                                val dy = pointer.position.y - down.position.y
                                val slop = viewConfiguration.touchSlop * 0.65f
                                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                                    // Yield vertical motion (e.g. sheet dismiss) without changing volume.
                                    break
                                }
                                if (abs(dx) >= slop && abs(dx) >= abs(dy)) {
                                    holdJob?.cancel()
                                    horizontalActive = true
                                    sliding = true
                                    if (!isGrabbed) {
                                        isGrabbed = true
                                        grabbed = true
                                        grabChanged?.invoke(true)
                                    }
                                    anchorX = down.position.x + kotlin.math.sign(dx) * slop
                                    anchorValue = currentValue().coerceIn(0f, 1f)
                                    val w = size.width.coerceAtLeast(1).toFloat()
                                    val raw = anchorValue + (pointer.position.x - anchorX) / w
                                    val clamped = raw.coerceIn(0f, 1f)
                                    if (raw <= 0f || raw >= 1f) {
                                        anchorValue = clamped
                                        anchorX = pointer.position.x
                                    }
                                    change(clamped)
                                    pointer.consume()
                                }
                            } else if (pointer.positionChanged()) {
                                if (swipeOnly) {
                                    val w = size.width.coerceAtLeast(1).toFloat()
                                    val raw = anchorValue + (pointer.position.x - anchorX) / w
                                    val clamped = raw.coerceIn(0f, 1f)
                                    if (raw <= 0f || raw >= 1f) {
                                        anchorValue = clamped
                                        anchorX = pointer.position.x
                                    }
                                    change(clamped)
                                } else if (variableScrubSpeed) {
                                    val verticalDp = abs(pointer.position.y - down.position.y) / density
                                    val nextSpeed = scrubSpeedForVerticalDp(verticalDp)
                                    if (nextSpeed != scrubSpeed) {
                                        scrubSpeed = nextSpeed
                                        speedChanged?.invoke(nextSpeed)
                                    }
                                    val w = size.width.coerceAtLeast(1).toFloat()
                                    if (nextSpeed == ScrubSpeed.FULL && verticalDp < 12f) {
                                        scrubFrac = (pointer.position.x / w).coerceIn(0f, 1f)
                                    } else {
                                        scrubFrac = (scrubFrac + ((pointer.position.x - prevX) / w) * nextSpeed.factor).coerceIn(0f, 1f)
                                    }
                                    prevX = pointer.position.x
                                    change(scrubFrac)
                                } else {
                                    change((pointer.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                                }
                                pointer.consume()
                            }
                        }
                    } finally {
                        holdJob?.cancel()
                        val wasGrabbed = isGrabbed
                        val wasSliding = horizontalActive
                        pressed = false
                        sliding = false
                        if (variableScrubSpeed) speedChanged?.invoke(null)
                        if (wasGrabbed) {
                            grabbed = false
                            grabChanged?.invoke(false)
                        }
                        if (wasSliding) {
                            finished?.invoke()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Constant layout height + graphicsLayer transform: thickness and bounce animate
        // purely in RenderNode / Canvas draw phases with zero relayout or recomposition.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(trackSlotHeight)
                .graphicsLayer {
                    val s = grabAnim.value
                    scaleX = s
                    scaleY = 1f + (s - 1f) * 2.4f
                },
        ) {
            val idlePx = idleHeight.toPx()
            val activePx = activeHeight.toPx()
            val barH = (idlePx + (activePx - idlePx) * expandState.value).coerceAtLeast(1f)
            val topY = (size.height - barH) / 2f
            val radius = CornerRadius(barH / 2f)
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, topY),
                size = Size(size.width, barH),
                cornerRadius = radius,
            )
            // Scaled by [fillFactorState]: retracted to nothing while the sheen
            // runs (two white signals on one bar would read as progress
            // fighting the wait) and slid back in when the wait ends.
            val fillFactor = fillFactorState.value
            val filled = size.width * value().coerceIn(0f, 1f) * fillFactor
            if (filled > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, topY),
                    size = Size(
                        filled.coerceAtLeast(barH * fillFactor).coerceAtMost(size.width),
                        barH,
                    ),
                    cornerRadius = radius,
                )
            }
        }
        // Composed only while loading. The infinite animation therefore costs no
        // frames during ordinary playback, and AnimatedVisibility lets it leave gracefully.
        AnimatedVisibility(
            visible = sheenVisible,
            // Grown out of the bar's own left end — where the progress fill
            // begins — instead of slid in from outside the bar. Same duration
            // and curve as the fill's retraction, so the swap reads as one morph.
            enter = fadeIn(tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing)) +
                scaleIn(
                    animationSpec = tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing),
                    initialScale = 0f,
                    transformOrigin = TransformOrigin(0f, 0.5f),
                ),
            exit = fadeOut(tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing)),
        ) {
            LoadingSheen(
                slotHeight = trackSlotHeight,
                idleHeight = idleHeight,
                activeHeight = activeHeight,
                expandFraction = { expandState.value },
                color = activeColor,
            )
        }
    }
}

private const val HOLD_GRAB_DELAY_MS = 120L
private const val GRAB_SQUEEZE_SCALE = 0.985f
private const val GRAB_HELD_SCALE = 1.022f
private const val GRAB_POP_VELOCITY = 1.8f

private const val SHEEN_BAND_FRACTION = 0.34f

/** How long the fill takes to retract under the sheen, and to slide back. */
private const val MORPH_MS = 450

/** The least time the sheen stays up once shown. */
private const val LOADING_MIN_MS = 600L

@Composable
private fun LoadingSheen(
    slotHeight: Dp,
    idleHeight: Dp,
    activeHeight: Dp,
    expandFraction: () -> Float,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "loadingSheen")
    val phase = transition.animateFloat(
        initialValue = -SHEEN_BAND_FRACTION,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            // Reverses rather than restarting: a restart teleports the band
            // back to the far edge every second, which is a hitch the eye
            // catches each time. Ping-pong has no edge to fall off.
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingSheenPhase",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(slotHeight),
    ) {
        val idlePx = idleHeight.toPx()
        val activePx = activeHeight.toPx()
        val barH = (idlePx + (activePx - idlePx) * expandFraction()).coerceAtLeast(1f)
        val topY = (size.height - barH) / 2f
        val band = size.width * SHEEN_BAND_FRACTION
        val x = phase.value * size.width
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to color,
                    1f to Color.Transparent,
                ),
                startX = x,
                endX = x + band,
            ),
            topLeft = Offset(0f, topY),
            size = Size(size.width, barH),
            // The band is clipped to the same capsule the track is drawn
            // with: a plain rect bared square corners wherever the sweep
            // crossed the rounded ends.
            cornerRadius = CornerRadius(barH / 2f),
        )
    }
}
