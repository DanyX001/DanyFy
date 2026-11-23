package app.danyfy.shared.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.concurrent.thread

actual fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000L

actual fun decodeImageWithColor(bytes: ByteArray): Pair<ImageBitmap, Color>? {
    return try {
        val skiaImage = Image.makeFromEncoded(bytes)
        val composeBitmap = skiaImage.toComposeImageBitmap()

        // Extract vibrant tint color by sampling pixels
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
                    // Skip very bright whites and pure blacks for a richer background tint
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

class DesktopHttp : PlatformHttp {
    override fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        callback: PlatformHttpCallback,
    ) {
        thread(isDaemon = true) {
            try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                callback.onResponse(code, body, null)
            } catch (t: Throwable) {
                callback.onResponse(0, null, t.message ?: t.toString())
            }
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override fun getBytes(
        url: String,
        headers: Map<String, String>,
        callback: PlatformHttpCallback,
    ) {
        thread(isDaemon = true) {
            try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val b64 = if (code in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    kotlin.io.encoding.Base64.encode(bytes)
                } else null
                callback.onResponse(code, b64, null)
            } catch (t: Throwable) {
                callback.onResponse(0, null, t.message ?: t.toString())
            }
        }
    }
}

actual fun ensureDefaultPlatformBridge() {
    if (PlatformRegistry.http is NoOpPlatformHttp) {
        PlatformRegistry.http = DesktopHttp()
    }
}
