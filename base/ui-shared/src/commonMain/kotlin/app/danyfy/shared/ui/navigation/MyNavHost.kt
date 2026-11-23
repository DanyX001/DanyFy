package app.danyfy.shared.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.savedstate.read
import app.danyfy.shared.ui.components.WindowSize
import app.danyfy.shared.ui.components.rememberReduceMotion
import app.danyfy.shared.ui.components.windowSize
import app.danyfy.shared.ui.screens.CollectionDetailScreen
import app.danyfy.shared.ui.screens.HomeScreen
import app.danyfy.shared.ui.screens.LibraryScreen
import app.danyfy.shared.ui.screens.SearchScreen
import app.danyfy.shared.ui.screens.SettingsScreen
import app.danyfy.shared.ui.theme.AppBackground

@Composable
fun MyNavHost(
    navHostController: NavHostController,
    startDestination: String = Routes.Home.route,
    modifier: Modifier = Modifier,
) {
    val reduceState = rememberUpdatedState(rememberReduceMotion())

    NavHost(
        navController = navHostController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { if (reduceState.value) EnterTransition.None else navEnter(initialState, targetState) },
        exitTransition = { if (reduceState.value) ExitTransition.None else navExit(initialState, targetState) },
        popEnterTransition = { if (reduceState.value) EnterTransition.None else navPopEnter(initialState, targetState) },
        popExitTransition = { if (reduceState.value) ExitTransition.None else navPopExit(initialState, targetState) },
    ) {
        page(Routes.Home.route) { HomeScreen(navHostController) }
        page(Routes.Search.route) { SearchScreen(navHostController) }
        page(Routes.Library.route) { LibraryScreen(navHostController) }
        page(Routes.Player.route) {
            LaunchedEffect(Unit) {
                navHostController.popBackStack(Routes.Player.route, inclusive = true)
                PlayerSheet.show()
            }
        }
        page(Routes.Queue.route) {
            CollectionDetailScreen(navHostController, "Queue", "Next in queue")
        }
        page(Routes.Liked.route) {
            CollectionDetailScreen(navHostController, "Liked Songs", "Playlist • 20 songs")
        }
        page(Routes.LocalFiles.route) {
            CollectionDetailScreen(navHostController, "Local Files", "Tracks stored on this device")
        }
        page(Routes.Downloads.route) {
            CollectionDetailScreen(navHostController, "Downloaded", "Offline downloads")
        }
        page(Routes.Recent.route) {
            CollectionDetailScreen(navHostController, "Recently played", "Recent sample tracks")
        }
        page(Routes.Recap.route) {
            CollectionDetailScreen(navHostController, "Listening Recap", "Preview sample activity")
        }
        page(Routes.WhatsNew.route) {
            CollectionDetailScreen(navHostController, "What's New", "The latest from artists you follow")
        }
        page(Routes.Friends.route) {
            CollectionDetailScreen(navHostController, "Friend Activity", "Preview mode — offline")
        }
        page(Routes.Settings.route) { SettingsScreen(navHostController) }
        page(Routes.Extensions.route) {
            CollectionDetailScreen(navHostController, "Extensions", "Offline preview — no extensions loaded")
        }
        page(Routes.Profile.route) {
            CollectionDetailScreen(navHostController, "Dany", "iOS Preview profile")
        }
        page(Routes.Equalizer.route) {
            CollectionDetailScreen(navHostController, "Equalizer", "Audio DSP settings (preview)")
        }
        page(Routes.NewReleases.route) {
            CollectionDetailScreen(navHostController, "New Releases", "Curated picks")
        }
        page(
            "${Routes.Category.route}/{genre}?title={title}",
            arguments = listOf(navArgument("title") { defaultValue = "" }),
        ) { entry ->
            val genre = entry.arguments?.read { getStringOrNull("genre") }.orEmpty()
            val title = entry.arguments?.read { getStringOrNull("title") }.orEmpty().ifBlank { genre }
            CollectionDetailScreen(navHostController, title, "Browse category", browseId = genre)
        }
        page(
            "${Routes.Playlist.route}/{pId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { entry ->
            val pId = entry.arguments?.read { getStringOrNull("pId") }.orEmpty()
            val name = entry.arguments?.read { getStringOrNull("name") }.orEmpty().ifBlank { "Playlist" }
            CollectionDetailScreen(navHostController, name, "Playlist", browseId = pId)
        }
        page(
            "${Routes.Album.route}/{uString}?artist={artist}&id={albumId}",
            arguments = listOf(
                navArgument("artist") { defaultValue = "" },
                navArgument("albumId") { defaultValue = "" },
            ),
        ) { entry ->
            val albumId = entry.arguments?.read { getStringOrNull("albumId") }.orEmpty()
            val name = entry.arguments?.read { getStringOrNull("uString") }.orEmpty().ifBlank { "Album" }
            val artist = entry.arguments?.read { getStringOrNull("artist") }.orEmpty()
            CollectionDetailScreen(navHostController, name, artist, browseId = albumId)
        }
        page(
            "${Routes.Artist.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { entry ->
            val artistId = entry.arguments?.read { getStringOrNull("artistId") }.orEmpty()
            val name = entry.arguments?.read { getStringOrNull("aString") }.orEmpty().ifBlank { "Artist" }
            CollectionDetailScreen(navHostController, name, "Artist", browseId = artistId)
        }
    }
}

private val tabRoutes = setOf(Routes.Home.route, Routes.Search.route, Routes.Library.route)
private val sheetRoutes = setOf(Routes.Player.route, Routes.Queue.route)

private fun isTabSwitch(from: NavBackStackEntry, to: NavBackStackEntry) =
    from.destination.route in tabRoutes && to.destination.route in tabRoutes
private fun NavBackStackEntry.isSheet() = destination.route in sheetRoutes

private const val PAGE_MS = 300
private const val TAB_MS = 150
private const val SHEET_IN_MS = 340
private const val SHEET_OUT_MS = 240
private const val SHIFT = 0.08f
private const val FADE_OUT_MS = 90
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun pageIn(fromRight: Boolean): EnterTransition =
    slideInHorizontally(tween(PAGE_MS, easing = Standard)) { (it * SHIFT).toInt() * if (fromRight) 1 else -1 } +
        fadeIn(tween(PAGE_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = Decelerate))

private fun pageOut(toLeft: Boolean): ExitTransition =
    slideOutHorizontally(tween(PAGE_MS, easing = Standard)) { (it * SHIFT).toInt() * if (toLeft) -1 else 1 } +
        fadeOut(tween(FADE_OUT_MS, easing = Accelerate))

private fun navEnter(from: NavBackStackEntry, to: NavBackStackEntry): EnterTransition = when {
    isTabSwitch(from, to) -> fadeIn(tween(TAB_MS, easing = Decelerate))
    to.isSheet() -> slideInVertically(tween(SHEET_IN_MS, easing = Decelerate)) { it }
    else -> pageIn(fromRight = true)
}

private fun navExit(from: NavBackStackEntry, to: NavBackStackEntry): ExitTransition = when {
    isTabSwitch(from, to) -> ExitTransition.None
    to.isSheet() -> fadeOut(tween(durationMillis = 1, delayMillis = SHEET_IN_MS))
    else -> pageOut(toLeft = true)
}

private fun navPopEnter(from: NavBackStackEntry, to: NavBackStackEntry): EnterTransition = when {
    isTabSwitch(from, to) -> fadeIn(tween(TAB_MS, easing = Decelerate))
    from.isSheet() -> EnterTransition.None
    else -> pageIn(fromRight = false)
}

private fun navPopExit(from: NavBackStackEntry, to: NavBackStackEntry): ExitTransition = when {
    isTabSwitch(from, to) -> ExitTransition.None
    from.isSheet() -> slideOutVertically(tween(SHEET_OUT_MS, easing = Accelerate)) { it }
    else -> pageOut(toLeft = false)
}

private val FullWidthRoutes = setOf(
    Routes.Home.route, Routes.Search.route, Routes.Library.route, Routes.Player.route, Routes.Queue.route,
    Routes.Category.route, Routes.NewReleases.route,
)

private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    val scope = this
    val settled = remember {
        derivedStateOf { scope.transition.currentState == scope.transition.targetState }
    }
    CompositionLocalProvider(LocalPageSettled provides settled) {
        val capped = windowSize().large &&
            entry.destination.route?.substringBefore('/')?.substringBefore('?') !in FullWidthRoutes
        Box(Modifier.fillMaxSize().background(AppBackground)) {
            Box(
                Modifier.fillMaxHeight().then(
                    if (capped) Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = WindowSize.MaxPageWidth)
                    else Modifier.fillMaxWidth()
                )
            ) { scope.content(entry) }
        }
    }
}
