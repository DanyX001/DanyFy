package app.danyfy.shared.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.titleMarquee(enabled: Boolean = true, iterations: Int = 2): Modifier =
    if (!enabled || rememberReduceMotion()) this
    else this.basicMarquee(iterations = iterations, repeatDelayMillis = 2_500, initialDelayMillis = 1_800, velocity = 28.dp)
