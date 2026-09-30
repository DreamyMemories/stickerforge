package com.stickerforge.app.core.webp

/**
 * Builds synthetic WebP containers for the unit tests. The container structure
 * is real; the VP8/VP8L bitstreams are dummy byte patterns, because the parser
 * never decodes them.
 */
internal object WebpTestData {

    fun riff(vararg chunks: ByteArray): ByteArray {
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

    /** Wraps [payload] into a RIFF chunk, padding the payload to an even size. */
    fun chunk(fourCC: String, payload: ByteArray): ByteArray {
        val padding = payload.size and 1
        val out = ByteArray(8 + payload.size + padding)
        writeAscii(out, 0, fourCC)
        writeUInt32LE(out, 4, payload.size.toLong())
        payload.copyInto(out, 8)
        return out
    }

    fun vp8xChunk(width: Int, height: Int, animation: Boolean = false, alpha: Boolean = false): ByteArray {
        val payload = ByteArray(10)
        payload[0] = ((if (animation) 0x02 else 0) or (if (alpha) 0x10 else 0)).toByte()
        writeUInt24LE(payload, 4, width - 1)
        writeUInt24LE(payload, 7, height - 1)
        return chunk("VP8X", payload)
    }

    fun animChunk(loopCount: Int = 0): ByteArray {
        val payload = ByteArray(6)
        writeUInt16LE(payload, 4, loopCount)
        return chunk("ANIM", payload)
    }

    /** VP8 lossy frame header with the 0x9d 0x01 0x2a start code and 14-bit dimensions. */
    fun vp8Payload(width: Int, height: Int, extraBytes: Int = 0): ByteArray {
        val payload = ByteArray(10 + extraBytes)
        payload[3] = 0x9D.toByte()
        payload[4] = 0x01
        payload[5] = 0x2A
        writeUInt16LE(payload, 6, width and 0x3FFF)
        writeUInt16LE(payload, 8, height and 0x3FFF)
        return payload
    }

    /** VP8L header: 0x2f signature, 14-bit width-1/height-1, alpha bit at bit 28. */
    fun vp8lPayload(width: Int, height: Int, alpha: Boolean = false, extraBytes: Int = 0): ByteArray {
        val payload = ByteArray(5 + extraBytes)
        payload[0] = 0x2F
        val w = width - 1
        val h = height - 1
        payload[1] = w.toByte()
        payload[2] = ((w ushr 8) or (h shl 6)).toByte()
        payload[3] = (h ushr 2).toByte()
        payload[4] = (((h ushr 10) and 0x0F) or (if (alpha) 0x10 else 0)).toByte()
        return payload
    }

    fun anmfChunk(
        width: Int,
        height: Int,
        durationMs: Int,
        frameChunks: List<ByteArray>,
        x: Int = 0,
        y: Int = 0,
        doNotBlend: Boolean = false,
    ): ByteArray {
        val payload = ByteArray(16 + frameChunks.sumOf { it.size })
        writeUInt24LE(payload, 0, x / 2)
        writeUInt24LE(payload, 3, y / 2)
        writeUInt24LE(payload, 6, width - 1)
        writeUInt24LE(payload, 9, height - 1)
        writeUInt24LE(payload, 12, durationMs)
        payload[15] = if (doNotBlend) 0x02 else 0x00
        var offset = 16
        for (frameChunk in frameChunks) {
            frameChunk.copyInto(payload, offset)
            offset += frameChunk.size
        }
        return chunk("ANMF", payload)
    }

    /** Simple still lossy file, like Bitmap.compress(WEBP_LOSSY) without alpha. */
    fun stillVp8(width: Int, height: Int, extraBytes: Int = 0): ByteArray =
        riff(chunk("VP8 ", vp8Payload(width, height, extraBytes)))

    /** Simple still lossless file. */
    fun stillVp8l(width: Int, height: Int, alpha: Boolean = true, extraBytes: Int = 0): ByteArray =
        riff(chunk("VP8L", vp8lPayload(width, height, alpha, extraBytes)))

    /** Extended still file: VP8X + ALPH + VP8, like a lossy encode that kept alpha. */
    fun extendedStillVp8(width: Int, height: Int, alphaPayloadSize: Int = 4): ByteArray =
        riff(
            vp8xChunk(width, height, animation = false, alpha = true),
            chunk("ALPH", ByteArray(alphaPayloadSize)),
            chunk("VP8 ", vp8Payload(width, height)),
        )

    fun animatedVp8l(
        width: Int,
        height: Int,
        frameDurationsMs: List<Int>,
        alpha: Boolean = true,
        loopCount: Int = 0,
        extraBytesPerFrame: Int = 0,
    ): ByteArray {
        val chunks = ArrayList<ByteArray>(frameDurationsMs.size + 2)
        chunks += vp8xChunk(width, height, animation = true, alpha = alpha)
        chunks += animChunk(loopCount)
        for (duration in frameDurationsMs) {
            chunks += anmfChunk(
                width,
                height,
                duration,
                listOf(chunk("VP8L", vp8lPayload(width, height, alpha, extraBytesPerFrame))),
            )
        }
        return riff(*chunks.toTypedArray())
    }

    fun animatedVp8(
        width: Int,
        height: Int,
        frameDurationsMs: List<Int>,
        loopCount: Int = 0,
        extraBytesPerFrame: Int = 0,
    ): ByteArray {
        val chunks = ArrayList<ByteArray>(frameDurationsMs.size + 2)
        chunks += vp8xChunk(width, height, animation = true, alpha = false)
        chunks += animChunk(loopCount)
        for (duration in frameDurationsMs) {
            chunks += anmfChunk(
                width,
                height,
                duration,
                listOf(chunk("VP8 ", vp8Payload(width, height, extraBytesPerFrame))),
            )
        }
        return riff(*chunks.toTypedArray())
    }

    /** Returns the payload of the first top-level chunk with [fourCC], or null. */
    fun findChunk(bytes: ByteArray, fourCC: String): ByteArray? {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readUInt32LE(bytes, offset + 4)
            if (offset + 8 + size > bytes.size) return null
            if (name == fourCC) return bytes.copyOfRange(offset + 8, (offset + 8 + size).toInt())
            offset = (offset + 8 + size + (size and 1L)).toInt()
        }
        return null
    }

    /** Returns the payload of a sub-chunk of an ANMF payload, or null. */
    fun innerChunk(payload: ByteArray, start: Int, fourCC: String): ByteArray? {
        var offset = start
        while (offset + 8 <= payload.size) {
            val name = String(payload, offset, 4, Charsets.US_ASCII)
            val size = readUInt32LE(payload, offset + 4)
            if (offset + 8 + size > payload.size) return null
            if (name == fourCC) return payload.copyOfRange(offset + 8, (offset + 8 + size).toInt())
            offset = (offset + 8 + size + (size and 1L)).toInt()
        }
        return null
    }

    fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    fun writeAscii(out: ByteArray, offset: Int, value: String) {
        for (index in value.indices) out[offset + index] = value[index].code.toByte()
    }

    fun writeUInt16LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
    }

    fun writeUInt24LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
    }

    fun writeUInt32LE(out: ByteArray, offset: Int, value: Long) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }
}
