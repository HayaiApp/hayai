package dev.ahmedmohamed.hayai.novel.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter

object NovelDocumentLoader {
    suspend fun load(
        source: Source,
        chapter: SChapter,
    ): NovelDocument {
        require(source.isNovelSource) { "This source does not provide novel text." }

        val pages = source.getPageList(chapter)
        require(pages.isNotEmpty()) { "The novel chapter has no pages." }
        val contents = mutableListOf<String>()
        pages.forEach { page ->
            contents += page.text ?: source.fetchPageText(page).also { page.text = it }
        }
        val content = contents.joinToString("\n\n")

        return NovelDocument(
            content = content,
            contentType = detectContentType(pages, content),
            baseUrl = pages.first().url.takeIf(String::isNotBlank),
        )
    }

    internal fun detectContentType(
        pages: List<Page>,
        content: String,
    ): NovelContentType {
        val extensions =
            pages.mapNotNull { page ->
                page.url
                    .substringBefore('#')
                    .substringBefore('?')
                    .substringAfterLast('.', "")
                    .lowercase()
                    .takeIf(String::isNotBlank)
            }.toSet()

        return when {
            extensions.isNotEmpty() && extensions.all { it in MARKDOWN_EXTENSIONS } -> NovelContentType.Markdown
            extensions.isNotEmpty() && extensions.all { it in PLAIN_TEXT_EXTENSIONS } -> NovelContentType.PlainText
            extensions.any { it in HTML_EXTENSIONS } -> NovelContentType.Html
            content.contains(HTML_TAG, ignoreCase = true) -> NovelContentType.Html
            else -> NovelContentType.Html
        }
    }

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
    private val PLAIN_TEXT_EXTENSIONS = setOf("txt", "text")
    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
    private const val HTML_TAG = "<html"
}
