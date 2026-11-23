package app.danyfy.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PullToDismissState internal constructor(
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val flingPx: Float,
    private val dismiss: () -> Unit,
) {
    var enabled = true
    private val pull = Animatable(0f)
    private var raw = 0f
    private var dismissed = false

    val offset: Float get() = pull.value

    private fun follow() {
        scope.launch { pull.snapTo(raw * RESISTANCE) }
    }

    fun drag(delta: Float): Float {
        val used = when {
            delta < 0f -> maxOf(delta, -raw)
            enabled && !dismissed -> delta
            else -> 0f
        }
        if (used == 0f) return 0f
        raw += used
        follow()
        return used
    }

    suspend fun reset() {
        raw = 0f
        dismissed = false
        pull.snapTo(0f)
    }

    fun release(velocity: Float) {
        scope.launch { settle(velocity) }
    }

    private suspend fun settle(velocity: Float): Boolean {
        if (raw <= 0f) return false
        if (raw > thresholdPx || velocity > flingPx) {
            dismissed = true
            dismiss()
        } else {
            raw = 0f
            pull.animateTo(0f)
        }
        return true
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (raw <= 0f || available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (settle(available.y)) available else Velocity.Zero
    }

    private companion object {
        const val RESISTANCE = 0.6f
    }
}

fun Modifier.pullToDismiss(state: PullToDismissState): Modifier = pointerInput(state) {
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
        state.drag(pulled)
        val finished = verticalDrag(drag.id) { change ->
            velocity.addPointerInputChange(change)
            state.drag(change.positionChange().y)
            change.consume()
        }
        state.release(if (finished) velocity.calculateVelocity().y else 0f)
    }
}

@Composable
fun rememberPullToDismiss(open: Boolean = true, onDismiss: () -> Unit): PullToDismissState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latest = rememberUpdatedState(onDismiss)
    val state = remember(density) {
        with(density) { PullToDismissState(scope, 120.dp.toPx(), 1200.dp.toPx()) { latest.value() } }
    }
    LaunchedEffect(state, open) { if (open) state.reset() }
    return state
}
