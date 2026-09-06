package dev.ahmedmohamed.hayai.adult.eh.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class EhBrowserCookiesTest {
    @Test
    fun `retry reads fresh browser clearance while retaining typed account cookies`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().addHeader("Set-Cookie", "__cf_bm=renewed; Path=/"))
            val url = server.url("/")
            val jar = BrowserJar()
            jar.saveFromResponse(url, listOf(cookie(url, "cf_clearance=old"), cookie(url, "ipb_member_id=browser")))
            val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 403) {
                    response.close()
                    jar.saveFromResponse(url, listOf(cookie(url, "cf_clearance=solved")))
                    chain.proceed(chain.request())
                } else {
                    response
                }
            }.build().withEhBrowserCookies()
            client.newCall(Request.Builder().url(url).header("Cookie", "ipb_member_id=typed; sp=2").build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals("ipb_member_id=typed; sp=2; cf_clearance=old", server.takeRequest().getHeader("Cookie"))
            assertEquals("ipb_member_id=typed; sp=2; cf_clearance=solved", server.takeRequest().getHeader("Cookie"))
            assertEquals("renewed", jar.loadForRequest(url).single { it.name == "__cf_bm" }.value)
            assertEquals("browser", jar.loadForRequest(url).single { it.name == "ipb_member_id" }.value)
        }
    }

    @Test
    fun `clearance is scoped to the request host and secure transport`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse())
            val url = server.url("/")
            val jar = BrowserJar()
            jar.saveFromResponse(url, listOf(
                Cookie.Builder().name("cf_clearance").value("other-host").hostOnlyDomain("example.com").build(),
                Cookie.Builder().name("__cf_bm").value("secure-only").hostOnlyDomain(url.host).secure().build(),
            ))
            val client = OkHttpClient.Builder().cookieJar(jar).build().withEhBrowserCookies()
            client.newCall(Request.Builder().url(url).header("Cookie", "ipb_member_id=typed").build()).execute().close()
            assertEquals("ipb_member_id=typed", server.takeRequest().getHeader("Cookie"))
        }
    }

    private fun cookie(url: HttpUrl, value: String): Cookie = requireNotNull(Cookie.parse(url, "$value; Path=/"))

    private class BrowserJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies += cookie
            }
        }
    }
}
