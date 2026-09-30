package com.stickerforge.app.whatsapp

import com.stickerforge.app.model.Sticker
import com.stickerforge.app.model.StickerPack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads and writes the `.wastickers` pack format (zip with `manifest.json` at
 * the root), understood by third party WhatsApp sticker importers.
 *
 * Zip layout (Sticker.ly compatible): `manifest.json` first, then every
 * sticker file and the tray image under their plain file names (no folders).
 * Only pure Kotlin + `java.util.zip`, so it unit-tests on the JVM.
 */
object WastickersFile {

    private const val MANIFEST_FILE_NAME = "manifest.json"

    // Fixed timestamp for every zip entry, so writing the same pack twice
    // produces byte-for-byte identical archives.
    private const val FIXED_ENTRY_TIME_MILLIS = 946_684_800_000L // 2000-01-01T00:00:00Z

    private val json = Json {
        // Always emit every manifest key, including those at their default value.
        encodeDefaults = true
        // Manifests from other tools may carry keys we do not know about.
        ignoreUnknownKeys = true
    }

    data class Imported(
        val pack: StickerPack,
        val stickerBytes: Map<String, ByteArray>,
        val trayBytes: ByteArray,
    )

    fun write(
        pack: StickerPack,
        readSticker: (Sticker) -> ByteArray,
        readTray: () -> ByteArray,
    ): ByteArray {
        val manifest = Manifest(
            identifier = pack.identifier,
            name = pack.name,
            publisher = pack.publisher,
            tray_image_file = pack.trayImageFileName,
            image_data_version = pack.imageDataVersion,
            animated_sticker_pack = pack.animated,
            stickers = pack.stickers.map { sticker ->
                ManifestSticker(
                    image_file = sticker.fileName,
                    emojis = sticker.emojis,
                    accessibility_text = sticker.accessibilityText,
                )
            },
        )

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(entry(MANIFEST_FILE_NAME))
            zip.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (sticker in pack.stickers) {
                zip.putNextEntry(entry(sticker.fileName))
                zip.write(readSticker(sticker))
                zip.closeEntry()
            }

            zip.putNextEntry(entry(pack.trayImageFileName))
            zip.write(readTray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * @throws IllegalArgumentException when the bytes are not a readable zip,
     *   when `manifest.json` is missing or invalid, or when a sticker listed in
     *   the manifest is absent from the zip.
     */
    fun read(zipBytes: ByteArray): Imported {
        val entries = readEntries(zipBytes)
        val manifestBytes = entries[MANIFEST_FILE_NAME]
            ?: throw IllegalArgumentException(
                "Not a .wastickers file: $MANIFEST_FILE_NAME is missing from the zip",
            )
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid .wastickers $MANIFEST_FILE_NAME: ${e.message}", e)
        }

        val stickerBytes = LinkedHashMap<String, ByteArray>(manifest.stickers.size)
        for (manifestSticker in manifest.stickers) {
            val fileName = manifestSticker.image_file
            stickerBytes[fileName] = entries[fileName]
                ?: throw IllegalArgumentException(
                    "Invalid .wastickers file: $MANIFEST_FILE_NAME lists sticker " +
                        "'$fileName' but the zip does not contain it",
                )
        }

        val trayFileName = manifest.tray_image_file.ifBlank { StickerPack.TRAY_FILE_NAME }
        // The tray is not needed by every consumer, so a missing tray file is
        // tolerated and comes back as an empty array.
        val trayBytes = entries[trayFileName] ?: ByteArray(0)

        val pack = StickerPack(
            identifier = manifest.identifier,
            name = manifest.name,
            publisher = manifest.publisher,
            trayImageFileName = trayFileName,
            imageDataVersion = manifest.image_data_version,
            animated = manifest.animated_sticker_pack,
            stickers = manifest.stickers.map { manifestSticker ->
                Sticker(
                    id = manifestSticker.image_file,
                    fileName = manifestSticker.image_file,
                    emojis = manifestSticker.emojis,
                    accessibilityText = manifestSticker.accessibility_text,
                    animated = manifest.animated_sticker_pack,
                )
            },
        )
        return Imported(pack, stickerBytes, trayBytes)
    }

    private fun entry(name: String): ZipEntry = ZipEntry(name).apply {
        time = FIXED_ENTRY_TIME_MILLIS
    }

    private fun readEntries(zipBytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBytes()
                    }
                }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Not a readable zip: ${e.message}", e)
        }
        if (entries.isEmpty() && !looksLikeZip(zipBytes)) {
            throw IllegalArgumentException("Not a readable zip: the data is not a zip archive")
        }
        return entries
    }

    /** True when [bytes] starts with a zip signature (`PK` + local/EOCD/spanning marker). */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    @Serializable
    private data class Manifest(
        val identifier: String = "",
        val name: String = "",
        val publisher: String = "StickerForge",
        val tray_image_file: String = StickerPack.TRAY_FILE_NAME,
        val publisher_email: String = "",
        val publisher_website: String = "",
        val privacy_policy_website: String = "",
        val license_agreement_website: String = "",
        val image_data_version: String = "1",
        val avoid_cache: Boolean = false,
        val animated_sticker_pack: Boolean = false,
        val stickers: List<ManifestSticker> = emptyList(),
    )

    @Serializable
    private data class ManifestSticker(
        val image_file: String = "",
        val emojis: List<String> = listOf("\uD83D\uDE00"),
        val accessibility_text: String? = null,
    )
}
