package com.stickerforge.app.whatsapp

import com.stickerforge.app.model.Sticker
import com.stickerforge.app.model.StickerPack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WastickersFileTest {

    private val stickers = listOf(
        Sticker(
            id = "s1",
            fileName = "wave.webp",
            emojis = listOf("\uD83D\uDC4B", "\uD83D\uDE00"),
            accessibilityText = "waving hand",
            animated = true,
        ),
        Sticker(
            id = "s2",
            fileName = "heart.webp",
            emojis = listOf("\u2764\uFE0F"),
            accessibilityText = null,
            animated = true,
        ),
        Sticker(
            id = "s3",
            fileName = "cat.webp",
            emojis = listOf("\uD83D\uDC31", "\uD83D\uDC36", "\uD83D\uDC2D"),
            accessibilityText = "cat and friends",
            animated = true,
        ),
    )

    private val pack = StickerPack(
        identifier = "com.stickerforge.test",
        name = "Round Trip Pack",
        publisher = "StickerForge Tests",
        trayImageFileName = "tray.png",
        imageDataVersion = "7",
        animated = true,
        stickers = stickers,
    )

    private val trayBytes = "tray-bytes".toByteArray(Charsets.UTF_8)

    private fun stickerBytes(fileName: String): ByteArray =
        "sticker-bytes:$fileName".toByteArray(Charsets.UTF_8)

    private fun writePack(): ByteArray =
        WastickersFile.write(
            pack,
            readSticker = { stickerBytes(it.fileName) },
            readTray = { trayBytes },
        )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun unzip(zipBytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
            }
        }
        return entries
    }

    @Test
    fun roundTripPreservesPackMetadataStickerOrderAndContent() {
        val imported = WastickersFile.read(writePack())

        assertEquals(pack.identifier, imported.pack.identifier)
        assertEquals(pack.name, imported.pack.name)
        assertEquals(pack.publisher, imported.pack.publisher)
        assertEquals(pack.imageDataVersion, imported.pack.imageDataVersion)
        assertEquals(pack.trayImageFileName, imported.pack.trayImageFileName)
        assertEquals(pack.animated, imported.pack.animated)

        assertEquals(pack.stickers.map { it.fileName }, imported.pack.stickers.map { it.fileName })
        assertEquals(pack.stickers.map { it.emojis }, imported.pack.stickers.map { it.emojis })
        assertEquals(pack.stickers.map { it.accessibilityText }, imported.pack.stickers.map { it.accessibilityText })
        assertEquals(pack.stickers.map { it.animated }, imported.pack.stickers.map { it.animated })

        assertEquals(pack.stickers.map { it.fileName }.toSet(), imported.stickerBytes.keys)
        pack.stickers.forEach { sticker ->
            assertArrayEquals(
                stickerBytes(sticker.fileName),
                imported.stickerBytes.getValue(sticker.fileName),
            )
        }
        assertArrayEquals(trayBytes, imported.trayBytes)
    }

    @Test
    fun writtenZipContainsManifestAndEveryPlainFileAtTheRoot() {
        val zip = writePack()
        val entries = unzip(zip)

        assertEquals(
            listOf("manifest.json", "wave.webp", "heart.webp", "cat.webp", "tray.png"),
            entries.keys.toList(),
        )
        entries.keys.forEach { name ->
            assertFalse("entry '$name' must be at the zip root", name.contains('/'))
        }

        // Same pack in, same bytes out.
        assertArrayEquals(zip, writePack())
    }

    @Test
    fun writtenManifestHasExactlyTheSpecifiedKeysAndValues() {
        val manifest = Json
            .parseToJsonElement(unzip(writePack()).getValue("manifest.json").toString(Charsets.UTF_8))
            .jsonObject

        assertEquals(
            setOf(
                "identifier", "name", "publisher", "tray_image_file",
                "publisher_email", "publisher_website", "privacy_policy_website",
                "license_agreement_website", "image_data_version", "avoid_cache",
                "animated_sticker_pack", "stickers",
            ),
            manifest.keys,
        )
        assertEquals(pack.identifier, manifest.getValue("identifier").jsonPrimitive.content)
        assertEquals(pack.name, manifest.getValue("name").jsonPrimitive.content)
        assertEquals(pack.publisher, manifest.getValue("publisher").jsonPrimitive.content)
        assertEquals(pack.trayImageFileName, manifest.getValue("tray_image_file").jsonPrimitive.content)
        assertEquals(pack.imageDataVersion, manifest.getValue("image_data_version").jsonPrimitive.content)
        assertFalse(manifest.getValue("avoid_cache").jsonPrimitive.boolean)
        assertTrue(manifest.getValue("animated_sticker_pack").jsonPrimitive.boolean)

        val manifestStickers = manifest.getValue("stickers").jsonArray
        assertEquals(stickers.size, manifestStickers.size)
        assertEquals(
            pack.stickers.map { it.fileName },
            manifestStickers.map { it.jsonObject.getValue("image_file").jsonPrimitive.content },
        )
        assertEquals(
            pack.stickers.map { it.emojis },
            manifestStickers.map { element ->
                element.jsonObject.getValue("emojis").jsonArray.map { it.jsonPrimitive.content }
            },
        )
        assertEquals(
            pack.stickers.map { it.accessibilityText },
            manifestStickers.map { element ->
                val text = element.jsonObject.getValue("accessibility_text")
                if (text is JsonNull) null else text.jsonPrimitive.content
            },
        )
        assertEquals(
            setOf("image_file", "emojis", "accessibility_text"),
            manifestStickers.first().jsonObject.keys,
        )
    }

    @Test
    fun missingManifestThrowsIllegalArgumentException() {
        val zip = zipOf("wave.webp" to stickerBytes("wave.webp"), "tray.png" to trayBytes)

        val error = assertThrows(IllegalArgumentException::class.java) { WastickersFile.read(zip) }

        assertTrue(error.message.orEmpty().contains("manifest.json"))
    }

    @Test
    fun manifestListingAStickerMissingFromTheZipThrowsIllegalArgumentException() {
        val manifest = """
            {
              "identifier": "com.stickerforge.broken",
              "name": "Broken Pack",
              "publisher": "StickerForge Tests",
              "tray_image_file": "tray.png",
              "image_data_version": "1",
              "stickers": [
                {"image_file": "present.webp", "emojis": ["\uD83D\uDE00"], "accessibility_text": null},
                {"image_file": "ghost.webp", "emojis": ["\uD83D\uDC7B"], "accessibility_text": null}
              ]
            }
        """.trimIndent()
        val zip = zipOf(
            "manifest.json" to manifest.toByteArray(Charsets.UTF_8),
            "present.webp" to stickerBytes("present.webp"),
            "tray.png" to trayBytes,
        )

        val error = assertThrows(IllegalArgumentException::class.java) { WastickersFile.read(zip) }

        assertTrue(error.message.orEmpty().contains("ghost.webp"))
    }

    @Test
    fun unknownManifestKeysAreIgnored() {
        val manifest = """
            {
              "identifier": "com.stickerforge.unknown",
              "name": "Unknown Keys Pack",
              "publisher": "StickerForge Tests",
              "tray_image_file": "tray.png",
              "image_data_version": "2",
              "animated_sticker_pack": true,
              "avoid_cache": true,
              "future_top_level_key": {"a": [1, 2, 3]},
              "stickers": [
                {
                  "image_file": "one.webp",
                  "emojis": ["\uD83D\uDE00"],
                  "accessibility_text": "hello",
                  "future_sticker_key": "ignored"
                }
              ]
            }
        """.trimIndent()
        val zip = zipOf(
            "manifest.json" to manifest.toByteArray(Charsets.UTF_8),
            "one.webp" to stickerBytes("one.webp"),
            "tray.png" to trayBytes,
        )

        val imported = WastickersFile.read(zip)

        assertEquals("com.stickerforge.unknown", imported.pack.identifier)
        assertEquals("Unknown Keys Pack", imported.pack.name)
        assertEquals("2", imported.pack.imageDataVersion)
        assertTrue(imported.pack.animated)
        val sticker = imported.pack.stickers.single()
        assertEquals("one.webp", sticker.fileName)
        assertEquals(listOf("\uD83D\uDE00"), sticker.emojis)
        assertEquals("hello", sticker.accessibilityText)
        assertArrayEquals(stickerBytes("one.webp"), imported.stickerBytes.getValue("one.webp"))
        assertArrayEquals(trayBytes, imported.trayBytes)
    }

    @Test
    fun missingOptionalFieldsAndTrayAreTolerated() {
        val manifest =
            """{"identifier":"com.stickerforge.minimal","name":"Minimal","stickers":[{"image_file":"one.webp"}]}"""
        val zip = zipOf(
            "manifest.json" to manifest.toByteArray(Charsets.UTF_8),
            "one.webp" to stickerBytes("one.webp"),
        )

        val imported = WastickersFile.read(zip)

        assertEquals("StickerForge", imported.pack.publisher)
        assertEquals("1", imported.pack.imageDataVersion)
        assertEquals(StickerPack.TRAY_FILE_NAME, imported.pack.trayImageFileName)
        assertFalse(imported.pack.animated)

        val sticker = imported.pack.stickers.single()
        assertEquals(listOf("\uD83D\uDE00"), sticker.emojis)
        assertNull(sticker.accessibilityText)
        assertFalse(sticker.animated)

        assertTrue(imported.trayBytes.isEmpty())
    }

    @Test
    fun nonZipBytesThrowIllegalArgumentException() {
        val zip = "definitely not a zip".toByteArray(Charsets.UTF_8)

        val error = assertThrows(IllegalArgumentException::class.java) { WastickersFile.read(zip) }

        assertTrue(error.message.orEmpty().contains("zip"))
    }
}
