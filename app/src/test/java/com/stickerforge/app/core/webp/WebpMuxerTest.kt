package com.stickerforge.app.core.webp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpMuxerTest {

    @Test
    fun `mux preserves frame count durations and canvas`() {
        val frames = listOf(
            StillWebp(WebpTestData.stillVp8l(512, 512, alpha = true), 100),
            StillWebp(WebpTestData.stillVp8l(512, 512, alpha = true), 200),
            StillWebp(WebpTestData.stillVp8l(512, 512, alpha = true), 300),
        )

        val muxed = WebpMuxer.mux(frames, 512, 512)
        val info = WebpInfo.parse(muxed)

        assertEquals(3, info.frameCount)
        assertEquals(listOf(100, 200, 300), info.frameDurationsMs)
        assertEquals(600, info.totalDurationMs)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertTrue(info.hasAlpha)
    }

    @Test
    fun `mux writes a well formed RIFF container`() {
        val muxed = WebpMuxer.mux(listOf(StillWebp(WebpTestData.stillVp8(512, 512), 40)), 512, 512)

        assertEquals("RIFF", String(muxed, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(muxed, 8, 4, Charsets.US_ASCII))
        assertEquals((muxed.size - 8).toLong(), WebpTestData.readUInt32LE(muxed, 4))
    }

    @Test
    fun `mux sets the animation flag and writes the loop count`() {
        val frame = WebpTestData.stillVp8(512, 512)
        val muxed = WebpMuxer.mux(listOf(StillWebp(frame, 40)), 512, 512, loopCount = 3)

        val vp8x = WebpTestData.findChunk(muxed, "VP8X")
        assertNotNull(vp8x)
        assertEquals(0x02, vp8x!![0].toInt() and 0x02)
        assertEquals(0, vp8x[0].toInt() and 0x10) // no alpha in the frame

        val anim = WebpTestData.findChunk(muxed, "ANIM")
        assertNotNull(anim)
        assertEquals(3, WebpTestData.readUInt16LE(anim!!, 4))
    }

    @Test
    fun `mux sets the alpha flag when a frame has alpha`() {
        val muxed = WebpMuxer.mux(listOf(StillWebp(WebpTestData.stillVp8l(512, 512, alpha = true), 40)), 512, 512)
        val vp8x = WebpTestData.findChunk(muxed, "VP8X")!!
        assertEquals(0x12, vp8x[0].toInt() and 0x12)
        assertTrue(WebpInfo.parse(muxed).hasAlpha)
    }

    @Test
    fun `mux accepts extended still frames`() {
        val frames = listOf(
            StillWebp(WebpTestData.extendedStillVp8(512, 512), 40),
            StillWebp(WebpTestData.extendedStillVp8(512, 512), 60),
        )

        val info = WebpInfo.parse(WebpMuxer.mux(frames, 512, 512))
        assertEquals(2, info.frameCount)
        assertEquals(listOf(40, 60), info.frameDurationsMs)
        assertTrue(info.hasAlpha)
    }

    @Test
    fun `mux copies frame chunks verbatim including odd sized padding`() {
        val vp8Payload = WebpTestData.vp8Payload(512, 512)
        val alphPayload = byteArrayOf(1, 2, 3) // odd size on purpose
        val frame = WebpTestData.riff(
            WebpTestData.vp8xChunk(512, 512, animation = false, alpha = true),
            WebpTestData.chunk("ALPH", alphPayload),
            WebpTestData.chunk("VP8 ", vp8Payload),
        )

        val muxed = WebpMuxer.mux(listOf(StillWebp(frame, 120)), 512, 512)
        val anmf = WebpTestData.findChunk(muxed, "ANMF")
        assertNotNull(anmf)

        assertArrayEquals(alphPayload, WebpTestData.innerChunk(anmf!!, 16, "ALPH"))
        assertArrayEquals(vp8Payload, WebpTestData.innerChunk(anmf, 16, "VP8 "))
        assertEquals(120, WebpInfo.parse(muxed).frameDurationsMs.single())
    }

    @Test
    fun `mux writes frame durations as 24-bit values`() {
        // 70000 ms does not fit in 16 bits, so it only parses back correctly
        // when the duration is written as a 24-bit little-endian field.
        val muxed = WebpMuxer.mux(
            listOf(
                StillWebp(WebpTestData.stillVp8(512, 512), 8),
                StillWebp(WebpTestData.stillVp8(512, 512), 70000),
            ),
            512,
            512,
        )
        assertEquals(listOf(8, 70000), WebpInfo.parse(muxed).frameDurationsMs)
    }

    @Test
    fun `mux rejects invalid frames and arguments`() {
        val still = StillWebp(WebpTestData.stillVp8(512, 512), 40)

        assertThrows(IllegalArgumentException::class.java) { WebpMuxer.mux(emptyList(), 512, 512) }
        assertThrows(IllegalArgumentException::class.java) { WebpMuxer.mux(listOf(still), 0, 512) }
        assertThrows(IllegalArgumentException::class.java) { WebpMuxer.mux(listOf(still), 512, 0) }
        assertThrows(IllegalArgumentException::class.java) { WebpMuxer.mux(listOf(still), 512, 512, loopCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { WebpMuxer.mux(listOf(still), 512, 512, loopCount = 0x10000) }
        assertThrows(IllegalArgumentException::class.java) {
            WebpMuxer.mux(listOf(StillWebp(still.bytes, 0)), 512, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebpMuxer.mux(listOf(StillWebp(still.bytes, 0x1000000)), 512, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebpMuxer.mux(listOf(StillWebp(byteArrayOf(1, 2, 3), 40)), 512, 512)
        }
    }

    @Test
    fun `mux rejects animated frames because input must be still`() {
        val animated = StillWebp(WebpTestData.animatedVp8l(512, 512, listOf(40, 40)), 40)
        assertThrows(IllegalArgumentException::class.java) { WebpMuxer.mux(listOf(animated), 512, 512) }
    }

    @Test
    fun `mux without alpha keeps the VP8X alpha flag clear`() {
        val muxed = WebpMuxer.mux(
            listOf(StillWebp(WebpTestData.stillVp8(512, 512), 40)),
            512,
            512,
        )
        assertFalse(WebpInfo.parse(muxed).hasAlpha)
    }
}
