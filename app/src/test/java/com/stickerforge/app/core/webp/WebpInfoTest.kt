package com.stickerforge.app.core.webp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpInfoTest {

    @Test
    fun `simple VP8 lossy file reports bitstream dimensions`() {
        val info = WebpInfo.parse(WebpTestData.stillVp8(320, 240))
        assertEquals(320, info.width)
        assertEquals(240, info.height)
        assertEquals(1, info.frameCount)
        assertEquals(listOf(1000), info.frameDurationsMs)
        assertEquals(1000, info.totalDurationMs)
        assertFalse(info.hasAlpha)
    }

    @Test
    fun `simple VP8L file reports dimensions and alpha bit`() {
        val withAlpha = WebpInfo.parse(WebpTestData.stillVp8l(512, 384, alpha = true))
        assertEquals(512, withAlpha.width)
        assertEquals(384, withAlpha.height)
        assertTrue(withAlpha.hasAlpha)

        val withoutAlpha = WebpInfo.parse(WebpTestData.stillVp8l(512, 384, alpha = false))
        assertFalse(withoutAlpha.hasAlpha)
    }

    @Test
    fun `extended still file reports the VP8X canvas and alpha`() {
        val info = WebpInfo.parse(WebpTestData.extendedStillVp8(512, 512))
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertEquals(1, info.frameCount)
        assertEquals(listOf(1000), info.frameDurationsMs)
        assertEquals(1000, info.totalDurationMs)
        assertTrue(info.hasAlpha)
    }

    @Test
    fun `animated file reports frame count durations total and canvas`() {
        val bytes = WebpTestData.animatedVp8l(512, 512, listOf(40, 60, 100))
        val info = WebpInfo.parse(bytes)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertEquals(3, info.frameCount)
        assertEquals(listOf(40, 60, 100), info.frameDurationsMs)
        assertEquals(200, info.totalDurationMs)
        assertTrue(info.hasAlpha)
    }

    @Test
    fun `animated file without alpha does not report alpha`() {
        val bytes = WebpTestData.animatedVp8(512, 512, listOf(40, 40))
        val info = WebpInfo.parse(bytes)
        assertEquals(2, info.frameCount)
        assertFalse(info.hasAlpha)
    }

    @Test
    fun `odd sized chunk payloads are skipped with their padding`() {
        // An unknown 7-byte chunk sits before the bitstream: the parser must
        // step over size+1 bytes to find the VP8 header.
        val unknown = WebpTestData.chunk("XMP ", ByteArray(7))
        val info = WebpInfo.parse(
            WebpTestData.riff(unknown, WebpTestData.chunk("VP8 ", WebpTestData.vp8Payload(321, 123)))
        )
        assertEquals(321, info.width)
        assertEquals(123, info.height)
    }

    @Test
    fun `odd sized ALPH payload before the bitstream still parses`() {
        val bytes = WebpTestData.riff(
            WebpTestData.vp8xChunk(512, 512, animation = false, alpha = true),
            WebpTestData.chunk("ALPH", ByteArray(3)),
            WebpTestData.chunk("VP8 ", WebpTestData.vp8Payload(512, 512)),
        )
        val info = WebpInfo.parse(bytes)
        assertEquals(512, info.width)
        assertTrue(info.hasAlpha)
    }

    @Test
    fun `non WebP input is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WebpInfo.parse(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            WebpInfo.parse("GIF89a-not-a-webp-at-all".toByteArray(Charsets.US_ASCII))
        }
        val wave = ByteArray(12)
        WebpTestData.writeAscii(wave, 0, "RIFF")
        WebpTestData.writeUInt32LE(wave, 4, 4)
        WebpTestData.writeAscii(wave, 8, "WAVE")
        assertThrows(IllegalArgumentException::class.java) { WebpInfo.parse(wave) }
    }

    @Test
    fun `truncated chunk is rejected`() {
        val bytes = ByteArray(20)
        WebpTestData.writeAscii(bytes, 0, "RIFF")
        WebpTestData.writeUInt32LE(bytes, 4, 12)
        WebpTestData.writeAscii(bytes, 8, "WEBP")
        WebpTestData.writeAscii(bytes, 12, "VP8 ")
        WebpTestData.writeUInt32LE(bytes, 16, 100)
        assertThrows(IllegalArgumentException::class.java) { WebpInfo.parse(bytes) }
    }

    @Test
    fun `animation signal without frames is rejected`() {
        val bytes = WebpTestData.riff(
            WebpTestData.vp8xChunk(512, 512, animation = true),
            WebpTestData.animChunk(),
        )
        assertThrows(IllegalArgumentException::class.java) { WebpInfo.parse(bytes) }
    }

    @Test
    fun `ANMF frames without a VP8X header are rejected`() {
        val bytes = WebpTestData.riff(
            WebpTestData.anmfChunk(512, 512, 40, listOf(WebpTestData.chunk("VP8L", WebpTestData.vp8lPayload(512, 512)))),
        )
        assertThrows(IllegalArgumentException::class.java) { WebpInfo.parse(bytes) }
    }

    @Test
    fun `VP8X without image data is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebpInfo.parse(WebpTestData.riff(WebpTestData.vp8xChunk(512, 512)))
        }
    }

    @Test
    fun `invalid VP8 start code is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebpInfo.parse(WebpTestData.riff(WebpTestData.chunk("VP8 ", ByteArray(10))))
        }
    }

    @Test
    fun `canvas dimensions use 24-bit values`() {
        val info = WebpInfo.parse(WebpTestData.extendedStillVp8(4096, 2048))
        assertEquals(4096, info.width)
        assertEquals(2048, info.height)
    }
}
