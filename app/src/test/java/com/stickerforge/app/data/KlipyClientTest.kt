package com.stickerforge.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KLIPY speaks the Tenor v2 dialect, so these fixtures are trimmed captures of
 * real KLIPY responses (August 2026).
 */
class KlipyClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(key: String = "test-klipy-key") =
        KlipyClient(OkHttpClient(), { key }, server.url("/v2").toString().trimEnd('/'))

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `search parses the real klipy shape and requests unfiltered results`() = runTest {
        server.enqueue(jsonResponse(SEARCH_JSON))

        val result = client().search("hello", limit = 24, pos = null)

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(2, page.items.size)
        assertEquals("Mg==", page.next)

        val first = page.items[0]
        assertEquals("4551195970372378", first.id)
        // content_description is empty in KLIPY responses; title carries the text
        assertEquals("Greetings: Man Waving Hello", first.title)
        assertEquals("https://static.klipy.com/preview.jpg", first.previewUrl)
        // small animated rendition drives the search grid
        assertEquals("https://static.klipy.com/tiny.gif", first.animatedPreviewUrl)
        assertEquals("https://static.klipy.com/clip.mp4", first.mp4Url)
        assertEquals(640, first.width)
        assertEquals(640, first.height)
        assertEquals(GifSource.KLIPY, first.source)

        val request = server.takeRequest()
        assertEquals("/v2/search", request.path?.substringBefore('?'))
        assertEquals("hello", request.requestUrl?.queryParameter("q"))
        assertEquals("test-klipy-key", request.requestUrl?.queryParameter("key"))
        assertEquals("stickerforge", request.requestUrl?.queryParameter("client_key"))
        assertEquals("off", request.requestUrl?.queryParameter("contentfilter"))
        assertEquals("24", request.requestUrl?.queryParameter("limit"))
        assertNull(request.requestUrl?.queryParameter("searchfilter"))
        assertNull(request.requestUrl?.queryParameter("pos"))
    }

    @Test
    fun `sticker mode asks for transparent content and prefers it`() = runTest {
        server.enqueue(jsonResponse(STICKER_JSON))

        val result = client().search("hello", limit = 24, pos = "Mg==", stickerOnly = true)

        val page = (result as ApiResult.Ok<GifPage>).value
        val first = page.items[0]
        // the full-size transparent GIF wins over the opaque mp4/gif and the tiny variants
        assertEquals("https://static.klipy.com/gif-transparent.gif", first.gifUrl)
        assertEquals("https://static.klipy.com/preview.jpg", first.previewUrl)

        val request = server.takeRequest()
        assertEquals("sticker", request.requestUrl?.queryParameter("searchfilter"))
        assertEquals("Mg==", request.requestUrl?.queryParameter("pos"))
    }

    @Test
    fun `featured omits the query`() = runTest {
        server.enqueue(jsonResponse(SEARCH_JSON))

        val result = client().featured(limit = 10, pos = null)

        assertTrue(result is ApiResult.Ok)
        val request = server.takeRequest()
        assertEquals("/v2/featured", request.path?.substringBefore('?'))
        assertNull(request.requestUrl?.queryParameter("q"))
    }

    @Test
    fun `blank key returns MissingKey without an http call`() = runTest {
        val result = client(key = " ").search("hello", limit = 5, pos = null)

        assertTrue(result is ApiResult.MissingKey)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `content filter rejection retries once at medium`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("{\"error\":\"contentfilter off is not allowed for this key\"}"),
        )
        server.enqueue(jsonResponse(SEARCH_JSON))

        val result = client().search("hello", limit = 5, pos = null)

        assertTrue(result is ApiResult.Ok)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("medium", server.takeRequest().requestUrl?.queryParameter("contentfilter"))
    }

    @Test
    fun `other failures map to HttpError and NetworkError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        val throttled = client().search("hello", limit = 5, pos = null)
        assertEquals(429, (throttled as ApiResult.HttpError).code)

        server.shutdown()
        val offline = client().search("hello", limit = 5, pos = null)
        assertTrue(offline is ApiResult.NetworkError)
    }

    @Test
    fun `result without any usable media is skipped`() = runTest {
        server.enqueue(jsonResponse(NO_MEDIA_JSON))

        val page = (client().search("hello", limit = 5, pos = null) as ApiResult.Ok<GifPage>).value

        assertNotNull(page.items)
        assertEquals(0, page.items.size)
        assertNull(page.next)
    }

    private companion object {
        val SEARCH_JSON = """
            {
              "results": [
                {
                  "id": "4551195970372378",
                  "title": "Greetings: Man Waving Hello",
                  "content_description": "",
                  "media_formats": {
                    "gifpreview": { "url": "https://static.klipy.com/preview.jpg", "dims": [498, 498] },
                    "tinygif": { "url": "https://static.klipy.com/tiny.gif", "dims": [220, 220] },
                    "gif": { "url": "https://static.klipy.com/full.gif", "dims": [498, 498] },
                    "mp4": { "url": "https://static.klipy.com/clip.mp4", "dims": [640, 640] },
                    "tinymp4": { "url": "https://static.klipy.com/tiny.mp4", "dims": [320, 320] },
                    "webp": { "url": "https://static.klipy.com/full.webp", "dims": [498, 498] }
                  }
                },
                {
                  "id": "4551195970372379",
                  "title": "Second",
                  "media_formats": {
                    "gifpreview": { "url": "https://static.klipy.com/preview2.jpg", "dims": [300, 300] },
                    "tinygif": { "url": "https://static.klipy.com/tiny2.gif", "dims": [220, 220] }
                  }
                }
              ],
              "next": "Mg=="
            }
        """.trimIndent()

        val STICKER_JSON = """
            {
              "results": [
                {
                  "id": "9001",
                  "title": "Transparent sticker",
                  "media_formats": {
                    "gifpreview": { "url": "https://static.klipy.com/preview.jpg", "dims": [498, 498] },
                    "mp4": { "url": "https://static.klipy.com/opaque.mp4", "dims": [640, 640] },
                    "gif": { "url": "https://static.klipy.com/opaque.gif", "dims": [498, 498] },
                    "gif_transparent": { "url": "https://static.klipy.com/gif-transparent.gif", "dims": [498, 498] },
                    "tinygif_transparent": { "url": "https://static.klipy.com/tinygif-transparent.gif", "dims": [220, 220] },
                    "webp_transparent": { "url": "https://static.klipy.com/webp-transparent.webp", "dims": [498, 498] }
                  }
                }
              ]
            }
        """.trimIndent()

        val NO_MEDIA_JSON = """
            { "results": [ { "id": "empty", "title": "no formats", "media_formats": {} } ] }
        """.trimIndent()
    }
}
