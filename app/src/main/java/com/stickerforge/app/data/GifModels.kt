package com.stickerforge.app.data

/** Where a search result came from. */
enum class GifSource { GIPHY, KLIPY }

/**
 * Provider-agnostic search result.
 *
 * @param previewUrl still image, the cheap fallback for grids
 * @param animatedPreviewUrl small animated rendition used in the search grid
 *        (Giphy `fixed_width_small`, Klipy `tinygif`) - null when the provider
 *        offers no animated preview
 * @param gifUrl animated original - may be a GIF, WebP or MP4 depending on provider
 * @param mp4Url animated MP4 when the provider offers one; preferred for
 *        frame extraction because it decodes far faster than GIF
 */
data class GifResult(
    val id: String,
    val title: String,
    val previewUrl: String,
    val animatedPreviewUrl: String? = null,
    val gifUrl: String,
    val mp4Url: String?,
    val width: Int,
    val height: Int,
    val source: GifSource,
)

/** One page of results. [next] is null when there is nothing more to fetch. */
data class GifPage(
    val items: List<GifResult>,
    val next: String?,
)

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data object MissingKey : ApiResult<Nothing>()
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val message: String) : ApiResult<Nothing>()
    data class ParseError(val message: String) : ApiResult<Nothing>()
}
