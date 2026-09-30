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

private const val DEFAULT_BASE_URL = "https://tenor.googleapis.com/v2"
private const val MEDIA_FILTER = "gifpreview,tinygif,gif,mp4,tinymp4"
private const val ERROR_BODY_LIMIT = 500

private val tenorJson = Json { ignoreUnknownKeys = true }

/**
 * Tenor REST client. See `docs/ARCHITECTURE.md` section "Search".
 *
 * Search and featured share the same request shape; featured omits the query.
 * Like the Giphy client, results are dropped when no preview URL is usable.
 *
 * Tenor sometimes rejects `contentfilter=off`; when the server answers 400
 * and names the content filter in the body the request is retried exactly
 * once with `contentfilter=medium`. Any other failure is mapped as-is.
 *
 * The key never leaves this class except as the `key` query parameter and is
 * never logged.
 *
 */
class TenorClient(
    private val http: OkHttpClient,
    private val keyProvider: suspend () -> String,
) {
    /** Production endpoint; the secondary constructor overrides it in tests. */
    private var baseUrl: String = DEFAULT_BASE_URL

    /** Test seam: point the client at a mock server instead of Tenor. */
    constructor(
        http: OkHttpClient,
        keyProvider: suspend () -> String,
        baseUrl: String,
    ) : this(http, keyProvider) {
        this.baseUrl = baseUrl
    }

    suspend fun search(query: String, limit: Int, pos: String?): ApiResult<GifPage> =
        load(query = query, limit = limit, pos = pos, path = "search")

    suspend fun featured(limit: Int, pos: String?): ApiResult<GifPage> =
        load(query = null, limit = limit, pos = pos, path = "featured")

    private suspend fun load(query: String?, limit: Int, pos: String?, path: String): ApiResult<GifPage> {
        val key = keyProvider().trim()
        if (key.isEmpty()) return ApiResult.MissingKey
        return requestPage(key, query, limit, pos, path, contentFilter = "off", allowRetry = true)
    }

    private suspend fun requestPage(
        key: String,
        query: String?,
        limit: Int,
        pos: String?,
        path: String,
        contentFilter: String,
        allowRetry: Boolean,
    ): ApiResult<GifPage> {
        val request = buildRequest(key, query, limit, pos, path, contentFilter)
        return when (val response = perform(request)) {
            is Raw.Ok -> parse(response.body)
            is Raw.Http ->
                if (allowRetry && response.code == 400 && response.body.mentionsContentFilter()) {
                    requestPage(key, query, limit, pos, path, contentFilter = "medium", allowRetry = false)
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
        contentFilter: String,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(path)
            .addQueryParameter("key", key)
            .apply {
                if (query != null) addQueryParameter("q", query)
                if (!pos.isNullOrBlank()) addQueryParameter("pos", pos)
            }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("contentfilter", contentFilter)
            .addQueryParameter("media_filter", MEDIA_FILTER)
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

    private fun parse(body: String): ApiResult<GifPage> = try {
        ApiResult.Ok(tenorJson.decodeFromString<TenorResponse>(body).toPage())
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
private data class TenorResponse(
    val results: List<TenorItem> = emptyList(),
    val next: String? = null,
) {
    fun toPage(): GifPage = GifPage(
        items = results.mapNotNull { it.toResult() },
        next = next?.trim().takeUnless { it.isNullOrEmpty() },
    )
}

@Serializable
private data class TenorItem(
    val id: String = "",
    @SerialName("content_description") val contentDescription: String = "",
    @SerialName("media_formats") val mediaFormats: TenorMediaFormats? = null,
) {
    fun toResult(): GifResult? {
        val formats = mediaFormats ?: return null
        val preview = formats.gifpreview?.url.orNullIfBlank()
            ?: formats.tinygif?.url.orNullIfBlank()
            ?: return null
        val gif = formats.gif?.url.orNullIfBlank() ?: return null
        val mp4 = formats.mp4?.url.orNullIfBlank()
            ?: formats.tinymp4?.url.orNullIfBlank()
        val dims = formats.gif?.dims?.takeIf { it.size >= 2 }
            ?: formats.gifpreview?.dims?.takeIf { it.size >= 2 }
            ?: formats.mp4?.dims?.takeIf { it.size >= 2 }
        return GifResult(
            id = id,
            title = contentDescription,
            previewUrl = preview,
            gifUrl = gif,
            mp4Url = mp4,
            width = dims?.get(0) ?: 0,
            height = dims?.get(1) ?: 0,
            source = GifSource.TENOR,
        )
    }
}

@Serializable
private data class TenorMediaFormats(
    val gifpreview: TenorMedia? = null,
    val tinygif: TenorMedia? = null,
    val gif: TenorMedia? = null,
    val mp4: TenorMedia? = null,
    val tinymp4: TenorMedia? = null,
)

@Serializable
private data class TenorMedia(
    val url: String = "",
    val dims: List<Int> = emptyList(),
)

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String.mentionsContentFilter(): Boolean {
    val lower = lowercase()
    return "contentfilter" in lower || "content_filter" in lower || "safety" in lower
}

private fun String.shortBody(): String {
    val trimmed = trim()
    return if (trimmed.length <= ERROR_BODY_LIMIT) trimmed else trimmed.take(ERROR_BODY_LIMIT)
}
