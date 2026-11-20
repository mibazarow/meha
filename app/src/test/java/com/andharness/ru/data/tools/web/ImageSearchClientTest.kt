package com.andharness.ru.data.tools.web

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [ImageSearchClient] against a real OkHttp [MockWebServer]. Covers both catalogs
 * (Openverse `results[]` and Wikimedia `list/search` + `imageinfo`), DIRECT-image-url extraction,
 * the orientation/count/source param mapping on the request URL, the transparent fallback from a
 * failing source to the other, and honest empty results (no results / HTTP error / null client).
 */
class ImageSearchClientTest {

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

    private fun client(): ImageSearchClient = ImageSearchClient(
        OkHttpClient(),
        openverseBaseUrl = server.url("/v1/images/").toString(),
        commonsBaseUrl = server.url("/api.php").toString(),
    )

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private val openverseBody = """
        {"result_count":1,"results":[
          {"id":"x","title":"Nature stream","url":"https://cdn.example.com/nature.jpg",
           "creator":"Jane Doe","license":"by","license_version":"4.0",
           "width":3000,"height":4000,"source":"flickr"}
        ]}
    """.trimIndent()

    private val wikimediaSearchBody = """
        {"batchcomplete":true,"query":{"searchinfo":{"totalhits":1},"search":[
          {"ns":6,"title":"File:Nature Stream.jpg","pageid":1}
        ]}}
    """.trimIndent()

    private val wikimediaInfoBody = """
        {"query":{"pages":[
          {"pageid":1,"ns":6,"title":"File:Nature Stream.jpg","imageinfo":[
            {"size":123,"width":2400,"height":1600,
             "url":"https://upload.wikimedia.org/commons/a/a5/Nature_Stream.jpg",
             "extmetadata":{
               "Artist":{"value":"<a href='//cwm/User:A'>John Smith</a>"},
               "ObjectName":{"value":"Nature Stream"},
               "LicenseShortName":{"value":"CC BY-SA 4.0"}
             }}
          ]}
        ]}}
    """.trimIndent()

    @Test
    fun openverseReturnsDirectUrlAndMetadata() {
        server.enqueue(json(openverseBody))

        val (source, results) = runBlocking { client().search("nature", 5, "portrait", ImageSearchClient.Src.OPENVERSE) }

        assertEquals(ImageSearchClient.Src.OPENVERSE, source)
        assertEquals(1, results.size)
        val r = results.single()
        assertEquals("https://cdn.example.com/nature.jpg", r.url)
        assertEquals("Nature stream", r.title)
        assertEquals("Jane Doe", r.author)
        assertEquals("openverse", r.source)
        assertEquals("by.4.0", r.license)
        assertEquals(3000, r.width)
        assertEquals(4000, r.height)
    }

    @Test
    fun openverseSendsOrientationAndPageSize() {
        server.enqueue(json(openverseBody))

        runBlocking { client().search("nature wallpaper", 3, "landscape", ImageSearchClient.Src.OPENVERSE) }

        val path = server.takeRequest().path!!
        assertTrue("orientation must be in the request: $path", path.contains("orientation=landscape"))
        assertTrue("page_size must be in the request: $path", path.contains("page_size=3"))
        assertTrue("query must be URL-encoded in the request: $path", path.contains("q=nature"))
    }

    @Test
    fun wikimediaResolvesDirectUrlStripsHtmlAndReadsLicense() {
        server.enqueue(json(wikimediaSearchBody))
        server.enqueue(json(wikimediaInfoBody))

        val (source, results) = runBlocking { client().search("nature", 5, null, ImageSearchClient.Src.WIKIMEDIA) }

        assertEquals(ImageSearchClient.Src.WIKIMEDIA, source)
        val r = results.single()
        assertEquals("https://upload.wikimedia.org/commons/a/a5/Nature_Stream.jpg", r.url)
        assertEquals("John Smith", r.author)
        assertEquals("CC BY-SA 4.0", r.license)
        assertEquals(2400, r.width)
        assertEquals(1600, r.height)
    }

    @Test
    fun wikimediaSearchTitlesAreUrlEncoded() {
        server.enqueue(json(wikimediaSearchBody))
        server.enqueue(json(wikimediaInfoBody))

        runBlocking { client().search("nature", 2, null, ImageSearchClient.Src.WIKIMEDIA) }

        // First call is the search (srlimit=2), second is the imageinfo (titles URL-encoded).
        val search = server.takeRequest()
        assertTrue("srlimit must reflect count: ${search.path}", search.path!!.contains("srlimit=2"))
        val info = server.takeRequest()
        assertTrue("file title must be URL-encoded in imageinfo: ${info.path}", info.path!!.contains("File%3ANature"))
    }

    @Test
    fun fallsBackToWikimediaWhenOpenverseIsEmpty() {
        server.enqueue(json("""{"result_count":0,"results":[]}"""))
        server.enqueue(json(wikimediaSearchBody))
        server.enqueue(json(wikimediaInfoBody))

        val (source, results) = runBlocking { client().search("nature", 5, null, ImageSearchClient.Src.OPENVERSE) }

        assertEquals("fallback must report the source that actually served results",
            ImageSearchClient.Src.WIKIMEDIA, source)
        assertEquals(1, results.size)
        assertEquals("wikimedia", results.single().source)
    }

    @Test
    fun fallsBackToWikimediaWhenOpenverseErrors() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(json(wikimediaSearchBody))
        server.enqueue(json(wikimediaInfoBody))

        val (source, results) = runBlocking { client().search("nature", 5, null, ImageSearchClient.Src.OPENVERSE) }

        assertEquals(ImageSearchClient.Src.WIKIMEDIA, source)
        assertEquals(1, results.size)
    }

    @Test
    fun emptyWhenBothSourcesFail() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val (source, results) = runBlocking { client().search("nature", 5, null, ImageSearchClient.Src.OPENVERSE) }

        assertTrue("honest empty result expected, source=$source", results.isEmpty())
    }

    @Test
    fun emptyWhenNoClientProvided() {
        val (source, results) = runBlocking {
            ImageSearchClient(null).search("nature", 5, null, ImageSearchClient.Src.OPENVERSE)
        }
        assertTrue(results.isEmpty())
        assertEquals(ImageSearchClient.Src.OPENVERSE, source)
    }

    @Test
    fun openverseSkipsEntriesWithoutUrl() {
        server.enqueue(json("""{"result_count":1,"results":[{"id":"x","title":"no url","creator":"c"}]}"""))

        val (_, results) = runBlocking { client().search("nature", 5, null, ImageSearchClient.Src.OPENVERSE) }

        assertTrue("entries without a direct url must be skipped", results.isEmpty())
    }
}
