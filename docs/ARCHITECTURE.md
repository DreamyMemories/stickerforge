# StickerForge architecture

Single-module Android app, package `com.stickerforge.app`, Jetpack Compose,
Kotlin 2.0.21, AGP 8.7.3, minSdk 28, compileSdk 35.

```
app/src/main/java/com/stickerforge/app/
  MainActivity.kt            single activity, Compose navigation
  StickerForgeApp.kt         Application
  AppGraph.kt                hand-rolled service locator
  model/StickerPack.kt       serializable pack + sticker models
  data/ApiKeys.kt            DataStore-backed API keys
  data/GifModels.kt          GifResult / GifPage / ApiResult
  data/GifSearchService.kt   dispatcher over the two providers
  data/GiphyClient.kt        Giphy REST client          [issue #4]
  data/TenorClient.kt        Tenor REST client           [issue #4]
  data/PackRepository.kt     pack storage on disk
  core/EditMask.kt           per-pixel alpha mask         [issue #2]
  core/MagicWand.kt          flood fill selection         [issue #2]
  core/webp/WebpInfo.kt      RIFF/WebP header parser      [issue #3]
  core/webp/WebpMuxer.kt     animated WebP muxer          [issue #3]
  core/StickerExporter.kt    512x512 compose + size search
  core/Frames.kt             GIF / MP4 / still loading    [issue #8]
  core/AiCutout.kt           ML Kit subject segmentation  [issue #8]
  whatsapp/StickerContentProvider.kt  provider contract   [issue #6]
  whatsapp/StickerPackValidator.kt    pack rules          [issue #5]
  whatsapp/WhitelistCheck.kt          added-to-WhatsApp   [issue #6]
  whatsapp/WastickersFile.kt          .wastickers zip     [issue #5]
  ui/theme/Theme.kt
  ui/...                     screens                    [issue #7]
```

Frozen contracts below are what the subagents implement. Do not rename
anything without updating this file, `AppGraph` and the call sites.

---

## EditMask (issue #2) - `core/EditMask.kt`

Pure Kotlin. No `android.graphics` imports so it unit-tests on the JVM.

```kotlin
class EditMask(val width: Int, val height: Int) {
    /** Row-major, 0 = fully removed, 255 = fully opaque. Same length as width*height. */
    val alpha: ByteArray

    val canUndo: Boolean
    val canRedo: Boolean

    /** Push the current alpha onto the undo stack. Call once per user operation. */
    fun checkpoint()
    fun undo()
    fun redo()
    fun reset()   // every pixel back to 255

    /** Soft radial brush. hardness 0 = very soft edge, 1 = hard edge. */
    fun eraseCircle(cx: Float, cy: Float, radius: Float, hardness: Float)
    fun restoreCircle(cx: Float, cy: Float, radius: Float, hardness: Float)

    /**
     * @param pixels source ARGB_8888 pixels, same length as alpha
     * @param tolerance 0..255, max difference across R,G,B
     * @param contiguous true = 4-neighbour flood fill from the seed,
     *                   false = every pixel in the image matching the seed colour
     * @param erase true = set alpha to 0, false = set alpha to 255
     */
    fun magicWand(pixels: IntArray, seedX: Int, seedY: Int, tolerance: Int, contiguous: Boolean, erase: Boolean)

    /** ML Kit confidence mask (0f..1f). <=low becomes 0, >=high becomes 255, linear in between. */
    fun applyConfidenceMask(confidence: FloatArray, low: Float = 0.35f, high: Float = 0.65f)

    /** Independent copy, including history. */
    fun copy(): EditMask
}
```

Undo history is capped (>= 20 steps). Alpha stays clamped to 0..255.

---

## WebP (issue #3) - `core/webp/`

Writing an animated WebP from scratch: Android can only encode still WebP
frames (`Bitmap.compress`), so we mux the container ourselves.

```kotlin
data class WebpInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
    val totalDurationMs: Int,
    val hasAlpha: Boolean,
) {
    companion object { fun parse(bytes: ByteArray): WebpInfo }
}

class StillWebp(val bytes: ByteArray, val durationMs: Int)

object WebpMuxer {
    /** frames = still WebP files produced by Bitmap.compress(WEBP_LOSSY). */
    fun mux(frames: List<StillWebp>, width: Int, height: Int, loopCount: Int = 0): ByteArray
}
```

Container rules to honour:

- `RIFF` + size + `WEBP`
- `VP8X`: flags bit 1 = animation, bit 4 = alpha; 24-bit little-endian
  `canvasWidth - 1` / `canvasHeight - 1`
- `ANIM`: 4-byte background colour, 2-byte loop count
- one full-canvas `ANMF` per frame: 24-bit x/2, y/2, width-1, height-1,
  duration ms, 1 flag byte (bit 1 = do not blend), then the frame's
  `ALPH` + `VP8 `/`VP8L` chunks copied verbatim
- every RIFF chunk size is padded to an even byte count

`WebpInfo.parse` is also used by the validator, so it must tolerate
VP8X / ANMF / ALPH / VP8 / VP8L and reject non-WebP input.

---

## Search (issue #4) - `data/GiphyClient.kt`, `data/TenorClient.kt`

```kotlin
class GiphyClient(private val http: OkHttpClient, private val keyProvider: suspend () -> String) {
    suspend fun search(query: String, limit: Int, offset: Int): ApiResult<GifPage>
    suspend fun trending(limit: Int, offset: Int): ApiResult<GifPage>
}

class TenorClient(private val http: OkHttpClient, private val keyProvider: suspend () -> String) {
    suspend fun search(query: String, limit: Int, pos: String?): ApiResult<GifPage>
    suspend fun featured(limit: Int, pos: String?): ApiResult<GifPage>
}
```

- Giphy: `https://api.giphy.com/v1/gifs/search|trending`, `rating=r`,
  `bundle=messaging_non_clips`, preview = `images.fixed_width_small_still.url`,
  media = `images.original.url` and `images.original.mp4` when present.
  `next` cursor = offset + returned count.
- Tenor: `https://tenor.googleapis.com/v2/search|featured`,
  `contentfilter=off`, `media_filter=gifpreview,tinygif,gif,mp4,tinymp4`.
  If the response is a 400 that mentions the content filter, retry once with
  `contentfilter=medium`. `next` = `next` from the response payload.
- Never log or hardcode keys. Return `ApiResult.MissingKey` when the key is blank.
- Skip results without a usable `previewUrl`.

---

## Wastickers (issue #5) - `whatsapp/WastickersFile.kt`

```kotlin
object WastickersFile {
    data class Imported(
        val pack: StickerPack,
        val stickerBytes: Map<String, ByteArray>,  // fileName -> bytes
        val trayBytes: ByteArray,
    )

    fun write(
        pack: StickerPack,
        readSticker: (Sticker) -> ByteArray,
        readTray: () -> ByteArray,
    ): ByteArray

    fun read(zipBytes: ByteArray): Imported
}
```

Zip layout (Sticker.ly compatible): `manifest.json` at the root, then every
sticker file and the tray image, all with their plain file names.

`manifest.json` keys: `identifier`, `name`, `publisher`, `tray_image_file`,
`publisher_email`, `publisher_website`, `privacy_policy_website`,
`license_agreement_website`, `image_data_version`, `avoid_cache`,
`animated_sticker_pack`, `stickers` (`image_file`, `emojis`,
`accessibility_text`).

## Validation (issue #5) - `whatsapp/StickerPackValidator.kt`

```kotlin
class StickerPackValidationException(message: String) : IllegalStateException(message)

object StickerPackValidator {
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val STATIC_LIMIT_BYTES = 100 * 1024
    const val ANIMATED_LIMIT_BYTES = 500 * 1024

    /** @throws StickerPackValidationException on the first rule violation. */
    fun validate(pack: StickerPack, readSticker: (Sticker) -> ByteArray, readTray: () -> ByteArray)
}
```

Rules: identifier/name/publisher non-blank, <= 128 chars, identifier charset
`[\w-.,' ]` without `..`; 3..30 stickers; 1..3 emojis each; each sticker
512x512 via `WebpInfo`; static <= 100KB and frameCount == 1; animated
<= 500KB and frameCount > 1 and every frame duration >= 8ms and total
<= 10s; pack marked animated only when all stickers animate; tray image
24..512 px and <= 50KB.

---

## Running things

```
gradlew :app:testDebugUnitTest     # JVM unit tests
gradlew :app:assembleDebug         # APK
```

API keys are entered in the app's Settings screen and stored with DataStore.
No keys live in the repository.
