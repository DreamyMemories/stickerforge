package com.stickerforge.app.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val DEFAULT_BASE_URL = "https://api.giphy.com/v1/gifs"
private const val ERROR_BODY_LIMIT = 500

private val giphyJson = Json { ignoreUnknownKeys = true }

/**
 * Giphy REST client. See `docs/ARCHITECTURE.md` section "Search".
 *
 * Search and trending share the same request shape; trending simply omits
 * the query. Results without a preview URL are dropped. The key never leaves
 * this class except as the `api_key` query parameter and is never logged.
 *
 */
class GiphyClient(
    private val http: OkHttpClient,
    private val keyProvider: suspend () -> String,
) {
    /** Production endpoint; the secondary constructor overrides it in tests. */
    private var baseUrl: String = DEFAULT_BASE_URL

    /** Test seam: point the client at a mock server instead of Giphy. */
    constructor(
        http: OkHttpClient,
        keyProvider: suspend () -> String,
        baseUrl: String,
    ) : this(http, keyProvider) {
        this.baseUrl = baseUrl
    }

    suspend fun search(query: String, limit: Int, offset: Int): ApiResult<GifPage> =
        load(query = query, limit = limit, offset = offset, path = "search")

    suspend fun trending(limit: Int, offset: Int): ApiResult<GifPage> =
        load(query = null, limit = limit, offset = offset, path = "trending")

    private suspend fun load(query: String?, limit: Int, offset: Int, path: String): ApiResult<GifPage> {
        val key = keyProvider().trim()
        if (key.isEmpty()) return ApiResult.MissingKey

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(path)
            .addQueryParameter("api_key", key)
            .apply { if (query != null) addQueryParameter("q", query) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("rating", "r")
            .addQueryParameter("lang", "en")
            .addQueryParameter("bundle", "messaging_non_clips")
            .build()

        val response = perform(Request.Builder().url(url).get().build())
        return when (response) {
            is Raw.Ok -> parse(response.body, offset)
            is Raw.Http -> ApiResult.HttpError(response.code, response.body.shortBody())
            is Raw.Network -> ApiResult.NetworkError(response.message)
        }
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

    private fun parse(body: String, requestOffset: Int): ApiResult<GifPage> = try {
        ApiResult.Ok(giphyJson.decodeFromString<GiphyResponse>(body).toPage(requestOffset))
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
private data class GiphyResponse(
    val data: List<GiphyItem> = emptyList(),
    val pagination: GiphyPagination? = null,
) {
    fun toPage(requestOffset: Int): GifPage {
        val items = data.mapNotNull { it.toResult() }
        // Cursor advances by the number of items the API returned, skipped
        // items included, so the next request never refetches the same page.
        val nextOffset = requestOffset + data.size
        val next = if (pagination != null && nextOffset < pagination.totalCount) {
            nextOffset.toString()
        } else {
            null
        }
        return GifPage(items = items, next = next)
    }
}

@Serializable
private data class GiphyPagination(
    @SerialName("total_count") val totalCount: Int = 0,
    val count: Int = 0,
    val offset: Int = 0,
)

@Serializable
private data class GiphyItem(
    val id: String = "",
    val title: String = "",
    val images: GiphyImages? = null,
) {
    fun toResult(): GifResult? {
        val images = images ?: return null
        val original = images.original ?: return null
        val preview = images.fixedWidthSmallStill?.url?.trim().orEmpty()
        val gif = original.url.trim()
        if (preview.isEmpty() || gif.isEmpty()) return null
        return GifResult(
            id = id,
            title = title,
            previewUrl = preview,
            gifUrl = gif,
            mp4Url = original.mp4?.trim().takeUnless { it.isNullOrEmpty() },
            width = original.width,
            height = original.height,
            source = GifSource.GIPHY,
        )
    }
}

@Serializable
private data class GiphyImages(
    val original: GiphyImage? = null,
    @SerialName("fixed_width_small_still") val fixedWidthSmallStill: GiphyImage? = null,
)

@Serializable
private data class GiphyImage(
    val url: String = "",
    val mp4: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val width: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class) val height: Int = 0,
)

/** Giphy reports image dimensions as strings; plain numbers are accepted too. */
private object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

private fun String.shortBody(): String {
    val trimmed = trim()
    return if (trimmed.length <= ERROR_BODY_LIMIT) trimmed else trimmed.take(ERROR_BODY_LIMIT)
}
