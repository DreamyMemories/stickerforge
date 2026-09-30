package com.stickerforge.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads provider media (GIF / MP4 / preview) into memory. */
class MediaDownloader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {

    /** @throws IOException when the download fails. */
    suspend fun download(url: String, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty body for $url")
            val length = body.contentLength()
            if (length > maxBytes) throw IOException("File too large (${length / 1024 / 1024} MB)")
            val buffer = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream(if (length > 0) length.toInt() else 256 * 1024)
            var total = 0
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) throw IOException("File too large (over ${maxBytes / 1024 / 1024} MB)")
                    out.write(buffer, 0, read)
                }
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 64 * 1024 * 1024
        const val USER_AGENT = "StickerForge/1.0 (Android)"
    }
}
