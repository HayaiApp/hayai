package dev.ahmedmohamed.hayai.novel.plugin

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Cancellation closes the socket during both response waiting and streaming body reads. */
object NovelPluginDownload {
    suspend fun read(call: Call, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response ->
                novelRequire(response.isSuccessful, NovelFailure.Code.PluginHttp, response.code)
                val body = response.body
                val length = body.contentLength()
                novelRequire(length <= maxBytes, NovelFailure.Code.PluginResponseTooLarge)
                val input = body.byteStream()
                val output = ByteArrayOutputStream(if (length in 1..maxBytes) length.toInt() else 8192)
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    novelRequire(total <= maxBytes, NovelFailure.Code.PluginResponseTooLarge)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellation.cancel()
        }
    }
}
