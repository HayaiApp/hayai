package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.time.Instant

data class EnhancedDetails(
    val title: String? = null,
    val alternateTitle: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
)

object EnhancedDetailsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        family: SourceFamily,
        body: String,
        location: String,
    ): EnhancedDetails? = runCatching {
        when (family) {
            SourceFamily.NHentai -> parseNhentai(body)
            SourceFamily.MangaDex -> parseMangaDex(body)
            SourceFamily.Lanraragi -> parseLanraragi(body)
            SourceFamily.EightMuses -> parseEightMuses(body, location)
            SourceFamily.HBrowse -> parseHBrowse(body, location)
            SourceFamily.Pururin -> parsePururin(body, location)
            else -> null
        }
    }.getOrNull()

    private fun parseEightMuses(body: String, location: String): EnhancedDetails {
        val document = Jsoup.parse(body, location)
        val crumbs = document.select(".top-menu-breadcrumb li a").map { it.text().trim() }.filter(String::isNotBlank)
        val tags = document.select(".album-tags a").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val artist = crumbs.getOrNull(1)
        return EnhancedDetails(
            title = crumbs.lastOrNull(),
            artist = artist,
            description = description(listOf("Artist" to artist, "Tags" to tags.joinToString().takeIf(String::isNotBlank))),
            genres = tags,
            thumbnailUrl = document.selectFirst(".gallery .c-tile .lazyload")?.absUrl("data-src")?.takeIf(String::isNotBlank),
        )
    }

    private fun parseHBrowse(body: String, location: String): EnhancedDetails {
        val document = Jsoup.parse(body, location)
        val fields = document.select("#main .listTable tr").filter { it.childrenSize() > 1 }.associate {
            it.child(0).text().trim().lowercase() to it.child(1)
        }
        val tags = fields.filterKeys { it !in setOf("title", "length") }.flatMap { (namespace, value) ->
            value.select("a").map { "$namespace: ${it.text().trim()}" }
        }
        val length = fields["length"]?.text()?.substringBefore(' ')?.toIntOrNull()
        return EnhancedDetails(
            title = fields["title"]?.text()?.trim(),
            description = description(listOf("Length" to length?.let { "$it pages" }, "Tags" to tags.joinToString().takeIf(String::isNotBlank))),
            genres = tags,
        )
    }

    private fun parsePururin(body: String, location: String): EnhancedDetails {
        val document = Jsoup.parse(body, location)
        val wrapper = document.selectFirst(".content-wrapper") ?: document
        val fields = wrapper.select(".table-gallery-info > tbody > tr").filter { it.childrenSize() > 1 }
        val tags = fields.flatMap { row ->
            val namespace = row.child(0).text().trim().lowercase().removeSuffix(":")
            row.child(1).select("a").map { "$namespace: ${it.text().trim()}" }
        }.distinct()
        val fieldText = fields.associate { it.child(0).text().trim().lowercase().removeSuffix(":") to it.child(1).text().trim() }
        val pages = fieldText["pages"].orEmpty().substringBefore('(').trim().takeIf(String::isNotBlank)
        val fileSize = fieldText["pages"].orEmpty().substringAfter('(', "").removeSuffix(")").trim().takeIf(String::isNotBlank)
        val ratings = fields.singleOrNull {
            it.child(0).text().trim().lowercase().removeSuffix(":") == "ratings"
        }?.child(1)
        val ratingCount = ratings?.selectFirst("[itemprop=ratingCount]")?.attr("content")?.takeIf(String::isNotBlank)
        val ratingValue = ratings?.selectFirst("[itemprop=ratingValue]")?.attr("content")?.takeIf(String::isNotBlank)
        return EnhancedDetails(
            title = wrapper.selectFirst(".title h1")?.text()?.trim(),
            alternateTitle = wrapper.selectFirst(".alt-title")?.text()?.trim()?.takeIf(String::isNotBlank),
            description = description(
                listOf(
                    "Pages" to pages,
                    "File size" to fileSize,
                    "Rating" to ratingValue,
                    "Rating count" to ratingCount,
                    "Uploader" to fieldText["uploader"],
                    "Tags" to tags.joinToString().takeIf(String::isNotBlank),
                ),
            ),
            genres = tags,
            thumbnailUrl = wrapper.selectFirst(".cover-wrapper v-lazy-image")?.absUrl("src")?.takeIf(String::isNotBlank),
        )
    }

    private fun parseNhentai(body: String): EnhancedDetails {
        val root = json.parseToJsonElement(body).jsonObject
        val titles = root.objectOrNull("title")
        val tags = root.arrayOrEmpty("tags").mapNotNull { element ->
            val tag = element as? JsonObject ?: return@mapNotNull null
            val type = tag.string("type") ?: return@mapNotNull null
            val name = tag.string("name") ?: return@mapNotNull null
            "$type: $name"
        }
        return EnhancedDetails(
            title = titles?.string("english") ?: titles?.string("pretty"),
            alternateTitle = titles?.string("japanese"),
            description = description(
                listOf(
                    "ID" to root.long("id")?.toString(),
                    "Media ID" to root.string("media_id"),
                    "Posted" to root.long("upload_date")?.let { Instant.ofEpochSecond(it).toString() },
                    "Pages" to root.long("num_pages")?.toString(),
                    "Favorites" to root.long("num_favorites")?.toString(),
                    "Scanlator" to root.string("scanlator"),
                    "English title" to titles?.string("english"),
                    "Japanese title" to titles?.string("japanese"),
                    "Short title" to titles?.string("pretty"),
                    "Tags" to tags.joinToString().takeIf(String::isNotBlank),
                ),
            ),
            genres = tags,
        )
    }

    private fun parseMangaDex(body: String): EnhancedDetails {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root.objectOrNull("data") ?: root
        val attributes = data.objectOrNull("attributes") ?: return EnhancedDetails()
        val titleObject = attributes.objectOrNull("title")
        val title = titleObject?.values?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
        val altTitles = attributes.arrayOrEmpty("altTitles").flatMap { (it as? JsonObject)?.values.orEmpty() }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.distinct()
        val descriptionMap = attributes.objectOrNull("description")
        val descriptions = buildList {
            descriptionMap?.string("en")?.let(::add)
            descriptionMap?.forEach { (language, value) ->
                if (language != "en") (value as? JsonPrimitive)?.contentOrNull?.let(::add)
            }
        }
        val tags = attributes.arrayOrEmpty("tags").mapNotNull { element ->
            val names = (element as? JsonObject)?.objectOrNull("attributes")?.objectOrNull("name") ?: return@mapNotNull null
            names.string("en") ?: names.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
        }.distinct()
        return EnhancedDetails(
            title = title,
            alternateTitle = altTitles.firstOrNull(),
            description = description(listOf("Description" to descriptions.firstOrNull(), "Alternative titles" to altTitles.joinToString().takeIf(String::isNotBlank))),
            genres = tags,
        )
    }

    private fun parseLanraragi(body: String): EnhancedDetails {
        val root = json.parseToJsonElement(body).jsonObject
        val tags = root.string("tags").orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        return EnhancedDetails(
            title = root.string("title"),
            description = description(
                listOf(
                    "Summary" to root.string("summary"),
                    "Pages" to root.long("pagecount")?.toString(),
                    "File" to root.string("filename"),
                    "Archive type" to root.string("extension")?.uppercase(),
                    "Tags" to tags.joinToString().takeIf(String::isNotBlank),
                ),
            ),
            genres = tags,
        )
    }

    private fun description(rows: List<Pair<String, String?>>): String? =
        rows.mapNotNull { (label, value) -> value?.trim()?.takeIf(String::isNotBlank)?.let { "**$label:** $it" } }
            .takeIf(List<String>::isNotEmpty)?.joinToString("\n")

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.arrayOrEmpty(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
}
