package app.danyfy.shared.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavController
import app.danyfy.shared.ui.components.rememberReduceMotion
import app.danyfy.shared.ui.theme.appSansFamily
import app.danyfy.shared.ui.theme.appTypography
import kotlinx.coroutines.flow.drop

object PlayerSheet {
    val open = mutableStateOf(false)

    fun show() { open.value = true }
    fun close() { open.value = false }

    var miniTop by mutableFloatStateOf(0f)
}

val LocalPlayerReveal = staticCompositionLocalOf<() -> Float> { { 1f } }
val LocalPageSettled = staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> { mutableStateOf(true) }

internal val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val OPEN_MS = 380
private const val CLOSE_MS = 300
private const val SHEET_FADE = 0.3f
private val CloseEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
fun PlayerOverlay(
    navController: NavController,
    playerContent: @Composable (NavController) -> Unit,
) {
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.drop(1).collect { PlayerSheet.close() }
    }
    val open = PlayerSheet.open.value
    val progress = remember { Animatable(if (open) 1f else 0f) }
    var shown by remember { mutableStateOf(open) }
    val settled = remember { mutableStateOf(open) }
    val reduce = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) {
            focusManager.clearFocus(force = true)
            shown = true
            settled.value = false
            if (progress.value == 0f) { withFrameNanos { }; withFrameNanos { } }
            if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(OPEN_MS, easing = Decelerate))
            settled.value = true
        } else if (shown) {
            settled.value = false
            if (reduce) progress.snapTo(0f) else progress.animateTo(0f, tween(CLOSE_MS, easing = CloseEasing))
            shown = false
        }
    }
    if (!shown) return
    var heightPx by remember { mutableIntStateOf(0) }
    val reveal = remember { { ((progress.value - 0.25f) / 0.6f).coerceIn(0f, 1f) } }
    val typo = appTypography(appSansFamily())
    Box(
        Modifier.fillMaxSize().onSizeChanged { heightPx = it.height }.graphicsLayer {
            val p = progress.value
            val from = PlayerSheet.miniTop.takeIf { it > 0f } ?: heightPx.toFloat()
            translationY = (1f - p) * from
            alpha = (p / SHEET_FADE).coerceIn(0f, 1f)
        },
    ) {
        CompositionLocalProvider(LocalPageSettled provides settled, LocalPlayerReveal provides reveal) {
            ProvideTextStyle(typo.bodyLarge) {
                playerContent(navController)
            }
        }
    }
}
