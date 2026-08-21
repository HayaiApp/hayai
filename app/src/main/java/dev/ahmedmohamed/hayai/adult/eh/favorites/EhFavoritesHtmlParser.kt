package dev.ahmedmohamed.hayai.adult.eh.favorites

import org.jsoup.Jsoup

object EhFavoritesHtmlParser {
    fun categories(html: String): List<EhRemoteCategory> {
        require(html.length <= MAX_DOCUMENT_CHARS)
        val names = Jsoup.parse(html).select(".fp:not(.fps)").mapNotNull { element ->
            element.children().getOrNull(2)?.text()?.trim()?.takeIf(String::isNotBlank)
        }
        require(names.size == 10) { "E-Hentai returned ${names.size} favorite categories instead of 10." }
        return names.mapIndexed { index, name -> EhRemoteCategory(EhFavoriteSlot(index), name) }
    }

    fun existingNote(html: String): String {
        require(html.length <= MAX_DOCUMENT_CHARS)
        return Jsoup.parse(html).selectFirst("textarea[name=favnote]")?.text()?.take(MAX_NOTE_CHARS).orEmpty()
    }

    const val MAX_DOCUMENT_CHARS = 8 * 1024 * 1024
    private const val MAX_NOTE_CHARS = 20_000
}
