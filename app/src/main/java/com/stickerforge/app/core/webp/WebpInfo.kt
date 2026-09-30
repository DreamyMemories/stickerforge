package com.stickerforge.app.core.webp

/**
 * Metadata of a (possibly animated) WebP file, read straight from the RIFF
 * container. Used by the validator and by the exporter's size search.
 *
 * [parse] reads container metadata only - it never decodes the VP8/VP8L
 * bitstream, so it stays pure JVM code.
 */
data class WebpInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
    val totalDurationMs: Int,
    val hasAlpha: Boolean,
) {
    companion object {
        /** Duration reported for still files, which carry no timing information. */
        const val STILL_DURATION_MS = 1000

        private const val VP8X_ANIMATION_FLAG = 0x02
        private const val VP8X_ALPHA_FLAG = 0x10

        /** @throws IllegalArgumentException when [bytes] is not a valid WebP. */
        fun parse(bytes: ByteArray): WebpInfo {
            if (bytes.size < 12 ||
                readAscii(bytes, 0, 4) != "RIFF" ||
                readAscii(bytes, 8, 4) != "WEBP"
            ) {
                throw IllegalArgumentException("Not a WebP file: missing RIFF/WEBP header")
            }

            var hasVp8x = false
            var canvasWidth = -1
            var canvasHeight = -1
            var vp8Width = -1
            var vp8Height = -1
            var vp8lWidth = -1
            var vp8lHeight = -1
            var hasAlpha = false
            var animationSignal = false
            val frameDurations = ArrayList<Int>()

            var offset = 12
            while (offset + 8 <= bytes.size) {
                val fourCC = readAscii(bytes, offset, 4)
                val size = readUInt32LE(bytes, offset + 4)
                val payloadStart = offset + 8
                val payloadEnd = payloadStart + size
                if (payloadEnd > bytes.size) {
                    throw IllegalArgumentException("Truncated WebP chunk '$fourCC'")
                }
                when (fourCC) {
                    "VP8X" -> {
                        if (size < 10) throw IllegalArgumentException("Malformed VP8X chunk")
                        hasVp8x = true
                        val flags = bytes[payloadStart].toInt() and 0xFF
                        if (flags and VP8X_ALPHA_FLAG != 0) hasAlpha = true
                        if (flags and VP8X_ANIMATION_FLAG != 0) animationSignal = true
                        canvasWidth = readUInt24LE(bytes, payloadStart + 4) + 1
                        canvasHeight = readUInt24LE(bytes, payloadStart + 7) + 1
                    }
                    "ANIM" -> {
                        if (size < 6) throw IllegalArgumentException("Malformed ANIM chunk")
                        animationSignal = true
                    }
                    "ANMF" -> {
                        if (size < 16) throw IllegalArgumentException("Malformed ANMF chunk")
                        animationSignal = true
                        frameDurations += readUInt24LE(bytes, payloadStart + 12)
                        if (anmfHasAlpha(bytes, payloadStart + 16, payloadEnd)) hasAlpha = true
                    }
                    "ALPH" -> hasAlpha = true
                    "VP8 " -> {
                        if (size < 10) throw IllegalArgumentException("Truncated VP8 frame header")
                        checkVp8StartCode(bytes, payloadStart)
                        vp8Width = readUInt16LE(bytes, payloadStart + 6) and 0x3FFF
                        vp8Height = readUInt16LE(bytes, payloadStart + 8) and 0x3FFF
                    }
                    "VP8L" -> {
                        if (size < 5) throw IllegalArgumentException("Truncated VP8L frame header")
                        if ((bytes[payloadStart].toInt() and 0xFF) != 0x2F) {
                            throw IllegalArgumentException("Invalid VP8L frame header")
                        }
                        vp8lWidth = (((bytes[payloadStart + 1].toInt() and 0xFF) or
                            ((bytes[payloadStart + 2].toInt() and 0xFF) shl 8)) and 0x3FFF) + 1
                        vp8lHeight = ((((bytes[payloadStart + 2].toInt() and 0xFF) ushr 6) or
                            ((bytes[payloadStart + 3].toInt() and 0xFF) shl 2) or
                            ((bytes[payloadStart + 4].toInt() and 0xFF) shl 10)) and 0x3FFF) + 1
                        if ((bytes[payloadStart + 4].toInt() and 0x10) != 0) hasAlpha = true
                    }
                    // Unknown chunks (XMP, EXIF, ICCP, ...) are skipped.
                }
                offset = (payloadEnd + (size and 1L)).toInt()
            }

            if (!hasVp8x && (animationSignal || frameDurations.isNotEmpty())) {
                throw IllegalArgumentException("Animated WebP is missing its VP8X chunk")
            }
            val animatedFile = hasVp8x && (animationSignal || frameDurations.isNotEmpty())

            if (animatedFile) {
                if (frameDurations.isEmpty()) {
                    throw IllegalArgumentException("Animated WebP contains no ANMF frames")
                }
                if (canvasWidth <= 0 || canvasHeight <= 0) {
                    throw IllegalArgumentException("Malformed VP8X canvas")
                }
                return WebpInfo(
                    width = canvasWidth,
                    height = canvasHeight,
                    frameCount = frameDurations.size,
                    frameDurationsMs = frameDurations.toList(),
                    totalDurationMs = totalDuration(frameDurations),
                    hasAlpha = hasAlpha,
                )
            }

            if (vp8Width <= 0 && vp8lWidth <= 0) {
                throw IllegalArgumentException("No WebP image data found")
            }
            val width: Int
            val height: Int
            if (hasVp8x) {
                if (canvasWidth <= 0 || canvasHeight <= 0) {
                    throw IllegalArgumentException("Malformed VP8X canvas")
                }
                width = canvasWidth
                height = canvasHeight
            } else {
                width = if (vp8Width > 0) vp8Width else vp8lWidth
                height = if (vp8Height > 0) vp8Height else vp8lHeight
            }
            return WebpInfo(
                width = width,
                height = height,
                frameCount = 1,
                frameDurationsMs = listOf(STILL_DURATION_MS),
                totalDurationMs = STILL_DURATION_MS,
                hasAlpha = hasAlpha,
            )
        }

        private fun totalDuration(durations: List<Int>): Int {
            var total = 0L
            for (duration in durations) total += duration
            return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /** Walks the sub-chunks of an ANMF payload looking for alpha information. */
        private fun anmfHasAlpha(bytes: ByteArray, start: Int, end: Int): Boolean {
            var offset = start
            while (offset + 8 <= end) {
                val fourCC = readAscii(bytes, offset, 4)
                val size = readUInt32LE(bytes, offset + 4)
                val payloadStart = offset + 8
                val payloadEnd = payloadStart + size
                if (payloadEnd > end) break
                when (fourCC) {
                    "ALPH" -> return true
                    "VP8L" -> {
                        if (size >= 5 &&
                            (bytes[payloadStart].toInt() and 0xFF) == 0x2F &&
                            (bytes[payloadStart + 4].toInt() and 0x10) != 0
                        ) {
                            return true
                        }
                    }
                }
                offset = (payloadEnd + (size and 1L)).toInt()
            }
            return false
        }

        private fun checkVp8StartCode(bytes: ByteArray, payloadStart: Int) {
            if ((bytes[payloadStart + 3].toInt() and 0xFF) != 0x9D ||
                (bytes[payloadStart + 4].toInt() and 0xFF) != 0x01 ||
                (bytes[payloadStart + 5].toInt() and 0xFF) != 0x2A
            ) {
                throw IllegalArgumentException("Invalid VP8 frame header")
            }
        }

        private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)

        private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readUInt24LE(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)

        private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
