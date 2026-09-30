package com.stickerforge.app

import android.content.Context
import com.stickerforge.app.core.AiCutout
import com.stickerforge.app.data.ApiKeys
import com.stickerforge.app.data.GifSearchService
import com.stickerforge.app.data.MediaDownloader
import com.stickerforge.app.data.PackRepository

/**
 * Tiny hand-rolled service locator. Keeps the app dependency-light: no DI
 * framework needed for a single-module personal app.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val apiKeys: ApiKeys by lazy { ApiKeys(appContext) }

    val packRepository: PackRepository by lazy { PackRepository(appContext) }

    val gifSearch: GifSearchService by lazy { GifSearchService(apiKeys) }

    val downloader: MediaDownloader by lazy { MediaDownloader() }

    val aiCutout: AiCutout by lazy { AiCutout() }
}
