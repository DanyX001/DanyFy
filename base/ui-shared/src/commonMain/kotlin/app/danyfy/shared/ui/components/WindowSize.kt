package app.danyfy.shared.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }
enum class HeightClass { COMPACT, MEDIUM, EXPACT, MEDIUM_OR_MORE }

@Immutable
data class WindowSize(val widthDp: Int, val heightDp: Int) {
    val width: WidthClass = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
    val height: Int = when {
        heightDp < 480 -> 0
        heightDp < 900 -> 1
        else -> 2
    }

    val large: Boolean get() = width != WidthClass.COMPACT && height != 0
    val expanded: Boolean get() = large && width == WidthClass.EXPANDED
    val twoPane: Boolean get() = large && (widthDp > heightDp || width == WidthClass.EXPANDED)
    val shortAndWide: Boolean get() = height == 0 && width != WidthClass.COMPACT
    val portraitPhone: Boolean get() = width == WidthClass.COMPACT && heightDp >= widthDp

    fun playerSideBySide(boxWidth: Dp, boxHeight: Dp, minWidth: Dp = 0.dp): Boolean =
        !portraitPhone && (twoPane || (boxWidth > minWidth && boxWidth > boxHeight))

    val navigationRail: Boolean get() = large || shortAndWide
    val chromeInOneRow: Boolean get() = expanded || shortAndWide

    fun columns(phone: Int, cell: Dp = 180.dp): Int = columnsIn(widthDp.dp, phone, cell)

    companion object {
        val MaxPageWidth = 1040.dp
        val MaxMiniPlayerWidth = 720.dp

        fun columnsIn(width: Dp, phone: Int, cell: Dp = 180.dp): Int =
            (width.value / cell.value).toInt().coerceIn(phone, phone * 4)
    }
}

fun Modifier.cappedPageWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = WindowSize.MaxPageWidth)
    .fillMaxWidth()

private val LocalWindowSize = staticCompositionLocalOf { WindowSize(411, 914) }

@Composable
fun windowSize(): WindowSize = LocalWindowSize.current

@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value.toInt().coerceAtLeast(320)
        val h = maxHeight.value.toInt().coerceAtLeast(480)
        val ws = remember(w, h) { WindowSize(w, h) }
        CompositionLocalProvider(LocalWindowSize provides ws, content = content)
    }
}

fun Modifier.secondaryClick(action: (() -> Unit)?): Modifier = if (action == null) this else composed {
    val latest by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}
