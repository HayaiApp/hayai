package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceFamily
import java.net.URI

object EnhancedSourceUrlMapper {
    fun map(
        definition: EnhancedSourceDefinition,
        sourceBaseUrl: String,
        input: String,
    ): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val sourceHost = runCatching { URI(sourceBaseUrl).host?.lowercase() }.getOrNull()
        val host = uri.host?.lowercase() ?: return null
        if (definition.family == SourceFamily.Lanraragi) {
            if (sourceHost == null || host != sourceHost) return null
        } else if (host !in definition.hosts) {
            return null
        }

        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        return when (definition.family) {
            SourceFamily.EightMuses -> mapEightMuses(segments)
            SourceFamily.HBrowse -> segments.firstOrNull()?.takeIf(::isNumericId)?.let { "/$it/c00001/" }
            SourceFamily.Pururin -> mapPururin(segments)
            SourceFamily.NHentai -> segments.takeIf { it.firstOrNull().equals("g", true) }
                ?.getOrNull(1)?.takeIf(::isNumericId)?.let { "/g/$it/" }
            SourceFamily.MangaDex -> segments.takeIf { it.firstOrNull()?.lowercase() in setOf("title", "manga") }
                ?.getOrNull(1)?.takeIf(::isUuid)?.let { "/manga/$it" }
            SourceFamily.Lanraragi -> uri.rawPath.orEmpty().ifBlank { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty()
            else -> null
        }
    }

    private fun mapEightMuses(segments: List<String>): String? {
        val comics = segments.indexOfFirst { it.equals("comics", true) }
        if (comics < 0) return null
        val kind = segments.getOrNull(comics + 1)?.lowercase()
        if (kind !in setOf("album", "picture")) return null
        val content = segments.drop(comics + 2).filter(::isSafeSegment).toMutableList()
        if (kind == "picture" && content.isNotEmpty()) content.removeAt(content.lastIndex)
        return content.takeIf(List<String>::isNotEmpty)?.joinToString("/", prefix = "/comics/album/")
    }

    private fun mapPururin(segments: List<String>): String? {
        if (!segments.firstOrNull().equals("gallery", true)) return null
        val id = segments.getOrNull(1)?.takeIf(::isNumericId) ?: return null
        val slug = segments.getOrNull(2)?.takeIf(::isSafeSegment) ?: "-"
        return "/gallery/$id/$slug"
    }

    private fun isNumericId(value: String): Boolean = value.toLongOrNull()?.let { it > 0 } == true
    private fun isSafeSegment(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._~-]{1,200}"))
    private fun isUuid(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
}
