package com.music.spotui.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.music.spotui.ui.immersive.sheets.ImmersiveGlassSheet
import com.music.spotui.ui.immersive.sheets.LocalImmersiveSheetTint
import com.music.spotui.ui.navigation.isImmersive
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.tone

/** Sheet highlight accent: cover-tinted Immersive accent on glass sheets, Classic [AppAccent] otherwise. */
@Composable
internal fun lookSheetAccent(): Color =
    if (isImmersive()) LocalImmersiveSheetTint.current.accent else AppAccent

/** Raised card background inside shared sheets: translucent glass card in Immersive, raised grey in Classic. */
@Composable
internal fun lookSheetSurface(): Color =
    if (isImmersive()) Color.White.copy(alpha = 0.09f) else tone(0xFF333333)

/** Subtle pill/chip fill inside shared sheets. */
@Composable
internal fun lookSheetPillSurface(): Color =
    if (isImmersive()) Color.White.copy(alpha = 0.14f) else Color(0xFF4A4A4A)

/**
 * The bottom sheet of the screens shared by both looks: Material's [ModalBottomSheet] on
 * [containerColor] in Classic (exactly as before), a glass sheet in Immersive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isImmersive()) ImmersiveGlassSheet(onDismissRequest, sheetState, content)
    else ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = containerColor, content = content)
}
