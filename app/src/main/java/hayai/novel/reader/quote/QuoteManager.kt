package hayai.novel.reader.quote

import yokai.util.koin.get
import android.content.Context
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import yokai.domain.storage.StorageManager
import yokai.data.DatabaseHandler
import java.io.IOException

/**
 * DB-backed manager for quote storage and retrieval.
 * Legacy JSON files are imported once for users who created quotes before the DB-backed model.
 */
class QuoteManager(@Suppress("unused") private val context: Context) {

    private val jsonFormat = Json { prettyPrint = true }

    private val handler: DatabaseHandler by lazy {
        get<DatabaseHandler>()
    }

    private val storageManager: StorageManager by lazy {
        get<StorageManager>()
    }

    private val quotesDir: UniFile?
        get() = storageManager.getQuotesDirectory()

    private fun getQuotesFile(novelId: Long): UniFile? {
        return quotesDir?.findFile("novel_$novelId.json")
    }

    suspend fun saveQuotes(novelId: Long, quotes: List<Quote>) {
        withContext(Dispatchers.IO) {
            try {
                handler.await(true) {
                    series_knowledgeQueries.deleteQuotesForManga(novelId)
                    quotes.forEach { quote ->
                        series_knowledgeQueries.upsertQuote(
                            quote.id,
                            novelId,
                            quote.novelName,
                            quote.chapterName,
                            quote.content,
                            quote.originalContent,
                            quote.translatedContent,
                            quote.language,
                            quote.timestamp,
                        )
                    }
                }
                getQuotesFile(novelId)?.delete()
                Logger.d { "Quotes saved for novel $novelId: ${quotes.size} quotes" }
            } catch (e: IOException) {
                Logger.e(e) { "Failed to save quotes for novel $novelId" }
            } catch (e: SerializationException) {
                Logger.e(e) { "Failed to serialize quotes for novel $novelId" }
            }
        }
    }

    suspend fun loadQuotes(novelId: Long): List<Quote> {
        return withContext(Dispatchers.IO) {
            try {
                val dbQuotes =
                handler.awaitList {
                    series_knowledgeQueries.findQuotes(novelId) { quoteId, _, novelName, chapterName, displayedContent, originalContent, translatedContent, language, timestamp ->
                        Quote(
                            id = quoteId,
                            novelName = novelName,
                            chapterName = chapterName,
                            content = displayedContent,
                            originalContent = originalContent,
                            translatedContent = translatedContent,
                            language = language,
                            timestamp = timestamp,
                        )
                    }
                }
                dbQuotes.ifEmpty { importLegacyQuotes(novelId) }
            } catch (e: IOException) {
                Logger.e(e) { "Failed to load quotes for novel $novelId" }
                emptyList()
            } catch (e: SerializationException) {
                Logger.e(e) { "Failed to deserialize quotes for novel $novelId" }
                emptyList()
            }
        }
    }

    suspend fun addQuote(novelId: Long, quote: Quote) {
        withContext(Dispatchers.IO) {
            try {
                handler.await {
                    series_knowledgeQueries.upsertQuote(
                        quote.id,
                        novelId,
                        quote.novelName,
                        quote.chapterName,
                        quote.content,
                        quote.originalContent,
                        quote.translatedContent,
                        quote.language,
                        quote.timestamp,
                    )
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to add quote for novel $novelId" }
            }
        }
    }

    suspend fun removeQuote(novelId: Long, quoteId: String) {
        withContext(Dispatchers.IO) {
            handler.await { series_knowledgeQueries.deleteQuote(quoteId) }
        }
    }

    suspend fun updateQuote(novelId: Long, updatedQuote: Quote) {
        addQuote(novelId, updatedQuote)
    }

    suspend fun getQuotes(novelId: Long): List<Quote> {
        return loadQuotes(novelId)
    }

    suspend fun clearQuotes(novelId: Long) {
        withContext(Dispatchers.IO) {
            handler.await { series_knowledgeQueries.deleteQuotesForManga(novelId) }
            val file = getQuotesFile(novelId)
            if (file?.exists() == true) {
                file.delete()
            }
        }
    }

    suspend fun getQuoteCount(novelId: Long): Int {
        return withContext(Dispatchers.IO) {
            handler.awaitOne { series_knowledgeQueries.countQuotes(novelId) }.toInt()
        }
    }

    private suspend fun importLegacyQuotes(novelId: Long): List<Quote> {
        val file = getQuotesFile(novelId)
        if (file == null || !file.exists()) return emptyList()
        return try {
            val json = file.openInputStream().use { inputStream ->
                String(inputStream.readBytes())
            }
            val novelQuotes = jsonFormat.decodeFromString<NovelQuotes>(json)
            val quotes = novelQuotes.quotes
            if (quotes.isNotEmpty()) {
                saveQuotes(novelId, quotes)
            }
            quotes
        } catch (e: IOException) {
            Logger.e(e) { "Failed to import legacy quotes for novel $novelId" }
            emptyList()
        } catch (e: SerializationException) {
            Logger.e(e) { "Failed to import legacy quotes JSON for novel $novelId" }
            emptyList()
        }
    }
}

val Context.quoteManager: QuoteManager
    get() = QuoteManager(this)
