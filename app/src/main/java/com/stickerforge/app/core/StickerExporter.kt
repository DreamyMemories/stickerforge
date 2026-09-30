package com.stickerforge.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.stickerforge.app.core.webp.StillWebp
import com.stickerforge.app.core.webp.WebpMuxer
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Turns edited bitmaps into WhatsApp sticker size: exactly 512x512 WebP,
 * transparent background, inside the WhatsApp size budgets.
 */
object StickerExporter {

    const val SIZE = 512
    const val STATIC_BUDGET_BYTES = 100 * 1024
    const val ANIMATED_BUDGET_BYTES = 500 * 1024
    const val MAX_ANIMATED_FRAMES = 60

    private val qualities = intArrayOf(95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15)

    @Suppress("DEPRECATION")
    private val compressFormat: Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    /** Applies the mask to a frame, producing a transparent-background bitmap. */
    fun compose(frame: Bitmap, mask: EditMask): Bitmap {
        require(frame.width == mask.width && frame.height == mask.height) {
            "Mask ${mask.width}x${mask.height} does not match frame ${frame.width}x${frame.height}"
        }
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        val alpha = mask.alpha
        for (i in pixels.indices) {
            val maskAlpha = alpha[i].toInt() and 0xFF
            if (maskAlpha == 0) {
                pixels[i] = 0
            } else if (maskAlpha < 255) {
                val color = pixels[i]
                val sourceAlpha = (color ushr 24) and 0xFF
                val combined = sourceAlpha * maskAlpha / 255
                pixels[i] = (color and 0x00FFFFFF) or (combined shl 24)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Scales/crops to 512x512.
     *
     * @param fill true = centre-crop to fill the square, false = fit inside
     *        with transparent padding
     */
    fun toSquare(source: Bitmap, fill: Boolean = true): Bitmap {
        val side = min(source.width, source.height)
        val cropped = if (fill) {
            Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        } else {
            val scale = SIZE.toFloat() / maxOf(source.width, source.height)
            val scaled = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            val canvas = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvasBitmap = android.graphics.Canvas(canvas)
            canvasBitmap.drawBitmap(
                scaled,
                ((SIZE - scaled.width) / 2).toFloat(),
                ((SIZE - scaled.height) / 2).toFloat(),
                null,
            )
            scaled.recycle()
            return canvas
        }
        return if (cropped.width == SIZE && cropped.height == SIZE) {
            cropped
        } else {
            val scaled = Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
            cropped.recycle()
            scaled
        }
    }

    fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, out)
        return out.toByteArray()
    }

    /** Static sticker: smallest WebP that fits the budget, everything else equal. */
    fun exportStatic(frame: Bitmap, mask: EditMask? = null, fill: Boolean = true, budgetBytes: Int = STATIC_BUDGET_BYTES): ByteArray {
        val composed = if (mask != null) compose(frame, mask) else frame
        val square = toSquare(composed, fill)
        var smallest: ByteArray? = null
        for (quality in qualities) {
            val bytes = compress(square, quality)
            if (smallest == null || bytes.size < smallest.size) smallest = bytes
            if (bytes.size <= budgetBytes) break
        }
        return smallest ?: ByteArray(0)
    }

    /** Animated sticker: 512x512, every frame masked, muxed and quality-searched. */
    fun exportAnimated(
        frames: List<Bitmap>,
        durationsMs: List<Int>,
        mask: EditMask? = null,
        fill: Boolean = true,
        budgetBytes: Int = ANIMATED_BUDGET_BYTES,
    ): ByteArray {
        require(frames.isNotEmpty()) { "No frames to export" }
        val picked = decimate(frames, durationsMs, MAX_ANIMATED_FRAMES)
        val prepared = picked.first.map { frame ->
            val composed = if (mask != null) compose(frame, mask) else frame
            toSquare(composed, fill)
        }
        val durations = picked.second.map { it.coerceAtLeast(20) }
        var smallest: ByteArray? = null
        for (quality in qualities) {
            val stills = prepared.mapIndexed { index, bitmap ->
                StillWebp(compress(bitmap, quality), durations[index])
            }
            val bytes = WebpMuxer.mux(stills, SIZE, SIZE)
            if (smallest == null || bytes.size < smallest.size) smallest = bytes
            if (bytes.size <= budgetBytes) break
        }
        prepared.forEach { it.recycle() }
        return smallest ?: ByteArray(0)
    }

    /** Evenly reduces a long frame list so stickers stay small. */
    fun decimate(frames: List<Bitmap>, durationsMs: List<Int>, maxFrames: Int): Pair<List<Bitmap>, List<Int>> {
        if (frames.size <= maxFrames) {
            val durations = frames.indices.map { durationsMs.getOrElse(it) { 100 } }
            return frames to durations
        }
        val step = frames.size.toFloat() / maxFrames
        val pickedFrames = ArrayList<Bitmap>(maxFrames)
        val pickedDurations = ArrayList<Int>(maxFrames)
        for (i in 0 until maxFrames) {
            val index = (i * step).toInt().coerceIn(0, frames.lastIndex)
            pickedFrames += frames[index]
            // merge the durations of the skipped frames into the kept one
            val nextIndex = ((i + 1) * step).toInt().coerceIn(0, frames.size)
            var merged = 0
            for (j in index until nextIndex) merged += durationsMs.getOrElse(j) { 100 }
            pickedDurations += merged.coerceAtLeast(20)
        }
        return pickedFrames to pickedDurations
    }

    /** Cheap still-frame helper for packs where only one frame is wanted. */
    fun decodeFirstFrame(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
