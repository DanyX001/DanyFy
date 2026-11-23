package com.music.spotui.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.spotui.rust.OrpheonRustAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Side of the cover decode for colours and the mesh gradient. Glide keys its decodes on the size,
 * so one size means the cover is decoded once for both.
 */
const val COVER_DECODE_PX = 128
private const val RUST_PALETTE_PX = 64

internal data class ExtractedCoverColors(
    val mesh: List<Color>,
    val first: Color,
    val second: Color,
)

class Palette {
    companion object {
        /** The tint when a cover gives none (none loaded). */
        val NEUTRAL = Color(0xFF2A2A2A)
        private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Cover colours already computed (process-wide): a screen opening on a known
        // cover gets its colour in the first frame instead of popping in afterwards.
        private val colors = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, Color>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 200
        })
        fun cachedSecond(imageUrl: String): Color? = colors["2|$imageUrl"]
        fun cachedFirst(imageUrl: String): Color? = colors["1|$imageUrl"]

        internal fun cacheTintPair(imageUrl: String, first: Color, second: Color) {
            colors["1|$imageUrl"] = first
            colors["2|$imageUrl"] = second
        }

        internal fun clearCaches() {
            colors.clear()
        }

        /**
         * Shared cover loader at [COVER_DECODE_PX] software pixels so Palette, MeshGradient, and
         * blurred sheet washes share a single Glide decode entry per track cover.
         */
        internal fun loadCoverBitmap(
            context: Context,
            imageUrl: String,
            onLoaded: (Bitmap) -> Unit,
            onFailed: () -> Unit = {},
        ): CustomTarget<Bitmap> {
            val resolved = com.music.spotui.ui.components.spotifyImageUrl(imageUrl) ?: imageUrl
            return Glide.with(context.applicationContext)
                .asBitmap()
                .load(resolved)
                .override(COVER_DECODE_PX, COVER_DECODE_PX)
                .disallowHardwareConfig()
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) = onLoaded(resource)
                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                    override fun onLoadFailed(errorDrawable: Drawable?) = onFailed()
                })
        }

        /**
         * Native Rust perceptual OKLab palette extraction over a compact [RUST_PALETTE_PX] square.
         * Returns null if native extraction is unavailable so Kotlin Palette can run as fallback.
         */
        internal fun extractAllFast(bitmap: Bitmap): ExtractedCoverColors? {
            if (!OrpheonRustAudio.available || bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return null
            val scaled = try {
                when {
                    bitmap.width > RUST_PALETTE_PX || bitmap.height > RUST_PALETTE_PX ->
                        Bitmap.createScaledBitmap(bitmap, RUST_PALETTE_PX, RUST_PALETTE_PX, true)
                    bitmap.config != Bitmap.Config.ARGB_8888 ->
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    else -> bitmap
                }
            } catch (_: Throwable) {
                null
            } ?: return null
            return try {
                val w = scaled.width
                val h = scaled.height
                val pixels = IntArray(w * h)
                scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                val raw = OrpheonRustAudio.nativeExtractArtworkColors(pixels, w, h) ?: return null
                if (raw.size < 6) return null
                ExtractedCoverColors(
                    mesh = listOf(Color(raw[0]), Color(raw[1]), Color(raw[2]), Color(raw[3])),
                    first = Color(raw[4]),
                    second = Color(raw[5]),
                )
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }
        }

        /** Small owned software thumbnail copy taken in `onResourceReady` for off-thread processing. */
        internal fun copyForWorker(resource: Bitmap, maxEdgePx: Int = RUST_PALETTE_PX): Bitmap? = try {
            if (resource.isRecycled || resource.width <= 0 || resource.height <= 0) null
            else if (resource.width > maxEdgePx || resource.height > maxEdgePx) {
                Bitmap.createScaledBitmap(resource, maxEdgePx, maxEdgePx, true)
            } else {
                resource.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * The first colour carries white text and icons (the mini player, the player's gradient). A
         * cover with no dark swatch gave its dominant colour, a pale yellow or pink the white text
         * vanished on: darkened, same hue, until white reads on it.
         */
        internal fun readableUnderWhite(c: Color): Color {
            var out = c
            var t = 0f
            while (out.luminance() > MAX_TINT_LUMINANCE && t < 0.8f) {
                t += 0.05f
                out = androidx.compose.ui.graphics.lerp(c, Color.Black, t)
            }
            return out
        }

        /** White on a colour of this luminance has a contrast ratio of about 5.5:1. */
        private const val MAX_TINT_LUMINANCE = 0.14f
    }

    fun extractFirstColorFromImageUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) =
        extractTintAsync(context, imageUrl, pickFirst = true, onColorExtracted = onColorExtracted)

    fun extractSecondColorFromCoverUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) =
        extractTintAsync(context, imageUrl, pickFirst = false, onColorExtracted = onColorExtracted)

    private fun extractTintAsync(
        context: Context,
        imageUrl: String,
        pickFirst: Boolean,
        onColorExtracted: (Color) -> Unit,
    ) {
        val key = (if (pickFirst) "1|" else "2|") + imageUrl
        colors[key]?.let { onColorExtracted(it); return }
        loadCoverBitmap(
            context = context,
            imageUrl = imageUrl,
            onLoaded = { resource ->
                val work = copyForWorker(resource, maxEdgePx = RUST_PALETTE_PX)
                if (work == null) {
                    onColorExtracted(NEUTRAL)
                    return@loadCoverBitmap
                }
                workerScope.launch {
                    val result = try {
                        extractAllFast(work)?.also {
                            cacheTintPair(imageUrl, it.first, it.second)
                        } ?: extractWithAndroidPaletteFallback(work).also {
                            cacheTintPair(imageUrl, it.first, it.second)
                        }
                    } finally {
                        if (!work.isRecycled) work.recycle()
                    }
                    val picked = if (pickFirst) result.first else result.second
                    withContext(Dispatchers.Main.immediate) { onColorExtracted(picked) }
                }
            },
            onFailed = { onColorExtracted(NEUTRAL) },
        )
    }

    private fun extractWithAndroidPaletteFallback(bitmap: Bitmap): ExtractedCoverColors {
        val generated = Palette.from(bitmap).generate()
        val firstRgb = (generated.darkVibrantSwatch ?: generated.darkMutedSwatch ?: generated.dominantSwatch ?: generated.vibrantSwatch)?.rgb
        val secondRgb = (generated.mutedSwatch ?: generated.darkMutedSwatch ?: generated.dominantSwatch ?: generated.vibrantSwatch)?.rgb
        val first = firstRgb?.let { readableUnderWhite(Color(it or (0xFF shl 24))) } ?: NEUTRAL
        val second = secondRgb?.let { Color(it or (0xFF shl 24)) } ?: NEUTRAL
        return ExtractedCoverColors(listOf(first, second), first, second)
    }
}