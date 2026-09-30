package com.stickerforge.app.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Provider-agnostic entry point used by the UI. Dispatches to the two REST
 * clients and keeps a single HTTP stack.
 */
class GifSearchService(private val apiKeys: ApiKeys) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val giphy = GiphyClient(http) { apiKeys.giphyKeyOnce() }
    private val tenor = TenorClient(http) { apiKeys.tenorKeyOnce() }

    suspend fun search(
        source: GifSource,
        query: String,
        cursor: String? = null,
        limit: Int = 30,
    ): ApiResult<GifPage> = when (source) {
        GifSource.GIPHY -> giphy.search(query, limit, cursor?.toIntOrNull() ?: 0)
        GifSource.TENOR -> tenor.search(query, limit, cursor)
    }

    suspend fun trending(
        source: GifSource,
        cursor: String? = null,
        limit: Int = 30,
    ): ApiResult<GifPage> = when (source) {
        GifSource.GIPHY -> giphy.trending(limit, cursor?.toIntOrNull() ?: 0)
        GifSource.TENOR -> tenor.featured(limit, cursor)
    }
}
