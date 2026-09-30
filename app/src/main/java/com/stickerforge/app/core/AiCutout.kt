package com.stickerforge.app.core

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap background removal using ML Kit subject segmentation (on-device, via
 * Play services). Returns a per-pixel foreground confidence mask that the
 * editor folds into its [EditMask].
 */
class AiCutout {

    sealed interface Result {
        /** [confidence] is row-major, one value per pixel, 0f..1f. */
        data class Ok(val confidence: FloatArray, val width: Int, val height: Int) : Result
        data class Failed(val message: String) : Result
    }

    private val segmenter: SubjectSegmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        SubjectSegmentation.getClient(options)
    }

    suspend fun segment(bitmap: Bitmap): Result = withContext(Dispatchers.IO) {
        try {
            val task = segmenter.process(InputImage.fromBitmap(bitmap, 0))
            val result = Tasks.await(task, 30, TimeUnit.SECONDS)
            val mask = result.foregroundConfidenceMask
                ?: return@withContext Result.Failed(
                    "The segmentation model is still downloading. Try again in a minute.",
                )
            val expected = bitmap.width * bitmap.height
            val confidence = FloatArray(expected)
            val buffer = mask.duplicate()
            buffer.rewind()
            val available = minOf(expected, buffer.remaining())
            buffer.get(confidence, 0, available)
            Result.Ok(confidence, bitmap.width, bitmap.height)
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            Result.Failed(
                when {
                    message.contains("optional module", ignoreCase = true) ||
                        message.contains("downloaded", ignoreCase = true) ->
                        "The segmentation model is still downloading. Give it a minute, then tap AI cutout again."
                    message.isBlank() -> "Subject segmentation failed"
                    else -> message
                },
            )
        }
    }

    /**
     * Applies a segmentation result to [mask]: pixels above [high] stay, below
     * [low] are removed, in between fades for a soft edge.
     */
    fun applyTo(
        mask: EditMask,
        result: Result.Ok,
        low: Float = 0.35f,
        high: Float = 0.65f,
    ) {
        if (result.width != mask.width || result.height != mask.height) return
        mask.checkpoint()
        mask.applyConfidenceMask(result.confidence, low, high)
    }
}
