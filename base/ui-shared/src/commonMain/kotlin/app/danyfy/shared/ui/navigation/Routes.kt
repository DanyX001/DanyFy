package app.danyfy.shared.ui.navigation

import app.danyfy.shared.resources.Res
import app.danyfy.shared.resources.ic_friends_big
import app.danyfy.shared.resources.ic_home_filled
import app.danyfy.shared.resources.ic_library_big
import app.danyfy.shared.resources.ic_search_big
import app.danyfy.shared.resources.navigationbar_musicappitems_home_title
import app.danyfy.shared.resources.navigationbar_musicappitems_search_title
import app.danyfy.shared.resources.navigationbar_musicappitems_yourlibrary_title
import app.danyfy.shared.resources.friends_tab
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

sealed class Routes(
    val icon: DrawableResource? = null,
    val label: String,
    val route: String,
    val labelRes: StringResource? = null,
) {
    data object Home : Routes(icon = Res.drawable.ic_home_filled, label = "Home", route = "home", labelRes = Res.string.navigationbar_musicappitems_home_title)
    data object Search : Routes(icon = Res.drawable.ic_search_big, label = "Search", route = "search", labelRes = Res.string.navigationbar_musicappitems_search_title)
    data object Library : Routes(icon = Res.drawable.ic_library_big, label = "Your Library", route = "library", labelRes = Res.string.navigationbar_musicappitems_yourlibrary_title)
    data object Album : Routes(null, "Album", "album")
    data object Player : Routes(null, "Player", "player")
    data object Artist : Routes(null, "Artist", "artist")
    data object Playlist : Routes(null, "Playlist", "playlist")
    data object Queue : Routes(null, "Queue", "queue")
    data object Liked : Routes(null, "Liked", "liked")
    data object LocalFiles : Routes(null, "LocalFiles", "localfiles")
    data object Downloads : Routes(null, "Downloads", "downloads")
    data object Category : Routes(null, "Category", "category")
    data object NewReleases : Routes(null, "NewReleases", "newreleases")
    data object Recent : Routes(null, "Recent", "recent")
    data object Recap : Routes(null, "Recap", "recap")
    data object WhatsNew : Routes(null, "WhatsNew", "whatsnew")
    data object Friends : Routes(icon = Res.drawable.ic_friends_big, label = "Friends", route = "friends", labelRes = Res.string.friends_tab)
    data object Settings : Routes(null, "Settings", "settings")
    data object Extensions : Routes(null, "Extensions", "extensions")
    data object Profile : Routes(null, "Profile", "profile")
    data object Equalizer : Routes(null, "Equalizer", "equalizer")
    data object HiddenSongs : Routes(null, "HiddenSongs", "hiddensongs")
}

private fun encodeSeg(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val safe = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
            (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (safe) append(c.toChar())
        else {
            append('%')
            val hex = "0123456789ABCDEF"
            append(hex[c ushr 4])
            append(hex[c and 0x0F])
        }
    }
}

fun categoryRoute(genre: String, title: String): String =
    "${Routes.Category.route}/${encodeSeg(genre)}?title=${encodeSeg(title)}"

fun playlistRoute(id: String, name: String = ""): String =
    "${Routes.Playlist.route}/${encodeSeg(id)}?name=${encodeSeg(name)}"

fun artistRoute(name: String, id: String = ""): String {
    val base = "${Routes.Artist.route}/${encodeSeg(name)}"
    return if (id.isBlank()) base else "$base?id=${encodeSeg(id)}"
}

fun albumRoute(name: String, artist: String = "", id: String = ""): String =
    "${Routes.Album.route}/${encodeSeg(name.ifBlank { "album" })}?artist=${encodeSeg(artist)}&id=${encodeSeg(id)}"

fun detailPlaceholderRoute(title: String, subtitle: String = ""): String =
    "detail/${encodeSeg(title)}?sub=${encodeSeg(subtitle)}"
