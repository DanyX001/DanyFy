package app.danyfy.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import app.danyfy.shared.data.LibraryStore
import app.danyfy.shared.data.Look
import app.danyfy.shared.data.PlayerController
import app.danyfy.shared.data.PreviewPlayerState
import app.danyfy.shared.data.YtmApi
import app.danyfy.shared.platform.ensureDefaultPlatformBridge
import app.danyfy.shared.ui.components.ProvideWindowSize
import app.danyfy.shared.ui.components.rememberCoverTint
import app.danyfy.shared.ui.components.windowSize
import app.danyfy.shared.ui.navigation.MainBottomNavigation
import app.danyfy.shared.ui.navigation.MainNavigationRail
import app.danyfy.shared.ui.navigation.MyNavHost
import app.danyfy.shared.ui.navigation.PlayerOverlay
import app.danyfy.shared.ui.navigation.Routes
import app.danyfy.shared.ui.screens.PlayerScreen
import app.danyfy.shared.ui.theme.AppBackground
import app.danyfy.shared.ui.theme.SpotuiTheme

val LocalPlayerController = staticCompositionLocalOf<PlayerController> { error("No PlayerController") }
val LocalLibraryStore = staticCompositionLocalOf<LibraryStore> { error("No LibraryStore") }
val LocalYtmApi = staticCompositionLocalOf<YtmApi> { error("No YtmApi") }
val LocalLook = staticCompositionLocalOf<Look> { Look.CLASSIC }
val LocalOfflinePreview = staticCompositionLocalOf<Boolean> { false }

/**
 * Root shared UI of DanyFy supporting both Classic and Immersive looks.
 * Optional parameters allow screenshot tests to open a given tab, route, or full-screen player directly.
 */
@Composable
fun DanyFyApp(
    startDestination: String = Routes.Home.route,
    playerOpenInitially: Boolean = false,
    showMiniPlayer: Boolean = true,
    offlinePreview: Boolean = false,
) {
    ensureDefaultPlatformBridge()

    val ytmApi = remember { YtmApi() }
    val libraryStore = remember { LibraryStore() }
    val playerController = remember { PlayerController(ytmApi, libraryStore) }

    remember {
        if (!offlinePreview) {
            PreviewPlayerState.activeController = playerController
            PreviewPlayerState.activeLibraryStore = libraryStore
        }
    }

    app.danyfy.shared.ui.navigation.PlayerSheet.open.value = playerOpenInitially
    val currentLook = if (offlinePreview) Look.CLASSIC else libraryStore.look

    CompositionLocalProvider(
        LocalPlayerController provides playerController,
        LocalLibraryStore provides libraryStore,
        LocalYtmApi provides ytmApi,
        LocalLook provides currentLook,
        LocalOfflinePreview provides offlinePreview,
    ) {
        SpotuiTheme {
            ProvideWindowSize {
                val navController = rememberNavController()
                val rail = windowSize().navigationRail

                val playingTrack = playerController.currentTrack
                val bgTint = if (currentLook == Look.IMMERSIVE) {
                    rememberCoverTint(
                        artworkUrl = playingTrack?.artworkUrl,
                        id = playingTrack?.id ?: "immersive_bg",
                        title = playingTrack?.title ?: "Ambient",
                        defaultColor = Color(0xFF1E1035),
                    )
                } else Color.Transparent

                val bgModifier = if (currentLook == Look.IMMERSIVE) {
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                bgTint.copy(alpha = 0.35f),
                                Color(0xFF0C0C10),
                                Color.Black,
                            )
                        )
                    )
                } else {
                    Modifier.fillMaxSize().background(AppBackground)
                }

                Box(modifier = bgModifier) {
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
}
