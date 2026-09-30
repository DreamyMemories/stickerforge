package com.stickerforge.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class TenorClientTest {

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

    private fun client(key: String = "test-tenor-key"): TenorClient =
        TenorClient(OkHttpClient(), { key }, server.url("/").toString())

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `search parses results, fallback formats and next cursor`() = runTest {
        server.enqueue(jsonResponse(SEARCH_JSON))

        val result = client().search("dogs", limit = 12, pos = "CURSOR123")

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(2, page.items.size)
        assertEquals("CAESFgoQYWJjZGVmZ2hpamtsbW5vcA", page.next)

        val first = page.items[0]
        assertEquals("9219050505917113791", first.id)
        assertEquals("A dog wearing sunglasses", first.title)
        assertEquals("https://media.tenor.com/abcAAAA/preview.gif", first.previewUrl)
        assertEquals("https://media.tenor.com/abcAAAA/original.gif", first.gifUrl)
        assertEquals("https://media.tenor.com/abcAAAA/original.mp4", first.mp4Url)
        assertEquals(498, first.width)
        assertEquals(280, first.height)
        assertEquals(GifSource.TENOR, first.source)

        // Second item has no gifpreview and no mp4: falls back to tinygif / tinymp4.
        val second = page.items[1]
        assertEquals("https://media.tenor.com/bbbbBBBB/preview.gif", second.previewUrl)
        assertEquals("https://media.tenor.com/bbbbBBBB/tiny.mp4", second.mp4Url)
        assertEquals(320, second.width)
        assertEquals(240, second.height)

        val request = server.takeRequest()
        assertEquals("/search", request.path?.substringBefore('?'))
        assertEquals("dogs", request.requestUrl?.queryParameter("q"))
        assertEquals("test-tenor-key", request.requestUrl?.queryParameter("key"))
        assertEquals("12", request.requestUrl?.queryParameter("limit"))
        assertEquals("CURSOR123", request.requestUrl?.queryParameter("pos"))
        assertEquals("off", request.requestUrl?.queryParameter("contentfilter"))
        assertEquals("gifpreview,tinygif,gif,mp4,tinymp4", request.requestUrl?.queryParameter("media_filter"))
    }

    @Test
    fun `featured omits query and pos and maps a blank next to null`() = runTest {
        server.enqueue(jsonResponse(FEATURED_JSON))

        val result = client().featured(limit = 20, pos = null)

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(1, page.items.size)
        assertNull(page.items[0].mp4Url)
        assertNull(page.next)

        val request = server.takeRequest()
        assertEquals("/featured", request.path?.substringBefore('?'))
        assertNull(request.requestUrl?.queryParameter("q"))
        assertNull(request.requestUrl?.queryParameter("pos"))
        assertEquals("20", request.requestUrl?.queryParameter("limit"))
        assertEquals("off", request.requestUrl?.queryParameter("contentfilter"))
    }

    @Test
    fun `results without a usable preview are skipped`() = runTest {
        server.enqueue(jsonResponse(NO_PREVIEW_JSON))

        val result = client().search("cats", limit = 10, pos = null)

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(1, page.items.size)
        assertEquals("kept", page.items[0].id)
        assertNull(page.next)
    }

    @Test
    fun `retries once with contentfilter medium on a content filter 400`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(CONTENT_FILTER_ERROR))
        server.enqueue(jsonResponse(SEARCH_JSON))

        val result = client().search("dogs", limit = 12, pos = "CURSOR123")

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(2, page.items.size)
        assertEquals(2, server.requestCount)

        val first = server.takeRequest()
        assertEquals("off", first.requestUrl?.queryParameter("contentfilter"))
        val second = server.takeRequest()
        assertEquals("medium", second.requestUrl?.queryParameter("contentfilter"))
        assertEquals("dogs", second.requestUrl?.queryParameter("q"))
        assertEquals("CURSOR123", second.requestUrl?.queryParameter("pos"))
        assertEquals("test-tenor-key", second.requestUrl?.queryParameter("key"))
        assertEquals("12", second.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun `does not retry on other 400 responses`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"invalid request\"}}")
        )

        val result = client().search("dogs", limit = 10, pos = null)

        val error = result as ApiResult.HttpError
        assertEquals(400, error.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failing retry is returned without a second retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(CONTENT_FILTER_ERROR))
        server.enqueue(MockResponse().setResponseCode(400).setBody(CONTENT_FILTER_ERROR))

        val result = client().search("dogs", limit = 10, pos = null)

        val error = result as ApiResult.HttpError
        assertEquals(400, error.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `blank key returns MissingKey without an http call`() = runTest {
        val result = client(key = "").search("dogs", limit = 10, pos = null)

        assertTrue(result is ApiResult.MissingKey)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `io failure maps to NetworkError`() = runTest {
        val client = client()
        server.shutdown()

        val result = client.search("dogs", limit = 10, pos = null)

        assertTrue(result is ApiResult.NetworkError)
    }

    @Test
    fun `malformed json maps to ParseError`() = runTest {
        server.enqueue(jsonResponse("{\"results\": [oops"))

        val result = client().search("dogs", limit = 10, pos = null)

        assertTrue(result is ApiResult.ParseError)
    }

    private companion object {
        /** Tenor answers invalid content filters with a 400 naming the parameter. */
        val CONTENT_FILTER_ERROR =
            "{\"error\":{\"code\":400,\"message\":\"ContentFilter value is invalid: off is not allowed\"}}"

        val SEARCH_JSON = """
            {
              "results": [
                {
                  "id": "9219050505917113791",
                  "content_description": "A dog wearing sunglasses",
                  "media_formats": {
                    "gifpreview": { "url": "https://media.tenor.com/abcAAAA/preview.gif", "dims": [220, 124] },
                    "tinygif": { "url": "https://media.tenor.com/abcAAAA/tiny.gif", "dims": [220, 124] },
                    "gif": { "url": "https://media.tenor.com/abcAAAA/original.gif", "dims": [498, 280] },
                    "mp4": { "url": "https://media.tenor.com/abcAAAA/original.mp4", "dims": [498, 280] },
                    "tinymp4": { "url": "https://media.tenor.com/abcAAAA/tiny.mp4", "dims": [220, 124] }
                  }
                },
                {
                  "id": "55512345",
                  "content_description": "Fallback formats",
                  "media_formats": {
                    "gifpreview": { "url": "" },
                    "tinygif": { "url": "https://media.tenor.com/bbbbBBBB/preview.gif" },
                    "gif": { "url": "https://media.tenor.com/bbbbBBBB/original.gif", "dims": [320, 240] },
                    "tinymp4": { "url": "https://media.tenor.com/bbbbBBBB/tiny.mp4" }
                  }
                }
              ],
              "next": "CAESFgoQYWJjZGVmZ2hpamtsbW5vcA"
            }
        """.trimIndent()

        val FEATURED_JSON = """
            {
              "results": [
                {
                  "id": "featured-1",
                  "content_description": "Featured GIF",
                  "media_formats": {
                    "gifpreview": { "url": "https://media.tenor.com/ccccCCCC/preview.gif", "dims": [300, 200] },
                    "gif": { "url": "https://media.tenor.com/ccccCCCC/original.gif", "dims": [300, 200] }
                  }
                }
              ],
              "next": ""
            }
        """.trimIndent()

        val NO_PREVIEW_JSON = """
            {
              "results": [
                {
                  "id": "no-preview",
                  "content_description": "GIF without preview",
                  "media_formats": {
                    "gif": { "url": "https://media.tenor.com/nopreview/original.gif", "dims": [100, 100] }
                  }
                },
                {
                  "id": "blank-preview",
                  "content_description": "GIF with blank preview",
                  "media_formats": {
                    "gifpreview": { "url": "   " },
                    "gif": { "url": "https://media.tenor.com/blank/original.gif", "dims": [100, 100] }
                  }
                },
                {
                  "id": "kept",
                  "content_description": "Kept GIF",
                  "media_formats": {
                    "gifpreview": { "url": "https://media.tenor.com/kept/preview.gif", "dims": [100, 100] },
                    "gif": { "url": "https://media.tenor.com/kept/original.gif", "dims": [100, 100] }
                  }
                }
              ],
              "next": ""
            }
        """.trimIndent()
    }
}
