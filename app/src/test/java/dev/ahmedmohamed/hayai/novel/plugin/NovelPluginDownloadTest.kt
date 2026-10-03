package dev.ahmedmohamed.hayai.novel.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NovelPluginDownloadTest {
    @Test
    fun `cancel waiting for response cancels the underlying call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/plugin.js")).build())
            val download = async(Dispatchers.IO) { NovelPluginDownload.read(call, 1024) }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            withTimeout(2_000) { download.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(runCatching { download.await() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test
    fun `cancel during body streaming closes the socket without waiting for the full body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1024)).throttleBody(1, 1, TimeUnit.SECONDS))
            server.start()
            val bodyStarted = java.util.concurrent.CountDownLatch(1)
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val source = object : ForwardingSource(body.source()) {
                    private var reads = 0

                    override fun read(sink: Buffer, byteCount: Long): Long {
                        // One byte has arrived; the next read waits for the throttled remainder.
                        if (++reads >= 2) bodyStarted.countDown()
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }.build()
            val call = client.newCall(Request.Builder().url(server.url("/plugin.js")).build())
            val download = async(Dispatchers.IO) { NovelPluginDownload.read(call, 2048) }
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { download.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(runCatching { download.await() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test
    fun `uncancelled disconnect during streaming remains a network failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/plugin.js")).build())
            val failure = runCatching { NovelPluginDownload.read(call, 2048) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure !is CancellationException)
        }
    }

    @Test
    fun `completed download preserves plugin bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("plugin code"))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/plugin.js")).build())
            assertEquals("plugin code", NovelPluginDownload.read(call, 1024).toString(Charsets.UTF_8))
        }
    }
}
