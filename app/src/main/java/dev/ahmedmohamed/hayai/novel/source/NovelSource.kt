package dev.ahmedmohamed.hayai.novel.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SChapter

interface NovelSource : CatalogueSource {
    override val isNovelSource: Boolean
        get() = true

    suspend fun getChapterDocument(chapter: SChapter): NovelDocument = NovelDocumentLoader.load(this, chapter)
}

data class NovelDocument(
    val content: String,
    val contentType: NovelContentType,
    val baseUrl: String? = null,
)

enum class NovelContentType {
    Html,
    Markdown,
    PlainText,
}
