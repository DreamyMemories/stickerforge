package com.stickerforge.app.whatsapp

import com.stickerforge.app.core.webp.WebpInfo
import com.stickerforge.app.model.Sticker
import com.stickerforge.app.model.StickerPack

class StickerPackValidationException(message: String) : IllegalStateException(message)

/**
 * Enforces the WhatsApp sticker pack rules before a pack is exported or added
 * to WhatsApp. Rules ported from the official sample (BSD, see NOTICE.md).
 *
 * [validate] throws [StickerPackValidationException] on the first violation,
 * with a message naming the offending field, sticker or tray image.
 */
object StickerPackValidator {

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val STATIC_LIMIT_BYTES = 100 * 1024
    const val ANIMATED_LIMIT_BYTES = 500 * 1024
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96
    const val TRAY_LIMIT_BYTES = 50 * 1024

    private const val MAX_METADATA_LENGTH = 128
    private const val MIN_EMOJIS = 1
    private const val MAX_EMOJIS = 3
    private const val MIN_FRAME_DURATION_MS = 8
    private const val MAX_TOTAL_DURATION_MS = 10_000
    private const val MIN_TRAY_SIZE = 24
    private const val MAX_TRAY_SIZE = 512

    private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9_\\-.,' ]+$")
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )

    /** @throws StickerPackValidationException on the first rule violation. */
    fun validate(
        pack: StickerPack,
        readSticker: (Sticker) -> ByteArray,
        readTray: () -> ByteArray,
    ) {
        validateMetadata(pack)

        if (pack.stickers.size !in MIN_STICKERS..MAX_STICKERS) {
            throw StickerPackValidationException(
                "Pack '${pack.identifier}' must contain $MIN_STICKERS..$MAX_STICKERS stickers, " +
                    "found ${pack.stickers.size}."
            )
        }

        var hasStatic = false
        var hasAnimated = false
        for (sticker in pack.stickers) {
            validateStickerMetadata(sticker)
            val animated = validateStickerFile(sticker, readSticker(sticker))
            if (animated) hasAnimated = true else hasStatic = true
            if (hasStatic && hasAnimated) {
                throw StickerPackValidationException(
                    "Pack '${pack.identifier}' mixes static and animated stickers; " +
                        "every sticker in a pack must be the same kind."
                )
            }
        }

        if (pack.animated != hasAnimated) {
            throw StickerPackValidationException(
                if (hasAnimated) {
                    "Pack '${pack.identifier}' contains animated stickers but is not marked animated."
                } else {
                    "Pack '${pack.identifier}' is marked animated but all of its stickers are static."
                }
            )
        }

        validateTray(readTray())
    }

    private fun validateMetadata(pack: StickerPack) {
        validateTextField("identifier", pack.identifier)
        validateTextField("name", pack.name)
        validateTextField("publisher", pack.publisher)

        if (!IDENTIFIER_PATTERN.matches(pack.identifier)) {
            throw StickerPackValidationException(
                "Pack identifier '${pack.identifier}' may only contain letters, digits, " +
                    "_ - . , ' and spaces."
            )
        }
        if (pack.identifier.contains("..")) {
            throw StickerPackValidationException(
                "Pack identifier '${pack.identifier}' must not contain '..'."
            )
        }
    }

    private fun validateTextField(field: String, value: String) {
        if (value.isBlank()) {
            throw StickerPackValidationException("Pack $field must not be blank.")
        }
        if (value.length > MAX_METADATA_LENGTH) {
            throw StickerPackValidationException(
                "Pack $field must be at most $MAX_METADATA_LENGTH characters, found ${value.length}."
            )
        }
    }

    private fun validateStickerMetadata(sticker: Sticker) {
        if (sticker.fileName.isBlank()) {
            throw StickerPackValidationException("Sticker '${sticker.id}' has a blank file name.")
        }
        if (sticker.emojis.size !in MIN_EMOJIS..MAX_EMOJIS) {
            throw StickerPackValidationException(
                "Sticker '${sticker.fileName}' must carry $MIN_EMOJIS..$MAX_EMOJIS emojis, " +
                    "found ${sticker.emojis.size}."
            )
        }
    }

    /** @return true when the file is an animated WebP. */
    private fun validateStickerFile(sticker: Sticker, bytes: ByteArray): Boolean {
        val info = try {
            WebpInfo.parse(bytes)
        } catch (error: IllegalArgumentException) {
            throw StickerPackValidationException(
                "Sticker '${sticker.fileName}' is not a valid WebP: ${error.message}."
            )
        }

        if (info.width != STICKER_SIZE || info.height != STICKER_SIZE) {
            throw StickerPackValidationException(
                "Sticker '${sticker.fileName}' must be ${STICKER_SIZE}x$STICKER_SIZE, " +
                    "found ${info.width}x${info.height}."
            )
        }

        val animated = info.frameCount > 1
        if (animated) {
            if (bytes.size > ANIMATED_LIMIT_BYTES) {
                throw StickerPackValidationException(
                    "Animated sticker '${sticker.fileName}' is ${bytes.size} bytes; " +
                        "the limit is $ANIMATED_LIMIT_BYTES."
                )
            }
            val shortFrame = info.frameDurationsMs.firstOrNull { it < MIN_FRAME_DURATION_MS }
            if (shortFrame != null) {
                throw StickerPackValidationException(
                    "Sticker '${sticker.fileName}' has a ${shortFrame} ms frame; animated frames " +
                        "must last at least $MIN_FRAME_DURATION_MS ms."
                )
            }
            if (info.totalDurationMs > MAX_TOTAL_DURATION_MS) {
                throw StickerPackValidationException(
                    "Sticker '${sticker.fileName}' runs for ${info.totalDurationMs} ms; " +
                        "the limit is $MAX_TOTAL_DURATION_MS ms."
                )
            }
        } else if (bytes.size > STATIC_LIMIT_BYTES) {
            throw StickerPackValidationException(
                "Static sticker '${sticker.fileName}' is ${bytes.size} bytes; " +
                    "the limit is $STATIC_LIMIT_BYTES."
            )
        }
        return animated
    }

    private fun validateTray(bytes: ByteArray) {
        if (bytes.size > TRAY_LIMIT_BYTES) {
            throw StickerPackValidationException(
                "Tray image is ${bytes.size} bytes; the limit is $TRAY_LIMIT_BYTES."
            )
        }
        if (bytes.size < 24) {
            throw StickerPackValidationException("Tray image is not a valid PNG: too short.")
        }
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                throw StickerPackValidationException("Tray image is not a PNG.")
            }
        }
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") {
            throw StickerPackValidationException("Tray image is not a valid PNG: missing IHDR header.")
        }
        val width = readIntBE(bytes, 16)
        val height = readIntBE(bytes, 20)
        if (width !in MIN_TRAY_SIZE..MAX_TRAY_SIZE || height !in MIN_TRAY_SIZE..MAX_TRAY_SIZE) {
            throw StickerPackValidationException(
                "Tray image must be $MIN_TRAY_SIZE..$MAX_TRAY_SIZE px, found ${width}x$height."
            )
        }
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
