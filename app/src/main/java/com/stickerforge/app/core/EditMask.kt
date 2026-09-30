package com.stickerforge.app.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-pixel alpha mask for the sticker editor. Pure Kotlin on purpose: it is
 * unit tested on the JVM without android.graphics.
 *
 * [alpha] is row-major (`index = y * width + x`), 0 = fully removed,
 * 255 = fully opaque. Pixels are addressed by their integer corner, so the
 * centre of pixel (x, y) sits at (x + 0.5, y + 0.5) - the same convention
 * Canvas/Bitmap drawing uses. Every mutation clamps to 0..255.
 *
 * Undo is caller-driven: call [checkpoint] once before each user operation,
 * then [undo] / [redo] replay the history. The history keeps at least the
 * 20 steps `docs/ARCHITECTURE.md` requires (24 in this implementation).
 */
class EditMask(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "EditMask size must be positive, got ${width}x$height" }
    }

    /** Row-major, 0 = fully removed, 255 = fully opaque. */
    val alpha: ByteArray = ByteArray(width * height) { OPAQUE }

    private val undoStack = ArrayList<ByteArray>()
    private val redoStack = ArrayList<ByteArray>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Snapshot the current alpha so the next [undo] returns here. Clears the redo stack. */
    fun checkpoint() {
        undoStack.add(alpha.copyOf())
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
    }

    /** Restore the last checkpoint. No-op when [canUndo] is false. */
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.add(alpha.copyOf())
        if (redoStack.size > MAX_HISTORY) redoStack.removeAt(0)
        previous.copyInto(alpha)
    }

    /** Re-apply an undone checkpoint. No-op when [canRedo] is false. */
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(alpha.copyOf())
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        next.copyInto(alpha)
    }

    /** Every pixel back to fully opaque. The history stacks are left alone. */
    fun reset() {
        alpha.fill(OPAQUE)
    }

    /**
     * Soft radial eraser: alpha falls to 0 over the brush footprint.
     *
     * @param cx centre in pixel coordinates
     * @param cy centre in pixel coordinates
     * @param radius brush radius in pixels
     * @param hardness 0 = long soft edge (falloff spans the whole radius),
     *                 1 = hard edge (full erase everywhere inside the radius)
     */
    fun eraseCircle(cx: Float, cy: Float, radius: Float, hardness: Float) {
        paintCircle(cx, cy, radius, hardness, erase = true)
    }

    /**
     * Soft radial restorer: alpha rises back towards 255 over the brush
     * footprint. Same parameters as [eraseCircle].
     */
    fun restoreCircle(cx: Float, cy: Float, radius: Float, hardness: Float) {
        paintCircle(cx, cy, radius, hardness, erase = false)
    }

    /**
     * Colour-based selection.
     *
     * @param pixels source ARGB_8888 pixels, same length as [alpha]
     * @param seedX seed pixel x, ignored when out of bounds
     * @param seedY seed pixel y, ignored when out of bounds
     * @param tolerance 0..255, max difference across the R, G and B channels
     * @param contiguous true = 4-neighbour flood fill from the seed,
     *                   false = every pixel in the image matching the seed colour
     * @param erase true = set selected alpha to 0, false = set it to 255
     */
    fun magicWand(
        pixels: IntArray,
        seedX: Int,
        seedY: Int,
        tolerance: Int,
        contiguous: Boolean,
        erase: Boolean,
    ) {
        require(pixels.size == alpha.size) {
            "pixels.size=${pixels.size}, expected ${alpha.size} for ${width}x$height"
        }
        if (seedX !in 0 until width || seedY !in 0 until height) return
        val selected = MagicWand.select(pixels, width, height, seedX, seedY, tolerance, contiguous)
        val value = if (erase) TRANSPARENT else OPAQUE
        for (i in selected.indices) {
            if (selected[i]) alpha[i] = value
        }
    }

    /**
     * ML Kit confidence mask (0f..1f): values <= [low] become 0, values
     * >= [high] become 255, and the gap is mapped linearly.
     */
    fun applyConfidenceMask(confidence: FloatArray, low: Float = 0.35f, high: Float = 0.65f) {
        require(confidence.size == alpha.size) {
            "confidence.size=${confidence.size}, expected ${alpha.size} for ${width}x$height"
        }
        for (i in confidence.indices) {
            val c = confidence[i]
            val value = when {
                c.isNaN() -> 0f
                c <= low -> 0f
                high <= low || c >= high -> 255f
                else -> ((c - low) / (high - low)) * 255f
            }
            alpha[i] = value.roundToInt().coerceIn(0, 255).toByte()
        }
    }

    /** Independent copy, including both history stacks. */
    fun copy(): EditMask {
        val clone = EditMask(width, height)
        alpha.copyInto(clone.alpha)
        for (snapshot in undoStack) clone.undoStack.add(snapshot.copyOf())
        for (snapshot in redoStack) clone.redoStack.add(snapshot.copyOf())
        return clone
    }

    // ---- internals -------------------------------------------------------

    private fun paintCircle(cx: Float, cy: Float, radius: Float, hardness: Float, erase: Boolean) {
        if (radius <= 0f || !cx.isFinite() || !cy.isFinite()) return
        val h = hardness.coerceIn(0f, 1f)
        val inner = radius * h
        val outer = radius

        val minX = floor(cx - outer).toInt().coerceAtLeast(0)
        val maxX = ceil(cx + outer).toInt().coerceAtMost(width - 1)
        val minY = floor(cy - outer).toInt().coerceAtLeast(0)
        val maxY = ceil(cy + outer).toInt().coerceAtMost(height - 1)
        if (minX > maxX || minY > maxY) return

        for (y in minY..maxY) {
            val dy = y + 0.5f - cy
            val row = y * width
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val distance = sqrt(dx * dx + dy * dy)
                if (distance >= outer) continue
                val coverage = if (distance <= inner) {
                    1f
                } else {
                    // inner == outer only when hardness == 1, where the branch
                    // above always wins, so the division is safe.
                    smoothStep((outer - distance) / (outer - inner))
                }
                if (coverage <= 0f) continue

                val index = row + x
                val current = alpha[index].toInt() and 0xFF
                val updated = if (erase) {
                    current * (1f - coverage)
                } else {
                    current + (OPAQUE_INT - current) * coverage
                }
                alpha[index] = updated.roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    private fun smoothStep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private companion object {
        /** Undo depth; docs/ARCHITECTURE.md requires at least 20. */
        const val MAX_HISTORY = 24

        const val OPAQUE_INT = 255
        val OPAQUE: Byte = OPAQUE_INT.toByte()
        val TRANSPARENT: Byte = 0
    }
}
