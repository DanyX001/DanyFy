package app.danyfy.shared.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.danyfy.shared.ui.components.MiniPlayer
import app.danyfy.shared.ui.components.WindowSize
import app.danyfy.shared.ui.components.windowSize
import app.danyfy.shared.ui.theme.AppMuted
import app.danyfy.shared.ui.theme.AppShade
import app.danyfy.shared.ui.theme.AppText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

var solidNavBarEnabled = false

object TabReselect {
    private val _home = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _library = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _search = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val home: SharedFlow<Unit> = _home
    val library: SharedFlow<Unit> = _library
    val search: SharedFlow<Unit> = _search

    fun emit(route: String) {
        when (route) {
            Routes.Home.route -> _home.tryEmit(Unit)
            Routes.Library.route -> _library.tryEmit(Unit)
            Routes.Search.route -> _search.tryEmit(Unit)
        }
    }
}

private val TabItems: List<Routes> = listOf(Routes.Home, Routes.Search, Routes.Library)

@Composable
fun MainBottomNavigation(navController: NavHostController, showMiniPlayer: Boolean = true) {
    val shownRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val fullScreen = shownRoute == Routes.Player.route || shownRoute == Routes.Queue.route
    val hideAll = shownRoute == Routes.Settings.route
    AnimatedVisibility(
        visible = !fullScreen && !hideAll,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (solidNavBarEnabled) Modifier.background(AppShade)
                    else Modifier.background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.3f to AppShade.copy(alpha = 0.7f),
                            0.6f to AppShade.copy(alpha = 0.92f),
                            1f to AppShade,
                        )
                    )
                )
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                AnimatedVisibility(
                    visible = showMiniPlayer,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(if (windowSize().navigationRail) Modifier.widthIn(max = WindowSize.MaxMiniPlayerWidth) else Modifier) {
                            MiniPlayer()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val tabs = rememberTabNavigation(navController)
                if (!windowSize().navigationRail) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NavBarHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabItems.forEach { item ->
                            NavBarTab(
                                iconRes = item.icon!!,
                                label = item.labelRes?.let { stringResource(it) } ?: item.label,
                                selected = tabs.selected == item.route,
                                onClick = { tabs.open(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private class TabNavigation(
    private val navController: NavHostController,
    private val state: MutableState<String>,
    private val currentRoute: String?,
) {
    val selected: String get() = state.value

    fun open(item: Routes) {
        if (item.route == state.value) {
            if (currentRoute == item.route) {
                TabReselect.emit(item.route)
            } else if (!navController.popBackStack(item.route, inclusive = false)) {
                navController.navigate(item.route) { launchSingleTop = true }
            }
        } else {
            state.value = item.route
            navController.navigate(item.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = item.route != Routes.Home.route
            }
        }
    }
}

@Composable
private fun rememberTabNavigation(navController: NavHostController): TabNavigation {
    val navStack by navController.currentBackStackEntryAsState()
    val currentRoute = navStack?.destination?.route
    val selectedTab = rememberSaveable { mutableStateOf(Routes.Home.route) }
    if (currentRoute in TabItems.map { it.route }) selectedTab.value = currentRoute!!
    return TabNavigation(navController, selectedTab, currentRoute)
}

@Composable
fun MainNavigationRail(navController: NavHostController) {
    val shownRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    if (shownRoute == Routes.Player.route || shownRoute == Routes.Queue.route) return
    val tabs = rememberTabNavigation(navController)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(88.dp)
            .background(AppShade)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TabItems.forEach { item ->
            Row(Modifier.fillMaxWidth().height(72.dp)) {
                NavBarTab(
                    iconRes = item.icon!!,
                    label = item.labelRes?.let { stringResource(it) } ?: item.label,
                    selected = tabs.selected == item.route,
                    onClick = { tabs.open(item) },
                )
            }
        }
    }
}

private val NavBarHeight = 58.dp
private val NavIconSize = 28.dp

@Composable
private fun RowScope.NavBarTab(
    iconRes: DrawableResource,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) AppText else AppMuted
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NavIconSize),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
            )
        }
    }
}
