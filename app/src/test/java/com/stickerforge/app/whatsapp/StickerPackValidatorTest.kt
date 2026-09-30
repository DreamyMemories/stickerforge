package com.stickerforge.app.whatsapp

import com.stickerforge.app.core.webp.WebpTestData
import com.stickerforge.app.model.Sticker
import com.stickerforge.app.model.StickerPack
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPackValidatorTest {

    // ---- fixtures ----

    private fun staticPack(count: Int = 3): StickerPack = StickerPack(
        identifier = "test_pack",
        name = "Test Pack",
        publisher = "Tester",
        stickers = (1..count).map { sticker(it, animated = false) },
    )

    private fun animatedPack(count: Int = 3): StickerPack = staticPack(count).copy(
        animated = true,
        stickers = (1..count).map { sticker(it, animated = true) },
    )

    private fun sticker(index: Int, animated: Boolean): Sticker = Sticker(
        id = "sticker_$index",
        fileName = "sticker_$index.webp",
        emojis = listOf("\uD83D\uDE00"),
        animated = animated,
    )

    private fun staticFiles(count: Int = 3, size: Int = 512, extraBytes: Int = 0): Map<String, ByteArray> =
        (1..count).associate { "sticker_$it.webp" to WebpTestData.stillVp8(size, size, extraBytes) }

    private fun animatedFiles(
        durations: List<Int> = listOf(40, 40),
        count: Int = 3,
        extraBytesPerFrame: Int = 0,
    ): Map<String, ByteArray> = (1..count).associate { index ->
        "sticker_$index.webp" to WebpTestData.animatedVp8l(
            width = 512,
            height = 512,
            frameDurationsMs = durations,
            extraBytesPerFrame = extraBytesPerFrame,
        )
    }

    private fun withSticker(pack: StickerPack, index: Int, transform: (Sticker) -> Sticker): StickerPack =
        pack.copy(stickers = pack.stickers.mapIndexed { i, s -> if (i == index) transform(s) else s })

    /** Minimal PNG header: signature + IHDR chunk with the given dimensions. */
    private fun pngHeader(width: Int, height: Int, extraBytes: Int = 0): ByteArray {
        val bytes = ByteArray(33 + extraBytes)
        val signature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (index in signature.indices) bytes[index] = signature[index].toByte()
        WebpTestData.writeUInt32LE(bytes, 8, 13) // IHDR data length
        WebpTestData.writeAscii(bytes, 12, "IHDR")
        writeIntBE(bytes, 16, width)
        writeIntBE(bytes, 20, height)
        return bytes
    }

    // ---- helpers ----

    private fun validate(pack: StickerPack, files: Map<String, ByteArray>, tray: ByteArray = pngHeader(96, 96)) {
        StickerPackValidator.validate(pack, { files.getValue(it.fileName) }, { tray })
    }

    private fun assertRejects(
        pack: StickerPack,
        files: Map<String, ByteArray>,
        tray: ByteArray = pngHeader(96, 96),
        messagePart: String? = null,
    ) {
        val error = assertThrows(StickerPackValidationException::class.java) { validate(pack, files, tray) }
        if (messagePart != null) {
            assertTrue(
                "expected message containing '$messagePart' but was '${error.message}'",
                error.message.orEmpty().contains(messagePart),
            )
        }
    }

    // ---- happy paths ----

    @Test
    fun `fully valid static pack passes`() {
        validate(staticPack(), staticFiles())
    }

    @Test
    fun `fully valid animated pack passes`() {
        validate(animatedPack(), animatedFiles(durations = listOf(40, 60)))
    }

    @Test
    fun `minimum and maximum sticker counts pass`() {
        validate(staticPack(count = 3), staticFiles(count = 3))
        validate(staticPack(count = 30), staticFiles(count = 30))
    }

    // ---- identifier, name, publisher ----

    @Test
    fun `identifier must be non blank and at most 128 chars`() {
        assertRejects(staticPack().copy(identifier = ""), staticFiles(), messagePart = "identifier")
        assertRejects(staticPack().copy(identifier = "  "), staticFiles(), messagePart = "identifier")
        assertRejects(staticPack().copy(identifier = "a".repeat(129)), staticFiles(), messagePart = "128")
        validate(staticPack().copy(identifier = "a".repeat(128)), staticFiles())
    }

    @Test
    fun `identifier charset rejects bad characters and double dots`() {
        assertRejects(staticPack().copy(identifier = "bad/id"), staticFiles(), messagePart = "identifier")
        assertRejects(staticPack().copy(identifier = "bad..id"), staticFiles(), messagePart = "'..'")
        validate(staticPack().copy(identifier = "Good_pack-1.,' x"), staticFiles())
    }

    @Test
    fun `name and publisher must be non blank and at most 128 chars`() {
        assertRejects(staticPack().copy(name = " \t "), staticFiles(), messagePart = "name")
        assertRejects(staticPack().copy(name = "n".repeat(129)), staticFiles(), messagePart = "128")
        assertRejects(staticPack().copy(publisher = ""), staticFiles(), messagePart = "publisher")
        assertRejects(staticPack().copy(publisher = "p".repeat(129)), staticFiles(), messagePart = "128")
    }

    // ---- sticker metadata ----

    @Test
    fun `sticker count outside 3 to 30 is rejected`() {
        assertRejects(staticPack(count = 2), staticFiles(count = 2), messagePart = "3..30")
        assertRejects(staticPack(count = 31), staticFiles(count = 31), messagePart = "3..30")
    }

    @Test
    fun `each sticker needs one to three emojis`() {
        assertRejects(
            withSticker(staticPack(), 0) { it.copy(emojis = emptyList()) },
            staticFiles(),
            messagePart = "emojis",
        )
        assertRejects(
            withSticker(staticPack(), 1) { it.copy(emojis = List(4) { "\uD83D\uDE00" }) },
            staticFiles(),
            messagePart = "emojis",
        )
    }

    @Test
    fun `sticker file names must not be blank`() {
        assertRejects(
            withSticker(staticPack(), 1) { it.copy(fileName = " ") },
            staticFiles(),
            messagePart = "blank file name",
        )
    }

    // ---- sticker dimensions and bitstream validity ----

    @Test
    fun `stickers must be 512x512`() {
        assertRejects(staticPack(), staticFiles(size = 256), messagePart = "512x512")
        val files = staticFiles().toMutableMap()
        files["sticker_2.webp"] = WebpTestData.stillVp8(512, 256)
        assertRejects(staticPack(), files, messagePart = "512x512")
    }

    @Test
    fun `sticker that is not a valid WebP is rejected`() {
        val files = staticFiles().toMutableMap()
        files["sticker_3.webp"] = "not a webp".toByteArray(Charsets.US_ASCII)
        assertRejects(staticPack(), files, messagePart = "not a valid WebP")
    }

    // ---- static size and animated rules ----

    @Test
    fun `static sticker over 100 KB is rejected`() {
        assertRejects(
            staticPack(),
            staticFiles(extraBytes = 100 * 1024),
            messagePart = StickerPackValidator.STATIC_LIMIT_BYTES.toString(),
        )
    }

    @Test
    fun `static sticker at exactly 100 KB passes`() {
        // stillVp8 file size = 30 + extraBytes
        validate(staticPack(), staticFiles(extraBytes = StickerPackValidator.STATIC_LIMIT_BYTES - 30))
    }

    @Test
    fun `a single frame file counts as static even with animation flags`() {
        val singleFrame = (1..3).associate { "sticker_$it.webp" to WebpTestData.animatedVp8l(512, 512, listOf(40)) }
        validate(staticPack(), singleFrame)
    }

    @Test
    fun `animated stickers over 500 KB are rejected`() {
        assertRejects(
            animatedPack(),
            animatedFiles(extraBytesPerFrame = 300 * 1024),
            messagePart = StickerPackValidator.ANIMATED_LIMIT_BYTES.toString(),
        )
    }

    @Test
    fun `animated frames must last at least 8 ms`() {
        assertRejects(animatedPack(), animatedFiles(durations = listOf(7, 40)), messagePart = "8 ms")
        validate(animatedPack(), animatedFiles(durations = listOf(8, 40)))
    }

    @Test
    fun `animated total duration must be at most 10 s`() {
        assertRejects(animatedPack(), animatedFiles(durations = listOf(6_000, 6_000)), messagePart = "10000 ms")
        validate(animatedPack(), animatedFiles(durations = listOf(5_000, 5_000)))
    }

    // ---- pack.animated flag ----

    @Test
    fun `pack marked animated with static stickers is rejected`() {
        assertRejects(animatedPack(), staticFiles(), messagePart = "marked animated")
    }

    @Test
    fun `pack with animated stickers but not marked animated is rejected`() {
        assertRejects(staticPack(), animatedFiles(), messagePart = "not marked animated")
    }

    @Test
    fun `mixing static and animated stickers is rejected`() {
        val files = staticFiles().toMutableMap()
        files["sticker_3.webp"] = WebpTestData.animatedVp8l(512, 512, listOf(40, 40))
        assertRejects(staticPack(), files, messagePart = "mixes static and animated")
        assertRejects(animatedPack(), files, messagePart = "mixes static and animated")
    }

    // ---- tray image ----

    @Test
    fun `tray image dimensions must be 24 to 512 px`() {
        validate(staticPack(), staticFiles(), tray = pngHeader(24, 24))
        validate(staticPack(), staticFiles(), tray = pngHeader(512, 512))
        assertRejects(staticPack(), staticFiles(), tray = pngHeader(20, 96), messagePart = "24..512")
        assertRejects(staticPack(), staticFiles(), tray = pngHeader(513, 96), messagePart = "24..512")
    }

    @Test
    fun `tray image over 50 KB is rejected`() {
        assertRejects(
            staticPack(),
            staticFiles(),
            tray = pngHeader(96, 96, extraBytes = 50 * 1024),
            messagePart = StickerPackValidator.TRAY_LIMIT_BYTES.toString(),
        )
    }

    @Test
    fun `tray image must be a PNG`() {
        assertRejects(staticPack(), staticFiles(), tray = ByteArray(40), messagePart = "not a PNG")
        assertRejects(staticPack(), staticFiles(), tray = byteArrayOf(0x89.toByte(), 0x50), messagePart = "too short")
    }

    private fun writeIntBE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
