package dev.ahmedmohamed.hayai.novel.lookup

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@JvmInline
value class NovelSelectionQuery private constructor(val value: String) {
    companion object {
        private const val MAX_QUERY_CHARS = 2_048
        private const val MAX_QUERY_BYTES = 2_048

        fun parse(raw: String): Result<NovelSelectionQuery> =
            runCatching {
                val value = raw.trim()
                when {
                    value.isEmpty() -> throw NovelSelectionQueryException(NovelSelectionQueryFailure.Blank)
                    value.length > MAX_QUERY_CHARS || value.toByteArray(Charsets.UTF_8).size > MAX_QUERY_BYTES ->
                        throw NovelSelectionQueryException(NovelSelectionQueryFailure.TooLong)
                }
                NovelSelectionQuery(value)
            }
    }
}

enum class NovelSelectionQueryFailure { Blank, TooLong }

class NovelSelectionQueryException(val failure: NovelSelectionQueryFailure) : IllegalArgumentException()

enum class NovelLookupAction { Define, GoogleTranslate, SearchWeb }

data class NovelLookupRequest(
    val action: NovelLookupAction,
    val query: NovelSelectionQuery,
    val sourceLanguage: String,
    val targetLanguage: String,
)

enum class NovelLookupAppIntent { GoogleTranslateProcessText, GoogleTranslateSend }

sealed interface NovelLookupRoute {
    val webUrl: HttpUrl

    data class Web(override val webUrl: HttpUrl) : NovelLookupRoute

    data class AppThenWeb(
        val appIntents: List<NovelLookupAppIntent>,
        override val webUrl: HttpUrl,
    ) : NovelLookupRoute
}

enum class NovelLookupLaunchResult { ExternalApp, CustomTab, WebSheet, Unavailable }

object NovelLookupRouter {
    private val googleSearch = "https://www.google.com/search".toHttpUrl()
    private val googleTranslate = "https://translate.google.com/".toHttpUrl()
    private val language = Regex("auto|[a-z]{2,3}(?:-[A-Z]{2})?")

    fun route(request: NovelLookupRequest): NovelLookupRoute =
        when (request.action) {
            NovelLookupAction.Define -> NovelLookupRoute.Web(searchUrl("define ${request.query.value}"))
            NovelLookupAction.SearchWeb -> NovelLookupRoute.Web(searchUrl(request.query.value))
            NovelLookupAction.GoogleTranslate ->
                NovelLookupRoute.AppThenWeb(
                    appIntents =
                        listOf(
                            NovelLookupAppIntent.GoogleTranslateProcessText,
                            NovelLookupAppIntent.GoogleTranslateSend,
                        ),
                    webUrl =
                        googleTranslate.newBuilder()
                            .addQueryParameter("sl", request.sourceLanguage.validLanguage("auto"))
                            .addQueryParameter("tl", request.targetLanguage.validLanguage("en").takeUnless { it == "auto" } ?: "en")
                            .addQueryParameter("text", request.query.value)
                            .addQueryParameter("op", "translate")
                            .build(),
                )
        }

    private fun searchUrl(query: String) = googleSearch.newBuilder().addQueryParameter("q", query).build()

    private fun String.validLanguage(fallback: String) = trim().takeIf(language::matches) ?: fallback
}
