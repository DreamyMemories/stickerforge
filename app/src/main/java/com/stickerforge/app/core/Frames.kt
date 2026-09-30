package com.stickerforge.app.core

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Frames plus per-frame durations, ready for the editor. */
data class ImageSource(
    val frames: List<Bitmap>,
    val durationsMs: List<Int>,
    val animated: Boolean,
) {
    val width: Int get() = frames.first().width
    val height: Int get() = frames.first().height
}

/**
 * Loads still images, animated GIFs and MP4s into frames.
 *
 * MP4 wins over GIF whenever the provider offers one: it decodes much faster
 * and MediaMetadataRetriever handles rotation for us.
 */
object Frames {

    const val MAX_FRAMES = 40
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_SIDE = 512

    suspend fun fromUri(context: Context, uri: Uri): ImageSource {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAllBytesCapped() }
            ?: throw IOException("Cannot open $uri")
        val mime = context.contentResolver.getType(uri)
        return fromBytes(context, bytes, mime)
    }

    suspend fun fromFile(context: Context, file: File): ImageSource =
        fromBytes(context, file.readBytes(), guessMime(file.name))

    fun fromBitmap(bitmap: Bitmap): ImageSource =
        ImageSource(listOf(bitmap), listOf(0), animated = false)

    fun fromBytes(context: Context, bytes: ByteArray, mimeHint: String? = null): ImageSource {
        if (bytes.size > MAX_BYTES) throw IOException("File is too large")
        return when {
            isMp4(bytes) || mimeHint?.startsWith("video/") == true -> fromMp4(context, bytes)
            isGif(bytes) || mimeHint == "image/gif" -> fromGif(bytes)
            else -> fromStill(bytes)
        }
    }

    // ------------------------------------------------------------------ stills

    private fun fromStill(bytes: ByteArray): ImageSource {
        val bitmap = decodeStill(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        return ImageSource(listOf(bitmap), listOf(0), animated = false)
    }

    private fun decodeStill(source: ImageDecoder.Source): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }

    // --------------------------------------------------------------------- gif

    private fun fromGif(bytes: ByteArray): ImageSource {
        val header = GifHeaderParser().setData(bytes).parseHeader()
        if (header.numFrames <= 1) return fromStill(bytes)

        val decoder = StandardGifDecoder(bitmapProvider, 1)
        decoder.setData(header, bytes)
        val frames = ArrayList<Bitmap>()
        val durations = ArrayList<Int>()
        while (frames.size < MAX_FRAMES) {
            decoder.advance()
            val bitmap = decoder.getNextFrame() ?: break
            val delay = decoder.getNextDelay()
            frames += bitmap
            durations += delay.coerceAtLeast(20)
        }
        decoder.clear()
        if (frames.isEmpty()) return fromStill(bytes)
        return ImageSource(frames, durations, animated = frames.size > 1)
    }

    private val bitmapProvider = object : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)

        override fun obtainIntArray(size: Int): IntArray = IntArray(size)

        override fun release(bitmap: Bitmap) = Unit

        override fun release(bytes: ByteArray) = Unit

        override fun release(ints: IntArray) = Unit
    }

    // --------------------------------------------------------------------- mp4

    private fun fromMp4(context: Context, bytes: ByteArray): ImageSource {
        val temp = File.createTempFile("sf-source", ".mp4", context.cacheDir)
        try {
            FileOutputStream(temp).use { it.write(bytes) }
            return fromMp4File(temp)
        } finally {
            temp.delete()
        }
    }

    fun fromMp4File(file: File): ImageSource {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val reportedFrames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: 0
            if (durationMs <= 0 && reportedFrames <= 0) {
                throw IOException("Not a readable video")
            }
            val estimated = if (reportedFrames > 0 && reportedFrames < 3_000) {
                reportedFrames
            } else {
                (durationMs / 33L).toInt().coerceAtLeast(1)
            }
            val count = estimated.coerceAtMost(MAX_FRAMES).coerceAtLeast(1)
            val frames = ArrayList<Bitmap>(count)
            for (index in 0 until count) {
                val timeUs = if (durationMs > 0) {
                    (durationMs * 1000L * index) / count
                } else {
                    index * 100_000L
                }
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                frames += downscale(frame)
            }
            if (frames.isEmpty()) throw IOException("No video frames could be decoded")
            val perFrame = if (durationMs > 0) {
                (durationMs / frames.size).toInt().coerceAtLeast(20)
            } else {
                100
            }
            return ImageSource(frames, List(frames.size) { perFrame }, animated = frames.size > 1)
        } finally {
            retriever.release()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        bitmap.recycle()
        return scaled
    }

    // ------------------------------------------------------------------ helpers

    private fun isMp4(bytes: ByteArray): Boolean =
        bytes.size > 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()

    private fun guessMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v", "webm" -> "video/mp4"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    private fun java.io.InputStream.readAllBytesCapped(): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BYTES) throw IOException("File is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
