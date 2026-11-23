/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.immersive.lyrics

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.look.rememberReduceMotion
import kotlin.math.sin

private data class LyricParticle(
    val xFrac: Float,
    val yFrac: Float,
    val radiusDp: Float,
    val speed: Float,
    val swayAmp: Float,
    val swayPhase: Float,
    val alpha: Float,
)

private val PARTICLES: List<LyricParticle> = List(20) { i ->
    val f1 = ((i * 0.61803398875f) % 1f)
    val f2 = ((i * 0.38196601125f + 0.17f) % 1f)
    val f3 = ((i * 0.75487766625f) % 1f)
    LyricParticle(
        xFrac = 0.06f + f1 * 0.88f,
        yFrac = f2,
        radiusDp = 6f + f3 * 22f,
        speed = 0.012f + (i % 5) * 0.004f,
        swayAmp = 0.015f + (i % 3) * 0.01f,
        swayPhase = i * 0.9f,
        alpha = 0.045f + f3 * 0.075f,
    )
}

/**
 * Subtle drifting bokeh particles behind Immersive lyrics. Advances on the frame clock only
 * while playback is running and Reduce Motion is off, and draws inside [drawBehind] with zero
 * recompositions.
 */
@Composable
internal fun LyricsAmbientParticles(
    tint: Color,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduce = rememberReduceMotion()
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, reduce) {
        if (!playing || reduce) return@LaunchedEffect
        var lastMs = 0L
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                if (lastMs != 0L) {
                    val dtSec = ((now - lastMs).coerceIn(0L, 64L)) / 1000f
                    phase.floatValue += dtSec
                }
                lastMs = now
            }
        }
    }
    Box(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            val t = phase.floatValue
            for (pt in PARTICLES) {
                // Slow upward drift wrapping smoothly past the top and bottom bounds.
                val rawY = (pt.yFrac - t * pt.speed) % 1f
                val yNorm = if (rawY < 0f) rawY + 1f else rawY
                val xNorm = (pt.xFrac + sin(t * 0.45f + pt.swayPhase) * pt.swayAmp).coerceIn(0f, 1f)
                val cx = xNorm * w
                val cy = yNorm * h
                val r = pt.radiusDp.dp.toPx()
                val breathe = 0.85f + 0.15f * sin(t * 0.9f + pt.swayPhase)
                val center = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            tint.copy(alpha = pt.alpha * breathe),
                            tint.copy(alpha = 0f),
                        ),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
            }
        },
    )
}
