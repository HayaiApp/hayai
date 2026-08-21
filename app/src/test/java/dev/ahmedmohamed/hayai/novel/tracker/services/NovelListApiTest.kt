package dev.ahmedmohamed.hayai.novel.tracker.services

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Base64

class NovelListApiTest {
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
    fun `search parses bounded valid entries and preserves uuid in url`() = runBlocking {
        val uuid = "51f55f33-2d67-46dc-a444-a9ad7fcc4198"
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"$uuid","english_title":"A Novel","slug":"a-novel","cover_image_link":"https://img.test/a.jpg","description":"Summary"},{"id":"bad","title":"Ignored"}]""",
            ),
        )
        val api = NovelListApi(NovelTrackerHttp(OkHttpClient()), { jwt(4_000) }, server.url("").toString().removeSuffix("/"), "https://www.novellist.co") { 1_000 }

        val result = api.search("A Novel")

        assertEquals(1, result.size)
        assertEquals(uuid, result.single().remoteKey)
        assert(result.single().url.endsWith("#$uuid"))
    }

    @Test
    fun `expired access token is rejected before persistence`() {
        val api = NovelListApi(NovelTrackerHttp(OkHttpClient()), { "" }, nowEpochSeconds = { 2_000 })

        assertThrows(NovelTrackerFailure.InvalidCredentials::class.java) {
            runBlocking { api.validateSession("reader", jwt(1_999)) }
        }
    }

    @Test
    fun `valid token requires an authenticated bounded server round trip`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"id\":\"51f55f33-2d67-46dc-a444-a9ad7fcc4198\"}"))
        val token = jwt(4_000)
        val api = NovelListApi(NovelTrackerHttp(OkHttpClient()), { "" }, server.url("").toString().removeSuffix("/")) { 1_000 }

        api.validateSession("reader", token)

        val request = server.takeRequest()
        assertEquals("/api/users/current", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test
    fun `server authentication and rate limit failures remain distinct`() {
        val token = jwt(4_000)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        val api = NovelListApi(NovelTrackerHttp(OkHttpClient()), { "" }, server.url("").toString().removeSuffix("/")) { 1_000 }

        assertThrows(NovelTrackerFailure.SessionExpired::class.java) {
            runBlocking { api.validateSession("reader", token) }
        }
        val limited =
            assertThrows(NovelTrackerFailure.RateLimited::class.java) {
                runBlocking { api.validateSession("reader", token) }
            }
        assertEquals(30L, limited.retryAfterSeconds)
    }

    @Test
    fun `update maps chapter status and score and authenticates the request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val token = jwt(4_000)
        val uuid = "51f55f33-2d67-46dc-a444-a9ad7fcc4198"
        val api = NovelListApi(NovelTrackerHttp(OkHttpClient()), { token }, server.url("").toString().removeSuffix("/"))
        val record = NovelTrackerRecord(uuid, "A Novel", "https://www.novellist.co/novels/a#$uuid", NovelReadingStatus.Completed, 12.5f, 8.8f, 20, 0, 0)

        api.update(record)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertTrue(body.contains("\"status\":\"COMPLETED\""))
        assertTrue(body.contains("\"chapter_count\":12"))
        assertTrue(body.contains("\"rating\":8"))
    }

    private fun jwt(expiry: Long): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return "${encode("{\"alg\":\"none\"}")}.${encode("{\"exp\":$expiry}")}.signature"
    }
}
