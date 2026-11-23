package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import app.myspoti.engine.R
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share as image": a 1080x1920 story card (blurred cover background, large cover, title,
 * artist, app wordmark) or selected-lyric quote card written to cache/shared/ and handed out
 * through the FileProvider, so Instagram / WhatsApp stories and the like can pick it up.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1920

    /** Renders and shares the track story card; does nothing if the image cannot be produced. */
    suspend fun share(context: Context, title: String, artist: String, coverUri: String, chooserTitle: String) {
        val wordmark = runCatching { context.getString(R.string.app_name) }.getOrDefault("DanyFy")
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val cover = loadCover(context, coverUri)
                val card = render(title, artist, cover, wordmark)
                writeSharedPng(context, card, "danyfy_card.png")
            }.getOrNull()
        } ?: return
        dispatchImage(context, file, chooserTitle)
    }

    /** Renders and shares a lyric-quote story card for the selected [lines]. */
    suspend fun shareLyricsCard(
        context: Context,
        title: String,
        artist: String,
        coverUri: String,
        lines: List<String>,
        chooserTitle: String,
    ) {
        val cleanLines = lines.map { it.trim() }.filter { it.isNotEmpty() && it != "♪" }
        if (cleanLines.isEmpty()) return
        val wordmark = runCatching { context.getString(R.string.app_name) }.getOrDefault("DanyFy")
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val cover = loadCover(context, coverUri)
                val card = renderLyricsCard(title, artist, cover, cleanLines, wordmark)
                writeSharedPng(context, card, "danyfy_lyrics_card.png")
            }.getOrNull()
        } ?: return
        dispatchImage(context, file, chooserTitle)
    }

    private fun writeSharedPng(context: Context, bmp: Bitmap, filename: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, filename).also { f ->
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun dispatchImage(context: Context, file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun loadCover(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        val bmp = runCatching {
            Glide.with(context.applicationContext).asBitmap().load(app.myspoti.host.android.Covers.large(uri)).submit(1000, 1000).get()
        }.getOrNull() ?: return null
        return if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
    }

    private fun drawBackdrop(c: Canvas, cover: Bitmap?, paint: Paint) {
        c.drawColor(Color.rgb(18, 18, 18))
        if (cover != null) {
            val tiny = Bitmap.createScaledBitmap(cover, 16, 16, true)
            c.drawBitmap(tiny, null, RectF(-200f, -200f, W + 200f, H + 200f), paint)
        }
        paint.shader = LinearGradient(
            0f, 0f, 0f, H.toFloat(),
            intArrayOf(Color.argb(125, 0, 0, 0), Color.argb(180, 0, 0, 0), Color.argb(235, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
    }

    private fun drawRoundedCover(c: Canvas, cover: Bitmap?, box: RectF, radius: Float, paint: Paint) {
        if (cover != null) {
            val layer = c.saveLayer(box, null)
            paint.color = Color.BLACK
            c.drawRoundRect(box, radius, radius, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            c.drawBitmap(cover, null, box, paint)
            paint.xfermode = null
            c.restoreToCount(layer)
        } else {
            paint.color = Color.rgb(40, 40, 40)
            c.drawRoundRect(box, radius, radius, paint)
        }
    }

    private fun drawWordmark(c: Canvas, wordmark: String) {
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 255, 255, 255)
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        c.drawText(wordmark, W / 2f, H - 150f, mark)
    }

    internal fun render(title: String, artist: String, cover: Bitmap?, wordmark: String = "DanyFy"): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        drawBackdrop(c, cover, paint)

        val side = 840f
        val left = (W - side) / 2f
        val top = 380f
        drawRoundedCover(c, cover, RectF(left, top, left + side, top + side), 36f, paint)

        val textW = (W - 160)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 62f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(80f, top + side + 80f); titleLayout.draw(c); c.restore()
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 46f }
        val artistLayout = StaticLayout.Builder.obtain(artist, 0, artist.length, artistPaint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(80f, top + side + 80f + titleLayout.height + 24f); artistLayout.draw(c); c.restore()

        drawWordmark(c, wordmark)
        return out
    }

    internal fun renderLyricsCard(
        title: String,
        artist: String,
        cover: Bitmap?,
        lines: List<String>,
        wordmark: String = "DanyFy",
    ): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        drawBackdrop(c, cover, paint)

        // Subtle translucent quote container behind the track header + lyric lines.
        val pad = 84f
        val thumbSize = 128f
        val headerTop = 240f
        drawRoundedCover(c, cover, RectF(pad, headerTop, pad + thumbSize, headerTop + thumbSize), 20f, paint)

        val metaX = pad + thumbSize + 36f
        val metaW = (W - pad - metaX).toInt().coerceAtLeast(100)
        val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerTitleLayout = StaticLayout.Builder.obtain(title, 0, title.length, headerTitlePaint, metaW)
            .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        val headerArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            textSize = 36f
        }
        val headerArtistLayout = StaticLayout.Builder.obtain(artist, 0, artist.length, headerArtistPaint, metaW)
            .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        val metaBlockH = headerTitleLayout.height + 8f + headerArtistLayout.height
        val metaTop = headerTop + (thumbSize - metaBlockH) / 2f
        c.save(); c.translate(metaX, metaTop); headerTitleLayout.draw(c); c.restore()
        c.save(); c.translate(metaX, metaTop + headerTitleLayout.height + 8f); headerArtistLayout.draw(c); c.restore()

        // Lyric block: scale down cleanly if many or long lines are selected so everything stays crisp.
        val quote = lines.joinToString("\n")
        val textW = (W - pad * 2).toInt()
        val maxBodyH = H - 300f - (headerTop + thumbSize + 96f)
        var sizePx = 68f
        var quoteLayout: StaticLayout
        while (true) {
            val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sizePx
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            quoteLayout = StaticLayout.Builder.obtain(quote, 0, quote.length, lp, textW)
                .setLineSpacing(sizePx * 0.24f, 1f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            if (quoteLayout.height <= maxBodyH || sizePx <= 38f) break
            sizePx -= 4f
        }
        val quoteTop = headerTop + thumbSize + 96f + ((maxBodyH - quoteLayout.height).coerceAtLeast(0f) * 0.35f)
        c.save(); c.translate(pad, quoteTop); quoteLayout.draw(c); c.restore()

        drawWordmark(c, wordmark)
        return out
    }
}
