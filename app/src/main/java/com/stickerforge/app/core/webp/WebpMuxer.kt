package com.stickerforge.app.core.webp

/** A still WebP produced by Bitmap.compress, plus how long it should show. */
class StillWebp(val bytes: ByteArray, val durationMs: Int)

/**
 * Muxes still WebP frames into an animated WebP container.
 *
 * Android can only encode still frames, so the extended container
 * (`VP8X` + `ANIM` + one `ANMF` per frame) is assembled by hand. Each frame's
 * `ALPH` + `VP8 `/`VP8L` chunks are copied verbatim; only the container is
 * rewritten.
 */
object WebpMuxer {

    private const val VP8X_ANIMATION_FLAG = 0x02
    private const val VP8X_ALPHA_FLAG = 0x10
    private const val MAX_DIMENSION = 0x1000000 // canvas stores width-1 in 24 bits
    private const val MAX_DURATION_MS = 0xFFFFFF
    private const val MAX_LOOP_COUNT = 0xFFFF

    /**
     * @param frames still, single-frame WebP files with their display duration.
     * @param width canvas width; every frame is placed as a full-canvas ANMF.
     * @param height canvas height.
     * @param loopCount 0 = loop forever.
     */
    fun mux(frames: List<StillWebp>, width: Int, height: Int, loopCount: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "At least one frame is required" }
        require(width in 1..MAX_DIMENSION) { "Canvas width out of range: $width" }
        require(height in 1..MAX_DIMENSION) { "Canvas height out of range: $height" }
        require(loopCount in 0..MAX_LOOP_COUNT) { "Loop count out of range: $loopCount" }

        val frameChunks = ArrayList<ByteArray>(frames.size)
        var hasAlpha = false
        frames.forEachIndexed { index, frame ->
            require(frame.durationMs in 1..MAX_DURATION_MS) {
                "Frame $index duration out of range: ${frame.durationMs} ms"
            }
            val info = try {
                WebpInfo.parse(frame.bytes)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Frame $index is not a valid still WebP", error)
            }
            require(info.frameCount == 1) { "Frame $index is already animated; mux input must be still WebP" }
            if (info.hasAlpha) hasAlpha = true
            frameChunks += buildAnmf(frame.bytes, width, height, frame.durationMs)
        }

        val chunks = ArrayList<ByteArray>(frameChunks.size + 2)
        chunks += buildVp8x(width, height, hasAlpha)
        chunks += buildAnim(loopCount)
        chunks += frameChunks
        return riff(chunks)
    }

    private fun buildVp8x(width: Int, height: Int, hasAlpha: Boolean): ByteArray {
        val payload = ByteArray(10)
        var flags = VP8X_ANIMATION_FLAG
        if (hasAlpha) flags = flags or VP8X_ALPHA_FLAG
        payload[0] = flags.toByte()
        writeUInt24LE(payload, 4, width - 1)
        writeUInt24LE(payload, 7, height - 1)
        return chunk("VP8X", payload)
    }

    private fun buildAnim(loopCount: Int): ByteArray {
        val payload = ByteArray(6) // 4-byte background colour stays transparent black
        writeUInt16LE(payload, 4, loopCount)
        return chunk("ANIM", payload)
    }

    private fun buildAnmf(frameBytes: ByteArray, width: Int, height: Int, durationMs: Int): ByteArray {
        val imageChunks = extractImageChunks(frameBytes)
        require(imageChunks.isNotEmpty()) { "Frame contains no VP8/VP8L image data" }
        val payload = ByteArray(16 + imageChunks.sumOf { it.size })
        // Frame x/2 and y/2 stay 0: frames are placed at the canvas origin.
        writeUInt24LE(payload, 6, width - 1)
        writeUInt24LE(payload, 9, height - 1)
        writeUInt24LE(payload, 12, durationMs)
        payload[15] = 0 // bit 1 (do not blend) clear
        var offset = 16
        for (imageChunk in imageChunks) {
            imageChunk.copyInto(payload, offset)
            offset += imageChunk.size
        }
        return chunk("ANMF", payload)
    }

    /** Collects the frame's ALPH + VP8 /VP8L chunks, padding included, verbatim. */
    private fun extractImageChunks(bytes: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>(2)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readUInt32LE(bytes, offset + 4)
            val payloadEnd = offset + 8 + size
            if (payloadEnd > bytes.size) break
            var chunkEnd = payloadEnd + (size and 1L)
            if (chunkEnd > bytes.size) chunkEnd = payloadEnd
            when (fourCC) {
                "ALPH", "VP8 ", "VP8L" -> result += bytes.copyOfRange(offset, chunkEnd.toInt())
            }
            offset = chunkEnd.toInt()
        }
        return result
    }

    private fun riff(chunks: List<ByteArray>): ByteArray {
        val contentSize = 4 + chunks.sumOf { it.size }
        val out = ByteArray(8 + contentSize)
        writeAscii(out, 0, "RIFF")
        writeUInt32LE(out, 4, contentSize.toLong())
        writeAscii(out, 8, "WEBP")
        var offset = 12
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** Wraps a payload into a RIFF chunk, padding the payload to an even size. */
    private fun chunk(fourCC: String, payload: ByteArray): ByteArray {
        val padding = payload.size and 1
        val out = ByteArray(8 + payload.size + padding)
        writeAscii(out, 0, fourCC)
        writeUInt32LE(out, 4, payload.size.toLong())
        payload.copyInto(out, 8)
        return out
    }

    private fun writeAscii(out: ByteArray, offset: Int, value: String) {
        for (index in value.indices) out[offset + index] = value[index].code.toByte()
    }

    private fun writeUInt16LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeUInt24LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
    }

    private fun writeUInt32LE(out: ByteArray, offset: Int, value: Long) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
