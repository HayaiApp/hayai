package dev.ahmedmohamed.hayai.novel.translation

import dev.ahmedmohamed.hayai.novel.reader.NovelContentProcessor
import dev.ahmedmohamed.hayai.novel.reader.NovelContentOptions
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import org.jsoup.Jsoup

internal object NovelTranslationText {
    private val OPTIONS =
        NovelContentOptions(
            hideChapterTitle = false,
            forceLowercase = false,
            blockMedia = true,
            keepEmbeddedCss = false,
            keepEmbeddedJs = false,
            showRawHtml = false,
            autoSplitText = false,
            autoSplitWordCount = 100,
            regexReplacements = "[]",
        )

    fun canonical(document: NovelDocument): String {
        val processed = NovelContentProcessor().process(document, chapterName = "", OPTIONS)
        return Jsoup
            .parseBodyFragment(processed.html)
            .body()
            .wholeText()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
