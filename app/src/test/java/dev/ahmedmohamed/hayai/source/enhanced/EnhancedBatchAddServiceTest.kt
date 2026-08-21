package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceFamily
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchAddService
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchEntry
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchFailure
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchInputParser
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchItemResult
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchLibraryGateway
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedImportTarget
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedResolveResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedBatchAddServiceTest {
    @Test
    fun `input parser trims and deduplicates without accepting credentials`() {
        val plan = EnhancedBatchInputParser.parse(
            """
                https://nhentai.net/g/1/
                https://nhentai.net/g/1/
                https://pururin.me/gallery/2/title
            """.trimIndent(),
        )

        assertEquals(2, plan.entries.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `input parser rejects credential-bearing URLs`() {
        EnhancedBatchInputParser.parse("https://user:secret@nhentai.net/g/1/")
    }

    @Test
    fun `service deduplicates canonical galleries across different submitted links`() = runBlocking {
        val gateway = FakeBatchGateway()
        val report = EnhancedBatchAddService(gateway, retryDelayMillis = 0).run(
            EnhancedBatchInputParser.parse(
                """
                    https://nhentai.net/g/1/
                    https://nhentai.net/g/1/2/
                """.trimIndent(),
            ),
        )

        assertEquals(1, report.added)
        assertEquals(1, report.duplicates)
        assertEquals(1, gateway.addCalls)
    }

    @Test
    fun `service retries only network failures and reports the final success`() = runBlocking {
        val gateway = FakeBatchGateway(networkFailures = 2)
        val report = EnhancedBatchAddService(gateway, maxNetworkAttempts = 3, retryDelayMillis = 0).run(
            EnhancedBatchInputParser.parse("https://nhentai.net/g/1/"),
        )

        assertEquals(1, report.added)
        assertEquals(3, gateway.addCalls)
    }

    @Test
    fun `service preserves unsupported source failures`() = runBlocking {
        val gateway = FakeBatchGateway(unsupported = true)
        val report = EnhancedBatchAddService(gateway, retryDelayMillis = 0).run(
            EnhancedBatchInputParser.parse("https://example.com/gallery/1"),
        )

        assertTrue((report.results.single() as EnhancedBatchItemResult.Failed).reason is EnhancedBatchFailure.UnsupportedSource)
    }

    @Test
    fun `cancellation interrupts an in-flight item without reporting a false failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gateway = object : EnhancedBatchLibraryGateway {
            override suspend fun resolve(entry: EnhancedBatchEntry) = EnhancedResolveResult.Match(
                EnhancedImportTarget(1, "NHentai", SourceFamily.NHentai, "/g/1/", entry.url),
            )

            override suspend fun addToLibrary(target: EnhancedImportTarget): EnhancedBatchItemResult {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch {
            EnhancedBatchAddService(gateway, retryDelayMillis = 0).run(
                EnhancedBatchInputParser.parse("https://nhentai.net/g/1/"),
            )
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }
}

private class FakeBatchGateway(
    private var networkFailures: Int = 0,
    private val unsupported: Boolean = false,
) : EnhancedBatchLibraryGateway {
    var addCalls = 0

    override suspend fun resolve(entry: EnhancedBatchEntry): EnhancedResolveResult {
        if (unsupported) return EnhancedResolveResult.Failure(EnhancedBatchFailure.UnsupportedSource())
        return EnhancedResolveResult.Match(
            EnhancedImportTarget(1, "NHentai", SourceFamily.NHentai, "/g/1/", entry.url),
        )
    }

    override suspend fun addToLibrary(target: EnhancedImportTarget): EnhancedBatchItemResult {
        addCalls++
        if (networkFailures-- > 0) {
            return EnhancedBatchItemResult.Failed(target.submittedUrl, EnhancedBatchFailure.Network("temporary"))
        }
        return EnhancedBatchItemResult.Added(target.submittedUrl, target, 10, "Gallery")
    }
}
