package dev.ahmedmohamed.hayai.novel.source.local

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

internal data class ComicInfoMetadata(
    val values: Map<String, String>,
) {
    fun applyTo(manga: SManga) {
        values["Series"]?.takeIf(String::isNotBlank)?.let { manga.title = it }
        values["Writer"]?.takeIf(String::isNotBlank)?.let { manga.author = it }
        values["Summary"]?.takeIf(String::isNotBlank)?.let { manga.description = it }
        listOf("Genre", "Tags", "Categories")
            .mapNotNull(values::get)
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?.let { manga.genre = it }
        listOf("Penciller", "Inker", "Colorist", "Letterer", "CoverArtist")
            .mapNotNull(values::get)
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?.let { manga.artist = it }
        values["PublishingStatusTachiyomi"]?.let { manga.status = publishingStatus(it) }
    }

    fun applyTo(chapter: SChapter) {
        values["Title"]?.takeIf(String::isNotBlank)?.let { chapter.name = it }
        values["Number"]?.toFloatOrNull()?.let { chapter.chapter_number = it }
        values["Translator"]?.takeIf(String::isNotBlank)?.let { chapter.scanlator = it }
    }

    companion object {
        fun parse(input: InputStream): ComicInfoMetadata {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setExpandEntityReferences(false)
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }
            val root = factory.newDocumentBuilder().parse(input).documentElement
            val values = buildMap {
                for (index in 0 until root.childNodes.length) {
                    val node = root.childNodes.item(index)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        put(node.localName ?: node.nodeName.substringAfter(':'), node.textContent.trim())
                    }
                }
            }
            return ComicInfoMetadata(values)
        }

        private fun publishingStatus(value: String): Int =
            when (value.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "licensed" -> SManga.LICENSED
                "publishing finished" -> SManga.PUBLISHING_FINISHED
                "cancelled" -> SManga.CANCELLED
                "on hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
    }
}
