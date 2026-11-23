package app.danyfy.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import app.danyfy.shared.ui.components.ProvideWindowSize
import app.danyfy.shared.ui.components.windowSize
import app.danyfy.shared.ui.navigation.MainBottomNavigation
import app.danyfy.shared.ui.navigation.MainNavigationRail
import app.danyfy.shared.ui.navigation.MyNavHost
import app.danyfy.shared.ui.navigation.PlayerOverlay
import app.danyfy.shared.ui.navigation.Routes
import app.danyfy.shared.ui.screens.PlayerScreen
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.SpotuiTheme

/**
 * Root shared UI of DanyFy in Classic look (exact visual match of Android's AppShell + SpotuiTheme).
 * Optional parameters allow screenshot tests to open a given tab, route, or full-screen player directly.
 */
@Composable
fun DanyFyApp(
    startDestination: String = Routes.Home.route,
    playerOpenInitially: Boolean = false,
    showMiniPlayer: Boolean = true,
) {
    app.danyfy.shared.ui.navigation.PlayerSheet.open.value = playerOpenInitially
    SpotuiTheme {
        ProvideWindowSize {
            val navController = rememberNavController()
            val rail = windowSize().navigationRail
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (rail) MainNavigationRail(navController)
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MyNavHost(navHostController = navController, startDestination = startDestination)
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            MainBottomNavigation(navController, showMiniPlayer = showMiniPlayer)
                        }
                    }
                }
                PlayerOverlay(navController) { nav ->
                    PlayerScreen(nav)
                }
            }
        }
    }
}
