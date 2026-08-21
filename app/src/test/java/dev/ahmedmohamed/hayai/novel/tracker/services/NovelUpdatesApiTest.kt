package dev.ahmedmohamed.hayai.novel.tracker.services

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class NovelUpdatesApiTest {
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

    @Test
    fun `search extracts numeric id content and canonical tracking fragment`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """<div class="search_main_box_nu"><span id="sid123"></span><div class="search_title"><a href="${server.url("series/a-novel/")}">A Novel</a></div><div class="search_img_nu"><img src="${server.url("cover.jpg")}"></div><div class="search_body_nu">A summary</div></div>""",
            ),
        )
        val api = NovelUpdatesApi(NovelTrackerHttp(OkHttpClient()), { "session=valid" }, server.url("").toString().removeSuffix("/"))

        val result = api.search("A Novel").single()

        assertEquals("123", result.remoteKey)
        assert(result.url.endsWith("#hayai-nu=123"))
        assertEquals("A Novel", result.title)
    }

    @Test
    fun `refresh preserves decimal chapter progress from notes`() = runBlocking {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path?.startsWith("/series/?p=123") == true ->
                            MockResponse().setBody("<div class='sticon'><span class='sttitle'><a href='reading-list/?list=0'>Reading</a></span></div>")
                        request.path == "/wp-admin/admin-ajax.php" ->
                            MockResponse().setBody("{\"notes\":\"private note&lt;br/&gt;total chapters read: 12.5\",\"tags\":\"favorite\"}0")
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        val baseUrl = server.url("").toString().removeSuffix("/")
        val api = NovelUpdatesApi(NovelTrackerHttp(OkHttpClient()), { "session=valid" }, baseUrl)
        val record = NovelTrackerRecord("123", "A Novel", "$baseUrl/series/a-novel/#hayai-nu=123", NovelReadingStatus.Reading, 10f, 0f, 0, 0, 0)

        val patch = api.refresh(record)

        assertEquals(12.5f, patch.chapterRead)
        assertEquals(NovelReadingStatus.Reading, patch.status)
    }

    @Test
    fun `update preserves private notes and replaces only the progress marker`() = runBlocking {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/wp-admin/admin-ajax.php" && request.body.clone().readUtf8().contains("wi_notestagsfic")) {
                        MockResponse().setBody("{\"notes\":\"private note&lt;br/&gt;total chapters read: 4\",\"tags\":\"favorite\"}0")
                    } else {
                        MockResponse().setBody("ok")
                    }
            }
        val baseUrl = server.url("").toString().removeSuffix("/")
        val api = NovelUpdatesApi(NovelTrackerHttp(OkHttpClient()), { "session=valid" }, baseUrl)
        val record = NovelTrackerRecord("123", "A Novel", "$baseUrl/series/a-novel/#hayai-nu=123", NovelReadingStatus.Reading, 9.5f, 0f, 0, 0, 0)

        api.update(record)

        server.takeRequest()
        server.takeRequest()
        val saveRequest = server.takeRequest()
        val decodedBody = URLDecoder.decode(saveRequest.body.readUtf8(), Charsets.UTF_8.name())
        assertTrue(decodedBody.contains("private note&lt;br/&gt;total chapters read: 9.5"))
        assertTrue(decodedBody.contains("strTags=favorite"))
    }
}
