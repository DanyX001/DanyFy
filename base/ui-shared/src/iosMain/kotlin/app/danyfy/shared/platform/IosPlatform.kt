package app.danyfy.shared.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

actual fun decodeImageWithColor(bytes: ByteArray): Pair<ImageBitmap, Color>? {
    return try {
        val skiaImage = Image.makeFromEncoded(bytes)
        val composeBitmap = skiaImage.toComposeImageBitmap()

        val pixmap = skiaImage.peekPixels()
        val sampledColor = if (pixmap != null && skiaImage.width > 0 && skiaImage.height > 0) {
            val stepX = maxOf(1, skiaImage.width / 8)
            val stepY = maxOf(1, skiaImage.height / 8)
            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            var count = 0L
            for (y in 0 until skiaImage.height step stepY) {
                for (x in 0 until skiaImage.width step stepX) {
                    val argb = pixmap.getColor(x, y)
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    val maxVal = maxOf(r, maxOf(g, b))
                    val minVal = minOf(r, minOf(g, b))
                    if (maxVal - minVal > 20 && maxVal in 40..220) {
                        totalR += r
                        totalG += g
                        totalB += b
                        count++
                    }
                }
            }
            if (count > 0) {
                Color(
                    red = (totalR / count).toInt(),
                    green = (totalG / count).toInt(),
                    blue = (totalB / count).toInt(),
                )
            } else {
                Color(0xFF2A2A38)
            }
        } else {
            Color(0xFF2A2A38)
        }

        Pair(composeBitmap, sampledColor)
    } catch (_: Throwable) {
        null
    }
}

actual fun ensureDefaultPlatformBridge() {}
