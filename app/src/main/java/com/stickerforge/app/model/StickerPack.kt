package com.stickerforge.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Sticker(
    val id: String,
    val fileName: String,
    val emojis: List<String> = listOf("\uD83D\uDE00"),
    val accessibilityText: String? = null,
    val animated: Boolean = false,
)

@Serializable
data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String = "StickerForge",
    val trayImageFileName: String = TRAY_FILE_NAME,
    val imageDataVersion: String = "1",
    val animated: Boolean = false,
    val stickers: List<Sticker> = emptyList(),
) {
    val stickerCount: Int get() = stickers.size

    companion object {
        const val TRAY_FILE_NAME = "tray.png"
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
    }
}

@Serializable
data class PackIndex(
    val packs: List<StickerPack> = emptyList(),
)
