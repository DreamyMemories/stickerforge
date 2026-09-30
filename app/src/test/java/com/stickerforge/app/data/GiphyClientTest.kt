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
class GiphyClientTest {

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

    private fun client(key: String = "test-giphy-key"): GiphyClient =
        GiphyClient(OkHttpClient(), { key }, server.url("/").toString())

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `search parses results, dimensions and next cursor`() = runTest {
        server.enqueue(jsonResponse(SEARCH_JSON))

        val result = client().search("excited dog", limit = 10, offset = 0)

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(2, page.items.size)
        assertEquals("2", page.next)

        val first = page.items[0]
        assertEquals("l0HlvtIPzPdt2usKs", first.id)
        assertEquals("excited dog GIF", first.title)
        assertEquals("https://media.giphy.com/media/l0HlvtIPzPdt2usKs/100w_s.gif", first.previewUrl)
        assertEquals("https://media.giphy.com/media/l0HlvtIPzPdt2usKs/giphy.gif", first.gifUrl)
        assertEquals("https://media.giphy.com/media/l0HlvtIPzPdt2usKs/giphy.mp4", first.mp4Url)
        assertEquals(480, first.width)
        assertEquals(270, first.height)
        assertEquals(GifSource.GIPHY, first.source)

        // Second item has numeric dimensions and no mp4 in a realistic payload.
        val second = page.items[1]
        assertEquals("3o7aD2saalBwwftBIY", second.id)
        assertNull(second.mp4Url)
        assertEquals(480, second.width)
        assertEquals(270, second.height)

        val request = server.takeRequest()
        assertEquals("/search", request.path?.substringBefore('?'))
        assertEquals("excited dog", request.url?.queryParameter("q"))
        assertEquals("test-giphy-key", request.url?.queryParameter("api_key"))
        assertEquals("10", request.url?.queryParameter("limit"))
        assertEquals("0", request.url?.queryParameter("offset"))
        assertEquals("r", request.url?.queryParameter("rating"))
        assertEquals("en", request.url?.queryParameter("lang"))
        assertEquals("messaging_non_clips", request.url?.queryParameter("bundle"))
    }

    @Test
    fun `trending omits the query and returns null on the last page`() = runTest {
        server.enqueue(jsonResponse(TRENDING_JSON))

        val result = client().trending(limit = 5, offset = 0)

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(1, page.items.size)
        assertNull(page.next)

        val request = server.takeRequest()
        assertEquals("/trending", request.path?.substringBefore('?'))
        assertNull(request.url?.queryParameter("q"))
        assertEquals("5", request.url?.queryParameter("limit"))
        assertEquals("0", request.url?.queryParameter("offset"))
    }

    @Test
    fun `results without a usable preview are skipped`() = runTest {
        server.enqueue(jsonResponse(NO_PREVIEW_JSON))

        val result = client().search("cats", limit = 10, offset = 0)

        val page = (result as ApiResult.Ok<GifPage>).value
        assertEquals(1, page.items.size)
        assertEquals("kept", page.items[0].id)
        assertNull(page.next)
    }

    @Test
    fun `blank key returns MissingKey without an http call`() = runTest {
        val result = client(key = "   ").search("cats", limit = 10, offset = 0)

        assertTrue(result is ApiResult.MissingKey)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `io failure maps to NetworkError`() = runTest {
        val client = client()
        server.shutdown()

        val result = client.search("cats", limit = 10, offset = 0)

        assertTrue(result is ApiResult.NetworkError)
    }

    @Test
    fun `non-success status maps to HttpError with a short body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val result = client().search("cats", limit = 10, offset = 0)

        val error = result as ApiResult.HttpError
        assertEquals(500, error.code)
        assertTrue(error.message.contains("upstream exploded"))
    }

    @Test
    fun `malformed json maps to ParseError`() = runTest {
        server.enqueue(jsonResponse("{\"data\": [not json"))

        val result = client().search("cats", limit = 10, offset = 0)

        assertTrue(result is ApiResult.ParseError)
    }

    private companion object {
        val SEARCH_JSON = """
            {
              "data": [
                {
                  "id": "l0HlvtIPzPdt2usKs",
                  "title": "excited dog GIF",
                  "images": {
                    "original": {
                      "url": "https://media.giphy.com/media/l0HlvtIPzPdt2usKs/giphy.gif",
                      "mp4": "https://media.giphy.com/media/l0HlvtIPzPdt2usKs/giphy.mp4",
                      "width": "480",
                      "height": "270"
                    },
                    "fixed_width_small_still": {
                      "url": "https://media.giphy.com/media/l0HlvtIPzPdt2usKs/100w_s.gif",
                      "width": "100",
                      "height": "56"
                    },
                    "fixed_width_small": {
                      "url": "https://media.giphy.com/media/l0HlvtIPzPdt2usKs/100w.gif"
                    }
                  }
                },
                {
                  "id": "3o7aD2saalBwwftBIY",
                  "title": "thumbs up GIF",
                  "images": {
                    "original": {
                      "url": "https://media.giphy.com/media/3o7aD2saalBwwftBIY/giphy.gif",
                      "width": 480,
                      "height": 270
                    },
                    "fixed_width_small_still": {
                      "url": "https://media.giphy.com/media/3o7aD2saalBwwftBIY/100w_s.gif"
                    },
                    "fixed_width_small": {
                      "url": "https://media.giphy.com/media/3o7aD2saalBwwftBIY/100w.gif"
                    }
                  }
                }
              ],
              "pagination": { "total_count": 100, "count": 2, "offset": 0 },
              "meta": { "status": 200, "msg": "OK", "response_id": "abc123" }
            }
        """.trimIndent()

        val TRENDING_JSON = """
            {
              "data": [
                {
                  "id": "trending-1",
                  "title": "trending GIF",
                  "images": {
                    "original": {
                      "url": "https://media.giphy.com/media/trending-1/giphy.gif",
                      "width": "200",
                      "height": "200"
                    },
                    "fixed_width_small_still": {
                      "url": "https://media.giphy.com/media/trending-1/100w_s.gif"
                    }
                  }
                }
              ],
              "pagination": { "total_count": 1, "count": 1, "offset": 0 }
            }
        """.trimIndent()

        val NO_PREVIEW_JSON = """
            {
              "data": [
                {
                  "id": "no-still",
                  "title": "no still GIF",
                  "images": {
                    "original": {
                      "url": "https://media.giphy.com/media/no-still/giphy.gif",
                      "width": "100",
                      "height": "100"
                    }
                  }
                },
                {
                  "id": "blank-still",
                  "title": "blank still GIF",
                  "images": {
                    "original": {
                      "url": "https://media.giphy.com/media/blank-still/giphy.gif",
                      "width": "100",
                      "height": "100"
                    },
                    "fixed_width_small_still": { "url": "   " }
                  }
                },
                {
                  "id": "kept",
                  "title": "kept GIF",
                  "images": {
                    "original": {
                      "url": "https://media.giphy.com/media/kept/giphy.gif",
                      "width": "100",
                      "height": "100"
                    },
                    "fixed_width_small_still": {
                      "url": "https://media.giphy.com/media/kept/100w_s.gif"
                    }
                  }
                }
              ],
              "pagination": { "total_count": 3, "count": 3, "offset": 0 }
            }
        """.trimIndent()
    }
}
