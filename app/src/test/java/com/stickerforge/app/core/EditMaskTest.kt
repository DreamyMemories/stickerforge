package com.stickerforge.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditMaskTest {

    private fun EditMask.a(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    private val opaque = 255.toByte()

    // ---- brush -----------------------------------------------------------

    @Test
    fun `hard erase lowers alpha inside the radius and leaves the outside untouched`() {
        val mask = EditMask(32, 32)
        mask.eraseCircle(16f, 16f, 8f, 1f)

        // Inside the radius (pixel centres are 0.5 off the integer corner).
        assertEquals(0, mask.a(16, 16))
        assertEquals(0, mask.a(20, 16))
        assertEquals(0, mask.a(16, 23))
        assertEquals(0, mask.a(23, 16))

        // Beyond the radius nothing changes.
        assertEquals(255, mask.a(24, 16))
        assertEquals(255, mask.a(16, 24))
        assertEquals(255, mask.a(0, 0))
        assertEquals(255, mask.a(31, 31))
    }

    @Test
    fun `soft erase produces partial values and a falling gradient towards the edge`() {
        val mask = EditMask(64, 64)
        mask.eraseCircle(32f, 32f, 20f, 0f) // falloff spans the whole radius

        val centre = mask.a(32, 32)    // d ~= 0.7
        val mid = mask.a(42, 32)       // d ~= 10.5, inside the soft band
        val nearEdge = mask.a(50, 32)  // d ~= 18.5, near the rim
        val outside = mask.a(60, 32)   // d ~= 28.5

        assertTrue("centre=$centre", centre <= 10)
        assertTrue("mid=$mid must be a partial value", mid in 1..254)
        assertTrue("nearEdge=$nearEdge must be a partial value", nearEdge in 1..254)
        assertTrue("centre=$centre < mid=$mid", centre < mid)
        assertTrue("mid=$mid < nearEdge=$nearEdge", mid < nearEdge)
        assertEquals(255, outside)
    }

    @Test
    fun `hard erase is fully transparent right up to the rim`() {
        val mask = EditMask(64, 64)
        mask.eraseCircle(32f, 32f, 20f, 1f)

        assertEquals(0, mask.a(32, 32))
        assertEquals(0, mask.a(51, 32)) // d = 19.5 < 20
        assertEquals(255, mask.a(52, 32)) // d = 20.5 > 20
    }

    @Test
    fun `restoreCircle raises alpha again`() {
        val mask = EditMask(32, 32)
        mask.eraseCircle(16f, 16f, 10f, 1f)
        assertEquals(0, mask.a(16, 16))
        assertEquals(0, mask.a(16, 24))

        mask.restoreCircle(16f, 16f, 8f, 1f)

        assertEquals(255, mask.a(16, 16))
        // Outside the restore radius the erased pixels stay erased.
        assertEquals(0, mask.a(16, 24))
        // Restoring an already opaque pixel is a no-op.
        assertEquals(255, mask.a(0, 0))

        // A soft restore lifts a partially erased pixel.
        val soft = EditMask(64, 64)
        soft.eraseCircle(32f, 32f, 20f, 0f)
        val before = soft.a(42, 32)
        soft.restoreCircle(32f, 32f, 20f, 0f)
        val after = soft.a(42, 32)
        assertTrue("restore must not lower alpha: $before -> $after", after > before)
    }

    // ---- undo / redo -----------------------------------------------------

    @Test
    fun `undo and redo round-trip alpha exactly`() {
        val mask = EditMask(16, 16)
        val original = mask.alpha.copyOf()

        assertFalse(mask.canUndo)
        assertFalse(mask.canRedo)

        mask.undo() // nothing to undo yet
        mask.redo() // nothing to redo yet
        assertArrayEquals(original, mask.alpha)

        mask.checkpoint()
        mask.eraseCircle(8f, 8f, 4f, 1f)
        val erased = mask.alpha.copyOf()
        assertFalse("erasing must change the mask", original.contentEquals(erased))
        assertTrue(mask.canUndo)
        assertFalse(mask.canRedo)

        mask.undo()
        assertArrayEquals(original, mask.alpha)
        assertFalse(mask.canUndo)
        assertTrue(mask.canRedo)

        mask.redo()
        assertArrayEquals(erased, mask.alpha)
        assertTrue(mask.canUndo)
        assertFalse(mask.canRedo)
    }

    @Test
    fun `undo history keeps at least twenty steps`() {
        val mask = EditMask(32, 32)
        val original = mask.alpha.copyOf()

        repeat(20) { i ->
            mask.checkpoint()
            mask.eraseCircle(i + 0.5f, 16f, 1f, 1f)
        }
        val finalState = mask.alpha.copyOf()

        repeat(20) { mask.undo() }
        assertArrayEquals(original, mask.alpha)
        assertFalse(mask.canUndo)
        assertTrue(mask.canRedo)

        repeat(20) { mask.redo() }
        assertArrayEquals(finalState, mask.alpha)
        assertTrue(mask.canUndo)
        assertFalse(mask.canRedo)
    }

    @Test
    fun `checkpoint clears the redo stack`() {
        val mask = EditMask(8, 8)
        mask.checkpoint()
        mask.eraseCircle(4f, 4f, 2f, 1f)
        mask.undo()
        assertTrue(mask.canRedo)

        mask.checkpoint()
        assertFalse(mask.canRedo)
    }

    @Test
    fun `copy is independent and carries its own history`() {
        val mask = EditMask(8, 8)
        mask.checkpoint()
        mask.eraseCircle(2f, 2f, 1.5f, 1f)
        val erased = mask.alpha.copyOf()

        val clone = mask.copy()
        assertArrayEquals(erased, clone.alpha)

        // Mutating the original does not touch the clone.
        mask.restoreCircle(2f, 2f, 4f, 1f)
        val restored = mask.alpha.copyOf()
        assertArrayEquals(erased, clone.alpha)

        // The clone can undo on its own without affecting the original.
        assertTrue(clone.canUndo)
        clone.undo()
        clone.undo() // no-op, already at the bottom
        assertArrayEquals(ByteArray(64) { opaque }, clone.alpha)
        assertFalse(clone.canUndo)
        assertTrue(clone.canRedo)
        assertArrayEquals(restored, mask.alpha)
    }

    @Test
    fun `reset restores every pixel to opaque`() {
        val mask = EditMask(4, 4)
        mask.checkpoint()
        mask.eraseCircle(1f, 1f, 3f, 1f)
        assertFalse(mask.alpha.all { (it.toInt() and 0xFF) == 255 })

        mask.reset()
        assertTrue(mask.alpha.all { (it.toInt() and 0xFF) == 255 })
    }

    // ---- magic wand ------------------------------------------------------

    @Test
    fun `contiguous wand does not leak across a hard colour boundary`() {
        val w = 8
        val h = 4
        val red = argb(255, 0, 0)
        val blue = argb(0, 0, 255)
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) red else blue }

        val mask = EditMask(w, h)
        mask.magicWand(pixels, seedX = 1, seedY = 1, tolerance = 0, contiguous = true, erase = true)

        for (y in 0 until h) {
            for (x in 0 until w / 2) {
                assertEquals("red pixel ($x, $y)", 0, mask.a(x, y))
            }
            for (x in w / 2 until w) {
                assertEquals("blue pixel ($x, $y)", 255, mask.a(x, y))
            }
        }
    }

    @Test
    fun `contiguous wand is four-connected so diagonal neighbours do not leak`() {
        val red = argb(255, 0, 0)
        val blue = argb(0, 0, 255)
        // (0,0) and (1,1) are red but only touch diagonally.
        val pixels = intArrayOf(
            red, blue, blue,
            blue, red, blue,
            blue, blue, blue,
        )

        val mask = EditMask(3, 3)
        mask.magicWand(pixels, seedX = 0, seedY = 0, tolerance = 0, contiguous = true, erase = true)

        assertEquals(0, mask.a(0, 0))
        assertEquals("diagonal red pixel must stay untouched", 255, mask.a(1, 1))
        assertEquals(255, mask.a(1, 0))
        assertEquals(255, mask.a(0, 1))
    }

    @Test
    fun `tolerance zero matches only the exact colour`() {
        val exact = argb(255, 0, 0)
        val offByOne = argb(255, 0, 1)
        val pixels = intArrayOf(exact, offByOne, exact)

        val mask = EditMask(3, 1)
        mask.magicWand(pixels, seedX = 0, seedY = 0, tolerance = 0, contiguous = false, erase = true)

        assertEquals(0, mask.a(0, 0))
        assertEquals(255, mask.a(1, 0))
        assertEquals(0, mask.a(2, 0))
    }

    @Test
    fun `tolerance is the largest per-channel difference`() {
        val seed = argb(100, 100, 100)
        val greenDiff5 = argb(100, 105, 100)
        val blueDiff6 = argb(100, 100, 106)
        val pixels = intArrayOf(seed, greenDiff5, blueDiff6)

        val mask = EditMask(3, 1)
        mask.magicWand(pixels, seedX = 0, seedY = 0, tolerance = 5, contiguous = false, erase = true)

        assertEquals(0, mask.a(0, 0))
        assertEquals(0, mask.a(1, 0))
        assertEquals(255, mask.a(2, 0))
    }

    @Test
    fun `non-contiguous wand selects every matching pixel and nothing else`() {
        val red = argb(255, 0, 0)
        val blue = argb(0, 0, 255)
        val green = argb(0, 255, 0)
        // Two separated red runs plus decoys; memory order is row-major.
        val pixels = intArrayOf(
            red, blue, red,
            blue, red, green,
        )

        val mask = EditMask(3, 2)
        mask.magicWand(pixels, seedX = 2, seedY = 0, tolerance = 0, contiguous = false, erase = true)

        assertEquals(0, mask.a(0, 0))
        assertEquals(255, mask.a(1, 0))
        assertEquals(0, mask.a(2, 0))
        assertEquals(255, mask.a(0, 1))
        assertEquals(0, mask.a(1, 1))
        assertEquals(255, mask.a(2, 1))
    }

    @Test
    fun `wand with erase false restores only the matching pixels`() {
        val red = argb(255, 0, 0)
        val blue = argb(0, 0, 255)
        val pixels = intArrayOf(red, blue, red)

        val mask = EditMask(3, 1)
        mask.eraseCircle(1f, 0.5f, 8f, 1f)
        assertTrue(mask.alpha.all { (it.toInt() and 0xFF) == 0 })

        mask.magicWand(pixels, seedX = 0, seedY = 0, tolerance = 0, contiguous = false, erase = false)

        assertEquals(255, mask.a(0, 0))
        assertEquals(0, mask.a(1, 0))
        assertEquals(255, mask.a(2, 0))
    }

    @Test
    fun `wand with an out-of-bounds seed changes nothing`() {
        val pixels = IntArray(4) { argb(1, 2, 3) }
        val mask = EditMask(2, 2)

        mask.magicWand(pixels, seedX = -1, seedY = 0, tolerance = 0, contiguous = true, erase = true)
        mask.magicWand(pixels, seedX = 0, seedY = 5, tolerance = 0, contiguous = false, erase = true)

        assertTrue(mask.alpha.all { (it.toInt() and 0xFF) == 255 })
    }

    @Test
    fun `wand rejects a pixel buffer with the wrong size`() {
        val mask = EditMask(2, 2)
        assertThrows(IllegalArgumentException::class.java) {
            mask.magicWand(IntArray(3), seedX = 0, seedY = 0, tolerance = 0, contiguous = true, erase = true)
        }
    }

    // ---- confidence mask -------------------------------------------------

    @Test
    fun `confidence mask maps low to transparent high to opaque and lerps between`() {
        val confidence = floatArrayOf(0f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 1f)
        val mask = EditMask(7, 1)
        mask.applyConfidenceMask(confidence, low = 0.35f, high = 0.65f)

        assertEquals(0, mask.a(0, 0))
        assertEquals(0, mask.a(1, 0))   // 0.2 <= low
        assertEquals(0, mask.a(2, 0))   // exactly low
        assertEquals(128, mask.a(3, 0)) // midpoint -> 0.5 * 255 = 127.5 -> 128
        assertEquals(255, mask.a(4, 0)) // exactly high
        assertEquals(255, mask.a(5, 0))
        assertEquals(255, mask.a(6, 0))
    }

    @Test
    fun `confidence mask uses the documented defaults and rejects the wrong size`() {
        val mask = EditMask(3, 1)
        mask.applyConfidenceMask(floatArrayOf(0.3f, 0.5f, 0.7f))
        assertEquals(0, mask.a(0, 0))
        assertEquals(128, mask.a(1, 0))
        assertEquals(255, mask.a(2, 0))

        assertThrows(IllegalArgumentException::class.java) {
            mask.applyConfidenceMask(floatArrayOf(0.5f, 0.5f))
        }
    }

    // ---- clamping --------------------------------------------------------

    @Test
    fun `repeated erase and restore never wraps outside 0 to 255`() {
        val mask = EditMask(4, 4)
        repeat(3) {
            mask.eraseCircle(2f, 2f, 10f, 1f)
            assertTrue("erase must saturate at 0", mask.alpha.all { (it.toInt() and 0xFF) == 0 })

            mask.restoreCircle(2f, 2f, 10f, 1f)
            assertTrue("restore must saturate at 255", mask.alpha.all { (it.toInt() and 0xFF) == 255 })
        }

        // Extreme confidence values stay inside the byte range.
        mask.applyConfidenceMask(FloatArray(16) { -1f }, low = 0.2f, high = 0.8f)
        assertTrue(mask.alpha.all { (it.toInt() and 0xFF) == 0 })
        mask.applyConfidenceMask(FloatArray(16) { 2f }, low = 0.2f, high = 0.8f)
        assertTrue(mask.alpha.all { (it.toInt() and 0xFF) == 255 })
    }
}
