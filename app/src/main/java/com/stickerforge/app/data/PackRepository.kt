package com.stickerforge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stickerforge.app.model.PackIndex
import com.stickerforge.app.model.Sticker
import com.stickerforge.app.model.StickerPack
import com.stickerforge.app.whatsapp.WastickersFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Packs live in app-private storage:
 *
 * ```
 * files/stickerforge/packs.json                  index of all packs
 * files/stickerforge/packs/<identifier>/<id>.webp
 * files/stickerforge/packs/<identifier>/tray.png
 * ```
 *
 * Every mutation bumps `imageDataVersion`, which is what tells WhatsApp that a
 * pack it already imported has new content.
 */
class PackRepository(context: Context) {

    private val rootDir = File(context.filesDir, "stickerforge")
    private val packsRoot = File(rootDir, "packs").apply { mkdirs() }
    private val indexFile = File(rootDir, "packs.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun listPacks(): List<StickerPack> = readIndex().packs

    @Synchronized
    fun getPack(identifier: String): StickerPack? =
        readIndex().packs.firstOrNull { it.identifier == identifier }

    @Synchronized
    fun createPack(name: String, publisher: String = DEFAULT_PUBLISHER): StickerPack {
        val identifier = uniqueIdentifier(name)
        packDir(identifier).mkdirs()
        val pack = StickerPack(
            identifier = identifier,
            name = name.trim().ifBlank { "Untitled pack" }.take(128),
            publisher = publisher.trim().ifBlank { DEFAULT_PUBLISHER }.take(128),
        )
        mutate { it + pack }
        return pack
    }

    @Synchronized
    fun renamePack(identifier: String, newName: String) {
        updatePack(identifier) { it.copy(name = newName.trim().take(128)) }
    }

    @Synchronized
    fun deletePack(identifier: String) {
        packDir(identifier).deleteRecursively()
        mutate { packs -> packs.filterNot { it.identifier == identifier } }
    }

    /** True when [animated] is compatible with the stickers already in the pack. */
    fun canAdd(identifier: String, animated: Boolean): Boolean {
        val pack = getPack(identifier) ?: return false
        return pack.stickers.isEmpty() || pack.animated == animated
    }

    /**
     * Stores a finished 512x512 WebP sticker.
     *
     * @throws IllegalStateException when the sticker would mix static and
     *         animated stickers in one pack.
     */
    @Synchronized
    fun addSticker(
        identifier: String,
        webpBytes: ByteArray,
        emojis: List<String> = listOf("\uD83D\uDE00"),
        animated: Boolean = false,
        accessibilityText: String? = null,
    ): Sticker {
        val pack = getPack(identifier) ?: throw IllegalStateException("Unknown pack $identifier")
        if (!canAdd(identifier, animated)) {
            throw IllegalStateException("A pack cannot mix static and animated stickers")
        }
        val stickerId = UUID.randomUUID().toString().replace("-", "").take(16)
        val fileName = "$stickerId.webp"
        File(packDir(identifier), fileName).writeBytes(webpBytes)
        val sticker = Sticker(
            id = stickerId,
            fileName = fileName,
            emojis = emojis.ifEmpty { listOf("\uD83D\uDE00") }.take(3),
            accessibilityText = accessibilityText?.take(255),
            animated = animated,
        )
        updatePack(identifier) { current ->
            current.copy(
                // The flag describes the pack's first sticker and must survive
                // every later add; clearing it here would make an animated pack
                // look static and reject the next sticker.
                animated = if (current.stickers.isEmpty()) animated else current.animated,
                stickers = current.stickers + sticker,
                imageDataVersion = bumpVersion(current.imageDataVersion),
            )
        }
        ensureTray(identifier)
        return sticker
    }

    @Synchronized
    fun removeSticker(identifier: String, stickerId: String) {
        val pack = getPack(identifier) ?: return
        val sticker = pack.stickers.firstOrNull { it.id == stickerId } ?: return
        File(packDir(identifier), sticker.fileName).delete()
        updatePack(identifier) { current ->
            val remaining = current.stickers.filterNot { it.id == stickerId }
            current.copy(
                stickers = remaining,
                animated = if (remaining.isEmpty()) false else current.animated,
                imageDataVersion = bumpVersion(current.imageDataVersion),
            )
        }
        ensureTray(identifier)
    }

    @Synchronized
    fun setStickerEmojis(identifier: String, stickerId: String, emojis: List<String>) {
        updatePack(identifier) { pack ->
            pack.copy(
                stickers = pack.stickers.map { sticker ->
                    if (sticker.id == stickerId) sticker.copy(emojis = emojis.take(3)) else sticker
                },
                imageDataVersion = bumpVersion(pack.imageDataVersion),
            )
        }
    }

    @Synchronized
    fun moveSticker(identifier: String, from: Int, to: Int) {
        updatePack(identifier) { pack ->
            val list = pack.stickers.toMutableList()
            if (from !in list.indices || to !in list.indices) return@updatePack pack
            val item = list.removeAt(from)
            list.add(to, item)
            pack.copy(stickers = list, imageDataVersion = bumpVersion(pack.imageDataVersion))
        }
    }

    fun packDir(identifier: String): File = File(packsRoot, identifier)

    fun stickerFile(identifier: String, fileName: String): File? {
        if (fileName.contains('/') || fileName.contains('\\')) return null
        val file = File(packDir(identifier), fileName)
        return if (file.isFile) file else null
    }

    fun stickerBytes(identifier: String, fileName: String): ByteArray? =
        stickerFile(identifier, fileName)?.readBytes()

    fun trayFile(identifier: String): File? {
        val file = File(packDir(identifier), StickerPack.TRAY_FILE_NAME)
        return if (file.isFile) file else null
    }

    fun trayBytes(identifier: String): ByteArray? = trayFile(identifier)?.readBytes()

    /** Regenerates `tray.png` (96x96) from the first sticker when it is missing. */
    @Synchronized
    fun ensureTray(identifier: String): ByteArray? {
        trayFile(identifier)?.let { if (it.isFile && it.length() > 0) return it.readBytes() }
        return generateTray(identifier)
    }

    @Synchronized
    fun regenerateTray(identifier: String): ByteArray? {
        File(packDir(identifier), StickerPack.TRAY_FILE_NAME).delete()
        return generateTray(identifier)
    }

    private fun generateTray(identifier: String): ByteArray? {
        val sticker = getPack(identifier)?.stickers?.firstOrNull() ?: return null
        val bytes = stickerBytes(identifier, sticker.fileName) ?: return null
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, 96, 96, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        val trayBytes = out.toByteArray()
        File(packDir(identifier), StickerPack.TRAY_FILE_NAME).writeBytes(trayBytes)
        return trayBytes
    }

    /** Serializes a pack to the `.wastickers` format for sharing/importing elsewhere. */
    fun writeWastickers(identifier: String): ByteArray {
        val pack = getPack(identifier) ?: throw IllegalArgumentException("Unknown pack $identifier")
        return WastickersFile.write(
            pack = pack,
            readSticker = { sticker -> stickerBytes(identifier, sticker.fileName) ?: ByteArray(0) },
            readTray = { ensureTray(identifier) ?: ByteArray(0) },
        )
    }

    /** Adds an imported `.wastickers` pack, renaming when the identifier is taken. */
    @Synchronized
    fun importPack(imported: WastickersFile.Imported, publisherFallback: String = DEFAULT_PUBLISHER): StickerPack {
        val source = imported.pack
        val identifier = uniqueIdentifier(source.name)
        val dir = packDir(identifier).apply { mkdirs() }
        imported.stickerBytes.forEach { (fileName, bytes) ->
            File(dir, fileName).writeBytes(bytes)
        }
        File(dir, StickerPack.TRAY_FILE_NAME).writeBytes(imported.trayBytes)
        val pack = source.copy(
            identifier = identifier,
            publisher = source.publisher.ifBlank { publisherFallback }.take(128),
            trayImageFileName = StickerPack.TRAY_FILE_NAME,
            imageDataVersion = bumpVersion(source.imageDataVersion),
        )
        mutate { it + pack }
        return pack
    }

    // ---------------------------------------------------------------- helpers

    private fun packFor(identifier: String): StickerPack? = getPack(identifier)

    @Synchronized
    private fun updatePack(identifier: String, transform: (StickerPack) -> StickerPack) {
        val packs = readIndex().packs.toMutableList()
        val index = packs.indexOfFirst { it.identifier == identifier }
        if (index < 0) return
        packs[index] = transform(packs[index])
        writeIndex(PackIndex(packs))
    }

    @Synchronized
    private fun mutate(transform: (List<StickerPack>) -> List<StickerPack>) {
        writeIndex(PackIndex(transform(readIndex().packs)))
    }

    private fun readIndex(): PackIndex = try {
        if (indexFile.isFile) {
            val stored = json.decodeFromString(PackIndex.serializer(), indexFile.readText())
            // Repair the pack flag if it ever disagrees with the stickers it
            // holds (older builds cleared it on every add after the first).
            PackIndex(
                stored.packs.map { pack ->
                    val derived = pack.stickers.isNotEmpty() && pack.stickers.all { it.animated }
                    if (pack.animated == derived) pack else pack.copy(animated = derived)
                },
            )
        } else {
            PackIndex()
        }
    } catch (error: Exception) {
        PackIndex()
    }

    private fun writeIndex(index: PackIndex) {
        rootDir.mkdirs()
        indexFile.writeText(json.encodeToString(PackIndex.serializer(), index))
    }

    private fun uniqueIdentifier(name: String): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9 _\\-.,']"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "pack" }
            .take(64)
        var candidate = base
        var counter = 2
        val taken = listPacks().map { it.identifier }.toSet()
        while (candidate in taken) {
            candidate = "${base}_$counter"
            counter++
        }
        return candidate
    }

    private fun bumpVersion(version: String): String {
        val asLong = version.toLongOrNull() ?: return "1"
        return (asLong + 1).toString()
    }

    private companion object {
        const val DEFAULT_PUBLISHER = "StickerForge"
    }
}
