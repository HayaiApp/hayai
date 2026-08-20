package dev.ahmedmohamed.hayai.novel.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SChapter
import java.io.InputStream

interface NovelSource : CatalogueSource {
    override val isNovelSource: Boolean
        get() = true

    suspend fun getChapterDocument(chapter: SChapter): NovelDocument = NovelDocumentLoader.load(this, chapter)
}

/** Optional boundary for source-owned assets referenced by a novel document. */
interface NovelAssetProvider {
    suspend fun getChapterAsset(
        chapterUrl: String,
        assetPath: String,
    ): InputStream?
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
