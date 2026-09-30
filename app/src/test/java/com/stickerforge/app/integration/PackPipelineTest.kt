package com.stickerforge.app.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.stickerforge.app.BuildConfig
import com.stickerforge.app.core.EditMask
import com.stickerforge.app.core.StickerExporter
import com.stickerforge.app.core.webp.WebpInfo
import com.stickerforge.app.data.PackRepository
import com.stickerforge.app.model.StickerPack
import com.stickerforge.app.whatsapp.StickerContentProvider
import com.stickerforge.app.whatsapp.StickerPackValidator
import com.stickerforge.app.whatsapp.WastickersFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end checks of the parts that only exist on Android: pack storage,
 * the 512px exporter, `.wastickers` and - most importantly - the
 * ContentProvider contract WhatsApp uses to read a sticker pack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackPipelineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = PackRepository(context)

    private fun squareFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.RED }
        canvas.drawRect(64f, 64f, 448f, 448f, paint)
        return bitmap
    }

    private fun webpStickerBytes(): ByteArray {
        val mask = EditMask(512, 512)
        // erase the top-left corner so the export has to keep transparency
        mask.eraseCircle(0f, 0f, 260f, 1f)
        return StickerExporter.exportStatic(squareFrame(), mask)
    }

    private fun newPackWithSticker(name: String): Pair<StickerPack, String> {
        val pack = repository.createPack(name)
        val bytes = webpStickerBytes()
        val sticker = repository.addSticker(pack.identifier, bytes, listOf("\uD83D\uDE00"), animated = false)
        return repository.getPack(pack.identifier)!! to sticker.fileName
    }

    // ------------------------------------------------------------------ export

    @Test
    fun `exporter produces a real 512x512 webp sticker under the size budget`() {
        val bytes = webpStickerBytes()

        assertTrue("expected bytes, got ${bytes.size}", bytes.isNotEmpty())
        assertTrue(
            "static sticker is ${bytes.size / 1024} KB, over the 100 KB budget",
            bytes.size <= StickerExporter.STATIC_BUDGET_BYTES,
        )
        val info = WebpInfo.parse(bytes)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertEquals(1, info.frameCount)
    }

    // -------------------------------------------------------------- repository

    @Test
    fun `pack storage keeps stickers, bumps the data version and makes a 96x96 tray`() {
        val (pack, fileName) = newPackWithSticker("Storage Pack")

        assertNotNull(repository.stickerBytes(pack.identifier, fileName))

        val tray = repository.ensureTray(pack.identifier)
        assertNotNull("tray icon should be generated from the first sticker", tray)
        assertTrue("tray is ${tray!!.size / 1024} KB", tray.size <= StickerPackValidator.TRAY_LIMIT_BYTES)
        val trayBitmap = BitmapFactory.decodeByteArray(tray, 0, tray.size)
        assertEquals(96, trayBitmap.width)
        assertEquals(96, trayBitmap.height)

        val stickerId = pack.stickers.first().id
        repository.setStickerEmojis(pack.identifier, stickerId, listOf("\uD83D\uDE00", "\uD83D\uDD25"))
        val edited = repository.getPack(pack.identifier)!!
        assertNotEquals(pack.imageDataVersion, edited.imageDataVersion)
        assertEquals(2, edited.stickers.first().emojis.size)

        repository.deletePack(pack.identifier)
        assertNull(repository.getPack(pack.identifier))
    }

    @Test
    fun `a valid exported pack passes the whatsapp validator`() {
        val (pack, fileName) = newPackWithSticker("Validator Pack")
        // a pack needs 3..30 stickers to be valid, so top it up
        repository.addSticker(pack.identifier, webpStickerBytes(), listOf("\uD83D\uDD25"), animated = false)
        repository.addSticker(pack.identifier, webpStickerBytes(), listOf("\u2728"), animated = false)
        val full = repository.getPack(pack.identifier)!!

        StickerPackValidator.validate(
            pack = full,
            readSticker = { repository.stickerBytes(full.identifier, it.fileName) ?: ByteArray(0) },
            readTray = { repository.ensureTray(full.identifier) ?: ByteArray(0) },
        )

        assertEquals(3, full.stickers.size)
        assertEquals(fileName, full.stickers.first().fileName)
    }

    @Test
    fun `wastickers export round-trips back in`() {
        val (pack, _) = newPackWithSticker("Roundtrip Pack")

        val zip = repository.writeWastickers(pack.identifier)
        val imported = WastickersFile.read(zip)

        assertEquals("Roundtrip Pack", imported.pack.name)
        assertEquals(1, imported.pack.stickers.size)

        val restored = repository.importPack(imported)
        assertNotEquals("importing must not clobber the original", pack.identifier, restored.identifier)
        assertEquals(1, restored.stickers.size)
        assertNotNull(repository.stickerBytes(restored.identifier, restored.stickers.first().fileName))
    }

    // ----------------------------------------------------------- the provider

    @Test
    fun `content provider answers the whatsapp contract and serves sticker bytes`() {
        val (pack, fileName) = newPackWithSticker("Provider Pack")
        val expectedBytes = repository.stickerBytes(pack.identifier, fileName)

        val provider = Robolectric.buildContentProvider(StickerContentProvider::class.java).create().get()

        // 1. metadata for every pack
        provider.query(Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/metadata"), null, null, null, null)!!
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                val identifier = cursor.getColumnIndexOrThrow("sticker_pack_identifier")
                var found = false
                while (!cursor.isAfterLast) {
                    if (cursor.getString(identifier) == pack.identifier) {
                        found = true
                        assertEquals(
                            "Provider Pack",
                            cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_name")),
                        )
                        assertEquals(
                            StickerPack.TRAY_FILE_NAME,
                            cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_icon")),
                        )
                        assertEquals(
                            0,
                            cursor.getInt(cursor.getColumnIndexOrThrow("animated_sticker_pack")),
                        )
                        // avoid_cache is deprecated and must stay 0
                        assertEquals(
                            0,
                            cursor.getInt(cursor.getColumnIndexOrThrow("whatsapp_will_not_cache_stickers")),
                        )
                    }
                    cursor.moveToNext()
                }
                assertTrue("pack missing from metadata cursor", found)
            }

        // 2. single pack metadata
        provider.query(
            Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/metadata/${pack.identifier}"),
            null, null, null, null,
        )!!.use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(
                pack.imageDataVersion,
                cursor.getString(cursor.getColumnIndexOrThrow("image_data_version")),
            )
        }

        // 3. sticker list with emoji tags
        provider.query(
            Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers/${pack.identifier}"),
            null, null, null, null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(fileName, cursor.getString(cursor.getColumnIndexOrThrow("sticker_file_name")))
            assertEquals(
                "\uD83D\uDE00",
                cursor.getString(cursor.getColumnIndexOrThrow("sticker_emoji")),
            )
        }

        // 4. asset bytes for a sticker
        val stickerUri = Uri.parse(
            "content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers_asset/${pack.identifier}/$fileName",
        )
        val served = provider.openAssetFile(stickerUri, "r")!!
            .createInputStream().use { it.readBytes() }
        assertArrayEquals(expectedBytes, served)
        assertEquals("image/webp", provider.getType(stickerUri))

        // 5. the tray icon is served too, as PNG
        val trayUri = Uri.parse(
            "content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers_asset/${pack.identifier}/${StickerPack.TRAY_FILE_NAME}",
        )
        assertNotNull(provider.openAssetFile(trayUri, "r"))
        assertEquals("image/png", provider.getType(trayUri))

        // 6. files that do not belong to the pack are refused
        val foreign = Uri.parse(
            "content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers_asset/${pack.identifier}/../../secrets.webp",
        )
        assertNull(provider.openAssetFile(foreign, "r"))
        assertNull(
            provider.openAssetFile(
                Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers_asset/${pack.identifier}/nope.webp"),
                "r",
            ),
        )
    }

    @Test
    fun `provider is reachable through the content resolver under its manifest authority`() {
        val (pack, _) = newPackWithSticker("Resolver Pack")

        context.contentResolver.query(
            Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/metadata/${pack.identifier}"),
            null, null, null, null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "Resolver Pack",
                cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_name")),
            )
        }
    }
}
