/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.immersive.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntSize

/** Coordinates recorded from the floating mini player so the full player cover docks from/to it. */
internal object PlayerDockBounds {
    var miniArtInWindow by mutableStateOf<Rect?>(null)
}

@Immutable
internal data class DockArtTransform(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
)

/**
 * Computes the GPU transform for the player's cover art at [dockFraction] (`0f` = full rest,
 * `1f` = shrunk & aligned directly over [miniArtInWindow]). Pure for unit testing.
 */
internal fun computeDockArtTransform(
    restLeftInSheet: Float,
    restTopInSheet: Float,
    restWidth: Float,
    restHeight: Float,
    miniArtInWindow: Rect?,
    sheetTranslationY: Float,
    dockFraction: Float,
): DockArtTransform {
    val f = dockFraction.coerceIn(0f, 1f)
    if (f <= 0f || miniArtInWindow == null || miniArtInWindow.isEmpty || restWidth <= 0f || restHeight <= 0f) {
        return DockArtTransform()
    }
    val restCenterX = restLeftInSheet + restWidth * 0.5f
    val restCenterY = restTopInSheet + restHeight * 0.5f
    val targetScale = (miniArtInWindow.width / restWidth).coerceIn(0.05f, 1.5f)
    val scale = 1f + (targetScale - 1f) * f
    val tx = (miniArtInWindow.center.x - restCenterX) * f
    val ty = ((miniArtInWindow.center.y - sheetTranslationY) - restCenterY) * f
    return DockArtTransform(scale = scale, translationX = tx, translationY = ty)
}

@Stable
internal class PlayerDockController {
    var sheetRootCoords by mutableStateOf<LayoutCoordinates?>(null)
    var sheetTranslationY by mutableFloatStateOf(0f)
    var dockFraction by mutableFloatStateOf(0f)
    var chromeAlpha by mutableFloatStateOf(1f)
}

internal val LocalPlayerDock = staticCompositionLocalOf<PlayerDockController?> { null }

/**
 * Helper for artwork slots: computes the live [DockArtTransform] relative to the overlay's
 * non-translating content container, using previously recorded slot bounds inside the sheet.
 */
internal fun dockTransformForSlot(
    dock: PlayerDockController?,
    slotInSheet: Rect?,
): DockArtTransform {
    if (dock == null || slotInSheet == null || slotInSheet.isEmpty) return DockArtTransform()
    return computeDockArtTransform(
        restLeftInSheet = slotInSheet.left,
        restTopInSheet = slotInSheet.top,
        restWidth = slotInSheet.width,
        restHeight = slotInSheet.height,
        miniArtInWindow = PlayerDockBounds.miniArtInWindow,
        sheetTranslationY = dock.sheetTranslationY,
        dockFraction = dock.dockFraction,
    )
}

/** Measures an un-transformed cover slot relative to [PlayerDockController.sheetRootCoords]. */
internal fun measureSlotInSheet(
    dock: PlayerDockController?,
    slotCoords: LayoutCoordinates,
): Rect? {
    val host = dock?.sheetRootCoords ?: return null
    if (!host.isAttached || !slotCoords.isAttached) return null
    val topLeft = host.localPositionOf(slotCoords, androidx.compose.ui.geometry.Offset.Zero)
    val sz: IntSize = slotCoords.size
    return Rect(topLeft.x, topLeft.y, topLeft.x + sz.width, topLeft.y + sz.height)
}

/** Keeps window mini-art bounds cleared cleanly when the mini player is disposed. */
internal fun Rect.validWindowBounds(): Rect? = takeIf { !isEmpty && width > 0f && height > 0f }

internal fun LayoutCoordinates.windowArtRect(): Rect? = boundsInWindow().validWindowBounds()
