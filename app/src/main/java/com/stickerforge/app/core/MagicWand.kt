package com.stickerforge.app.core

import kotlin.math.abs

/**
 * Colour-based selection used by [EditMask.magicWand].
 *
 * Pure Kotlin and iterative: a 1024x1024 image is the expected worst case, so
 * the flood fill drives an explicit IntArray stack instead of recursing.
 */
object MagicWand {

    /**
     * Pixels within [tolerance] of the seed pixel's colour. A pixel matches
     * when the largest absolute delta across its R, G and B channels is
     * <= tolerance; alpha is ignored.
     *
     * @param pixels row-major ARGB_8888 pixels, `width * height` entries
     * @param tolerance 0..255, clamped into range
     * @param contiguous true = 4-neighbour flood fill from the seed,
     *                   false = every matching pixel in the image
     * @return a `width * height` mask; true = selected
     */
    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int,
        tolerance: Int,
        contiguous: Boolean,
    ): BooleanArray {
        require(width > 0 && height > 0) { "image must be at least 1x1, got ${width}x$height" }
        require(pixels.size == width * height) {
            "pixels.size=${pixels.size}, expected ${width * height} for ${width}x$height"
        }
        require(seedX in 0 until width && seedY in 0 until height) {
            "seed ($seedX, $seedY) outside ${width}x$height"
        }

        val mask = BooleanArray(pixels.size)
        val seedColour = pixels[seedY * width + seedX]
        val tol = tolerance.coerceIn(0, 255)

        if (!contiguous) {
            for (i in pixels.indices) {
                if (matches(pixels[i], seedColour, tol)) mask[i] = true
            }
            return mask
        }

        val stack = IntArray(pixels.size)
        var top = 0
        val seedIndex = seedY * width + seedX
        mask[seedIndex] = true
        stack[top++] = seedIndex

        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width

            if (x > 0) {
                val n = index - 1
                if (!mask[n] && matches(pixels[n], seedColour, tol)) {
                    mask[n] = true
                    stack[top++] = n
                }
            }
            if (x < width - 1) {
                val n = index + 1
                if (!mask[n] && matches(pixels[n], seedColour, tol)) {
                    mask[n] = true
                    stack[top++] = n
                }
            }
            if (y > 0) {
                val n = index - width
                if (!mask[n] && matches(pixels[n], seedColour, tol)) {
                    mask[n] = true
                    stack[top++] = n
                }
            }
            if (y < height - 1) {
                val n = index + width
                if (!mask[n] && matches(pixels[n], seedColour, tol)) {
                    mask[n] = true
                    stack[top++] = n
                }
            }
        }
        return mask
    }

    private fun matches(pixel: Int, target: Int, tolerance: Int): Boolean {
        val dr = abs(((pixel shr 16) and 0xFF) - ((target shr 16) and 0xFF))
        val dg = abs(((pixel shr 8) and 0xFF) - ((target shr 8) and 0xFF))
        val db = abs((pixel and 0xFF) - (target and 0xFF))
        return maxOf(dr, dg, db) <= tolerance
    }
}
