package dev.ahmedmohamed.hayai.novel.tracker.services

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RanobeDbApiTest {
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
    fun `valid cookie requires authenticated settings round trip`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"id\":7,\"username\":\"reader\"}"))
        val baseUrl = server.url("").toString().removeSuffix("/")
        val api = RanobeDbApi(NovelTrackerHttp(OkHttpClient()), { "" }, baseUrl)

        api.validateSession("reader", "auth_session=valid-session")

        val request = server.takeRequest()
        assertEquals("/api/v0/user/me", request.path)
        assertEquals("auth_session=valid-session", request.getHeader("Cookie"))
    }

    @Test
    fun `invalid payload and unauthorized response map to different credential failures`() {
        server.enqueue(MockResponse().setBody("not-json"))
        server.enqueue(MockResponse().setResponseCode(401))
        val baseUrl = server.url("").toString().removeSuffix("/")
        val api = RanobeDbApi(NovelTrackerHttp(OkHttpClient()), { "" }, baseUrl)

        assertThrows(NovelTrackerFailure.InvalidResponse::class.java) {
            runBlocking { api.validateSession("reader", "auth_session=expired") }
        }
        assertThrows(NovelTrackerFailure.SessionExpired::class.java) {
            runBlocking { api.validateSession("reader", "auth_session=rejected") }
        }
    }
}
