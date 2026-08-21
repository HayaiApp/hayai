package dev.ahmedmohamed.hayai.novel.tracker.services

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class NovelTrackerHttpTest {
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
    fun `response body is bounded before parsing`() {
        server.enqueue(MockResponse().setBody("x".repeat(17)))
        val http = NovelTrackerHttp(OkHttpClient(), maximumResponseBytes = 16)

        assertThrows(NovelTrackerFailure.ResponseTooLarge::class.java) {
            runBlocking { http.execute(Request.Builder().url(server.url("/")).build()) }
        }
    }

    @Test
    fun `authentication and rate limits retain typed failures`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "42"))
        val http = NovelTrackerHttp(OkHttpClient())

        assertThrows(NovelTrackerFailure.SessionExpired::class.java) {
            runBlocking { http.execute(Request.Builder().url(server.url("/auth")).build()) }
        }
        val error =
            assertThrows(NovelTrackerFailure.RateLimited::class.java) {
                runBlocking { http.execute(Request.Builder().url(server.url("/limited")).build()) }
            }
        assertEquals(42L, error.retryAfterSeconds)
    }
}

