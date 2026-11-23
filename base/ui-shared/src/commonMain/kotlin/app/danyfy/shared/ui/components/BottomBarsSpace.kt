package app.danyfy.shared.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun bottomBarsSpace(): Dp = 160.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
