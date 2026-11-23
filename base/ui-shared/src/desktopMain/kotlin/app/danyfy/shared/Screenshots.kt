package app.danyfy.shared

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.danyfy.shared.ui.DanyFyApp
import app.danyfy.shared.ui.navigation.Routes
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private fun renderToFile(
    outDir: File,
    fileName: String,
    startRoute: String,
    playerOpen: Boolean = false,
    miniPlayer: Boolean = true,
) {
    val width = 1080
    val height = 2400
    val density = Density(2.625f)
    val scene = ImageComposeScene(width = width, height = height, density = density) {
        DanyFyApp(
            startDestination = startRoute,
            playerOpenInitially = playerOpen,
            showMiniPlayer = miniPlayer,
        )
    }
    try {
        var t = 0L
        repeat(8) {
            Thread.sleep(60)
            t += 16_000_000L
            scene.render(t)
        }
        val image = scene.render(t + 16_000_000L)
        val data = image.encodeToData(EncodedImageFormat.PNG)
            ?: error("Failed to encode $fileName to PNG")
        File(outDir, fileName).writeBytes(data.bytes)
    } finally {
        scene.close()
    }
}

fun main() {
    val outDir = File("build/screenshots").apply { mkdirs() }
    renderToFile(outDir, "home.png", Routes.Home.route, miniPlayer = false)
    renderToFile(outDir, "home_mini_player.png", Routes.Home.route, miniPlayer = true)
    renderToFile(outDir, "search.png", Routes.Search.route, miniPlayer = true)
    renderToFile(outDir, "library.png", Routes.Library.route, miniPlayer = true)
    renderToFile(outDir, "player.png", Routes.Home.route, playerOpen = true)
    renderToFile(outDir, "settings.png", Routes.Settings.route, miniPlayer = false)
    println("Rendered screenshots to ${outDir.absolutePath}")
}
