package com.stickerforge.app.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val DEFAULT_BASE_URL = "https://api.klipy.com/v2"
private const val CLIENT_KEY = "stickerforge"
private const val ERROR_BODY_LIMIT = 500

private val klipyJson = Json { ignoreUnknownKeys = true }

/**
 * KLIPY client. KLIPY replaced Tenor (Google shut the Tenor API down on
 * 30 June 2026) and exposes Tenor-compatible v2 endpoints, so this is
 * essentially "Tenor with a different host" - see `docs/ARCHITECTURE.md`.
 *
 * Unfiltered by default: KLIPY's `contentfilter` defaults to `off`, which is
 * what we ask for. If a key is not allowed to request that, the call is
 * retried once at `medium`.
 *
 * `searchfilter=sticker` returns transparent stickers (webp/png instead of
 * gif/mp4), which is ideal for a sticker maker - those are offered in the UI
 * as the "Stickers only" mode.
 */
class KlipyClient(
    private val http: OkHttpClient,
    private val keyProvider: suspend () -> String,
) {
    /** Production endpoint; the secondary constructor overrides it in tests. */
    private var baseUrl: String = DEFAULT_BASE_URL

    /** Test seam: point the client at a mock server instead of KLIPY. */
    constructor(
        http: OkHttpClient,
        keyProvider: suspend () -> String,
        baseUrl: String,
    ) : this(http, keyProvider) {
        this.baseUrl = baseUrl
    }

    suspend fun search(
        query: String,
        limit: Int,
        pos: String?,
        stickerOnly: Boolean = false,
    ): ApiResult<GifPage> = load(query, limit, pos, "search", stickerOnly)

    suspend fun featured(
        limit: Int,
        pos: String?,
        stickerOnly: Boolean = false,
    ): ApiResult<GifPage> = load(null, limit, pos, "featured", stickerOnly)

    private suspend fun load(
        query: String?,
        limit: Int,
        pos: String?,
        path: String,
        stickerOnly: Boolean,
    ): ApiResult<GifPage> {
        val key = keyProvider().trim()
        if (key.isEmpty()) return ApiResult.MissingKey
        return requestPage(key, query, limit, pos, path, stickerOnly, contentFilter = "off", allowRetry = true)
    }

    private suspend fun requestPage(
        key: String,
        query: String?,
        limit: Int,
        pos: String?,
        path: String,
        stickerOnly: Boolean,
        contentFilter: String,
        allowRetry: Boolean,
    ): ApiResult<GifPage> {
        val request = buildRequest(key, query, limit, pos, path, stickerOnly, contentFilter)
        return when (val response = perform(request)) {
            is Raw.Ok -> parse(response.body, stickerOnly)
            is Raw.Http ->
                if (allowRetry && response.code == 400 && response.body.mentionsContentFilter()) {
                    requestPage(key, query, limit, pos, path, stickerOnly, "medium", allowRetry = false)
                } else {
                    ApiResult.HttpError(response.code, response.body.shortBody())
                }
            is Raw.Network -> ApiResult.NetworkError(response.message)
        }
    }

    private fun buildRequest(
        key: String,
        query: String?,
        limit: Int,
        pos: String?,
        path: String,
        stickerOnly: Boolean,
        contentFilter: String,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(path)
            .addQueryParameter("key", key)
            .addQueryParameter("client_key", CLIENT_KEY)
            .apply {
                if (query != null) addQueryParameter("q", query)
                if (!pos.isNullOrBlank()) addQueryParameter("pos", pos)
                if (stickerOnly) addQueryParameter("searchfilter", "sticker")
            }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("contentfilter", contentFilter)
            .addQueryParameter("locale", "en_US")
            .build()
        return Request.Builder().url(url).get().build()
    }

    private suspend fun perform(request: Request): Raw = try {
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) Raw.Ok(body) else Raw.Http(response.code, body)
            }
        }
    } catch (e: IOException) {
        Raw.Network(e.message ?: e.javaClass.simpleName)
    }

    private fun parse(body: String, stickerOnly: Boolean): ApiResult<GifPage> = try {
        ApiResult.Ok(klipyJson.decodeFromString<KlipyResponse>(body).toPage(stickerOnly))
    } catch (e: SerializationException) {
        ApiResult.ParseError(e.message ?: "malformed JSON")
    } catch (e: IllegalArgumentException) {
        ApiResult.ParseError(e.message ?: "malformed JSON")
    }

    private sealed interface Raw {
        data class Ok(val body: String) : Raw
        data class Http(val code: Int, val body: String) : Raw
        data class Network(val message: String) : Raw
    }
}

@Serializable
private data class KlipyResponse(
    val results: List<KlipyItem> = emptyList(),
    val next: String? = null,
) {
    fun toPage(stickerOnly: Boolean): GifPage = GifPage(
        items = results.mapNotNull { it.toResult(stickerOnly) },
        next = next?.trim().takeUnless { it.isNullOrEmpty() },
    )
}

@Serializable
private data class KlipyItem(
    val id: String = "",
    @SerialName("content_description") val contentDescription: String = "",
    val title: String = "",
    @SerialName("media_formats") val mediaFormats: Map<String, KlipyMedia> = emptyMap(),
) {
    fun toResult(stickerOnly: Boolean): GifResult? {
        if (mediaFormats.isEmpty()) return null
        val preview = mediaFormats.firstOf(
            "gifpreview", "tinygif", "nanogif",
            "webppreview_transparent", "tinywebppreview_transparent",
            "tinywebp_transparent", "tinygif_transparent", "tinywebp",
            "webp_transparent", "webp", "png",
            "gif", "tinygif",
        ) ?: return null
        // Sticker mode exists to deliver transparency, so transparent formats
        // win there even though mp4 is normally the cheapest to decode.
        val animated = if (stickerOnly) {
            mediaFormats.firstOf(
                "gif_transparent", "tinygif_transparent",
                "webp_transparent", "tinywebp_transparent",
                "gif", "tinygif",
                "mp4", "tinymp4", "webp",
            )
        } else {
            mediaFormats.firstOf(
                "mp4", "tinymp4",
                "gif", "tinygif", "gif_transparent", "tinygif_transparent",
                "webp_transparent", "webp", "png",
            )
        }
        val playable = animated ?: preview
        val dims = listOf(playable, animated, preview)
            .firstNotNullOfOrNull { media -> media?.dims?.takeIf { it.size >= 2 } }
        return GifResult(
            id = id,
            title = contentDescription.ifBlank { title },
            previewUrl = preview.url,
            gifUrl = playable.url,
            mp4Url = mediaFormats.firstOf("mp4", "tinymp4")?.url,
            width = dims?.get(0) ?: 0,
            height = dims?.get(1) ?: 0,
            source = GifSource.KLIPY,
        )
    }
}

@Serializable
private data class KlipyMedia(
    val url: String = "",
    val dims: List<Int> = emptyList(),
)

/** KLIPY's media_formats is an open dictionary, so look formats up by name. */
private fun Map<String, KlipyMedia>.firstOf(vararg keys: String): KlipyMedia? =
    keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.url.isNotBlank() } }

private fun String.mentionsContentFilter(): Boolean {
    val lower = lowercase()
    return "contentfilter" in lower || "content_filter" in lower || "safety" in lower
}

private fun String.shortBody(): String {
    val trimmed = trim()
    return if (trimmed.length <= ERROR_BODY_LIMIT) trimmed else trimmed.take(ERROR_BODY_LIMIT)
}
