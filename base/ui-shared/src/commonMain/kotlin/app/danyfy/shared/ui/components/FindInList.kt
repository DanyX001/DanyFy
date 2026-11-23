package app.danyfy.shared.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.immersive_shell_search_clear
import org.jetbrains.compose.resources.stringResource

@Composable
fun SearchClearButton(
    focusRequester: FocusRequester,
    tint: Color,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) {
                onClear()
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            },
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(Res.string.immersive_shell_search_clear),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
