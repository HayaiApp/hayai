package dev.ahmedmohamed.hayai.novel.download

import dev.ahmedmohamed.hayai.novel.archive.HtmlAssetRewriter
import dev.ahmedmohamed.hayai.novel.archive.novelImageUrl
import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import java.net.URI
import java.net.URLDecoder

internal object NovelAssetReferences {
    fun extract(document: NovelDocument): Set<String> =
        when (document.contentType) {
            NovelContentType.Html -> HtmlAssetRewriter.extractResourceUrls(document.content)
            NovelContentType.Markdown -> MARKDOWN_RESOURCE.findAll(document.content).map { it.groupValues[1] }.toCollection(linkedSetOf())
            NovelContentType.PlainText -> emptySet()
        }

    fun rewrite(
        document: NovelDocument,
        offlinePaths: Map<String, String>,
    ): NovelDocument {
        val rewrite: (String) -> String? = { reference -> offlinePaths[reference]?.let(::novelImageUrl) }
        val content =
            when (document.contentType) {
                NovelContentType.Html -> HtmlAssetRewriter.rewriteHtml(document.content, rewrite)
                NovelContentType.Markdown ->
                    MARKDOWN_RESOURCE.replace(document.content) { match ->
                        val reference = match.groupValues[1]
                        match.value.replace(reference, rewrite(reference) ?: reference)
                    }
                NovelContentType.PlainText -> document.content
            }
        return document.copy(content = content, baseUrl = null)
    }

    fun providerPath(reference: String): String? {
        val uri = runCatching { URI(reference) }.getOrNull() ?: return reference
        if (uri.scheme?.lowercase() !in ASSET_SCHEMES) return reference
        val encoded =
            listOf(uri.rawAuthority.orEmpty().trim('/'), uri.rawPath.orEmpty().trimStart('/'))
                .filter(String::isNotBlank)
                .joinToString("/")
        if (encoded.isBlank()) return null
        return runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name()) }
            .getOrNull()
            ?.replace('\\', '/')
            ?.takeUnless { decoded -> decoded.startsWith('/') || decoded.split('/').any { it == ".." } }
    }

    fun isSourceAsset(reference: String): Boolean =
        runCatching { URI(reference).scheme?.lowercase() in ASSET_SCHEMES }.getOrDefault(false)

    private val MARKDOWN_RESOURCE = Regex("!\\[[^]]*]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)")
    private val ASSET_SCHEMES = setOf("hayai-novel-image", "novel-image")
}
