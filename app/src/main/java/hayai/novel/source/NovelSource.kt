package hayai.novel.source

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import hayai.novel.js.NovelJsBridge
import hayai.novel.js.NovelJsRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

/**
 * A CatalogueSource implementation backed by an LNReader JavaScript plugin.
 * Delegates all data fetching to the JS plugin via NovelJsRuntime.
 * Novels are mapped to SManga, chapters to SChapter - treated identically to manga
 * throughout the app, with the only difference at the reader layer (text vs images).
 */
class NovelSource(
    val pluginId: String,
    private val pluginName: String,
    override val lang: String,
    private val siteUrl: String,
    /**
     * Lazy provider for the plugin's JS source. Called on each runtime initialization (cold
     * start and after [destroy]) so we don't have to hold the full source string — typically
     * tens to hundreds of KB per plugin — in memory for the lifetime of the source object.
     */
    private val pluginCodeProvider: () -> String,
    private val pluginFilters: JsonObject? = null,
    val iconUrl: String?,
    /**
     * On-disk icon file under the plugin directory (typically `<pluginDir>/icon.png`).
     * When non-null and the file exists, UI binders prefer this over [iconUrl] so source
     * rows render without going through the network on cold starts (issue #10).
     */
    val iconFile: File? = null,
    private val context: Context,
    private val bridge: NovelJsBridge,
    private val userAgent: String = "",
) : CatalogueSource, TextSource {

    private val json = Json { ignoreUnknownKeys = true }
    private val filterDefinitions = parseFilterDefinitions(pluginFilters)

    override val name: String get() = pluginName
    val baseUrl: String get() = siteUrl
    override val webViewUrl: String get() = baseUrl
    override val isNovelSource: Boolean get() = true

    override fun toString() = "$name (${lang.uppercase()})"

    override val id: Long by lazy {
        val key = "novel/${pluginId.lowercase()}/$lang/1"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl (8 * (7 - it)) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    override val supportsLatest: Boolean = true

    // Lazy-initialized JS runtime. Guarded by [runtimeMutex] because ensureRuntime/destroy
    // are called from arbitrary coroutines (browse, reader, downloader) and the suspending
    // initialize() inside the check-then-act would otherwise let a second caller observe a
    // stale `runtime == null` and create a duplicate (leaking the QuickJS native context).
    private val runtimeMutex = Mutex()
    private var runtime: NovelJsRuntime? = null

    private suspend fun ensureRuntime(): NovelJsRuntime = runtimeMutex.withLock {
        runtime?.takeIf { it.isInitialized }?.let { return@withLock it }

        val rt = NovelJsRuntime(context, bridge, pluginId, userAgent)
        // Re-read the JS source on each (re)initialization rather than holding it for the
        // lifetime of this NovelSource. Plugin source files are typically tens-to-hundreds
        // of KB; with many plugins installed that adds up to noticeable resident heap.
        rt.initialize(pluginCodeProvider())
        runtime = rt
        rt
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val rt = ensureRuntime()
        val resultJson = rt.callMethod(
            "popularNovels",
            "$page, {showLatestNovels: false, filters: ${buildFiltersJsArg()}}",
        )
        Logger.d { "NovelSource($pluginId): popularNovels result (first 500 chars): ${resultJson.take(500)}" }
        return parseNovelItems(resultJson)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val rt = ensureRuntime()
        val resultJson = rt.callMethod(
            "popularNovels",
            "$page, {showLatestNovels: true, filters: ${buildFiltersJsArg()}}",
        )
        return parseNovelItems(resultJson)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val rt = ensureRuntime()

        if (query.isBlank()) {
            val resultJson = rt.callMethod(
                "popularNovels",
                "$page, {showLatestNovels: false, filters: ${buildFiltersJsArg(filters)}}",
            )
            return parseNovelItems(resultJson)
        }

        val resultJson = rt.callMethod("searchNovels", "${escapeJsString(query)}, $page")
        return parseNovelItems(resultJson)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val rt = ensureRuntime()
        val resultJson = rt.callMethod("parseNovel", escapeJsString(manga.url))
        return parseSourceNovel(resultJson, manga)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val rt = ensureRuntime()
        val novelJson = rt.callMethod("parseNovel", escapeJsString(manga.url))

        val novelObject = try {
            json.decodeFromString<JsonObject>(novelJson)
        } catch (e: Exception) {
            Logger.e(e) { "NovelSource($pluginId): Failed to decode parseNovel JSON" }
            return emptyList()
        }

        val collected = mutableListOf<SChapter>()
        novelObject["chapters"]?.jsonArray?.let { collected += parseChapterArray(it) }

        val totalPages = novelObject["totalPages"]
            ?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 1
        if (totalPages > 1) {
            val hasParsePage = runCatching {
                rt.evaluateRaw("typeof __plugin.parsePage === 'function'")
            }.getOrDefault("false") == "true"
            if (!hasParsePage) {
                Logger.w { "NovelSource($pluginId): totalPages=$totalPages but parsePage is unavailable" }
            }
            val pageRange = if (hasParsePage) 2..totalPages else emptyList()
            for (pageNum in pageRange) {
                val pageChapters = try {
                    val pageJson = rt.callMethod(
                        "parsePage",
                        "${escapeJsString(manga.url)}, ${escapeJsString(pageNum.toString())}",
                    )
                    json.decodeFromString<JsonObject>(pageJson)["chapters"]?.jsonArray
                        ?.let(::parseChapterArray)
                        .orEmpty()
                } catch (e: Exception) {
                    Logger.w(e) { "NovelSource($pluginId): parsePage($pageNum) failed for ${manga.url}" }
                    emptyList()
                }
                collected += pageChapters
            }
        }

        // LNReader plugins emit chapters chronologically; tachiyomi expects newest-first.
        return collected.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Novel chapters don't have image pages.
        // Return a single placeholder page. The actual text content
        // is loaded by NovelPageLoader via getChapterText().
        return listOf(Page(0, chapter.url))
    }

    override fun getFilterList(): FilterList {
        if (filterDefinitions.isEmpty()) {
            return FilterList()
        }
        return FilterList(filterDefinitions.map { it.toFilter() })
    }

    /**
     * Fetch chapter text content (HTML string).
     * Called by NovelPageLoader and the download system.
     */
    suspend fun getChapterText(chapterUrl: String): String {
        val rt = ensureRuntime()
        val resultJson = rt.callMethod("parseChapter", escapeJsString(chapterUrl))
        // parseChapter returns a string (HTML), JSON-stringified it becomes a quoted string
        return try {
            json.decodeFromString<String>(resultJson)
        } catch (_: Exception) {
            // If it's not a valid JSON string, return as-is (strip quotes if present)
            resultJson.removeSurrounding("\"")
        }
    }

    override suspend fun fetchPageText(page: Page): String {
        return getChapterText(page.url)
    }

    override fun getMangaUrl(manga: SManga): String {
        return resolveUrl(manga.url)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return resolveUrl(chapter.url)
    }

    override fun getChapterUrl(manga: SManga?, chapter: SChapter): String? {
        val chapterUrl = chapter.url.trim()
        return if (chapterUrl.isBlank()) {
            manga?.let { getMangaUrl(it) }
        } else {
            resolveUrl(chapterUrl)
        }
    }

    /**
     * Resolve a relative URL to absolute using the plugin's resolveUrl or site URL.
     */
    fun resolveUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//")) {
            return path
        }
        val site = siteUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$site/$cleanPath"
    }

    suspend fun destroy() = runtimeMutex.withLock {
        runtime?.close()
        runtime = null
    }

    private fun buildFiltersJsArg(filters: FilterList? = null): String {
        if (filterDefinitions.isEmpty()) {
            return "undefined"
        }

        val filtersByKey = mutableMapOf<String, Filter<*>>()
        filters?.forEach { filter ->
            filterKey(filter)?.let { filtersByKey[it] = filter }
        }

        val filtersJson = buildJsonObject {
            filterDefinitions.forEach { definition ->
                put(definition.key, definition.toJson(filtersByKey[definition.key]))
            }
        }
        return filtersJson.toString()
    }

    private fun filterKey(filter: Filter<*>): String? {
        return when (filter) {
            is LnTextFilter -> filter.key
            is LnSwitchFilter -> filter.key
            is LnPickerFilter -> filter.key
            is LnCheckboxGroupFilter -> filter.key
            is LnXCheckboxGroupFilter -> filter.key
            else -> null
        }
    }

    private fun parseFilterDefinitions(filters: JsonObject?): List<LnFilterDefinition> {
        if (filters == null) {
            return emptyList()
        }

        return filters.entries.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: key

            when (type) {
                FILTER_TYPE_TEXT -> LnTextFilterDefinition(
                    key = key,
                    label = label,
                    defaultValue = obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                FILTER_TYPE_SWITCH -> LnSwitchFilterDefinition(
                    key = key,
                    label = label,
                    defaultValue = obj["value"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                )
                FILTER_TYPE_PICKER -> LnPickerFilterDefinition(
                    key = key,
                    label = label,
                    options = parseFilterOptions(obj),
                    defaultValue = obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                FILTER_TYPE_CHECKBOX -> LnCheckboxFilterDefinition(
                    key = key,
                    label = label,
                    options = parseFilterOptions(obj),
                    defaultValues = parseStringArray(obj["value"]).toSet(),
                )
                FILTER_TYPE_XCHECKBOX -> {
                    val valueObj = obj["value"] as? JsonObject
                    LnXCheckboxFilterDefinition(
                        key = key,
                        label = label,
                        options = parseFilterOptions(obj),
                        defaultInclude = parseStringArray(valueObj?.get("include")).toSet(),
                        defaultExclude = parseStringArray(valueObj?.get("exclude")).toSet(),
                        includePresent = valueObj?.containsKey("include") == true,
                        excludePresent = valueObj?.containsKey("exclude") == true,
                    )
                }
                else -> null
            }
        }
    }

    private fun parseFilterOptions(obj: JsonObject): List<LnFilterOption> {
        val options = obj["options"] as? JsonArray ?: return emptyList()
        return options.mapNotNull { element ->
            val option = element as? JsonObject ?: return@mapNotNull null
            val label = option["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val value = option["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            LnFilterOption(label = label, value = value)
        }
    }

    private fun parseStringArray(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    // --- Parsing helpers ---

    private fun parseNovelItems(jsonStr: String): MangasPage {
        return try {
            val items = json.decodeFromString<JsonArray>(jsonStr)
            val mangas = items.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    SManga.create().apply {
                        title = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        url = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        thumbnail_url = obj["cover"]?.jsonPrimitive?.contentOrNull?.let { resolveUrl(it) }
                    }
                } catch (e: Exception) {
                    Logger.w(e) { "NovelSource: Failed to parse novel item" }
                    null
                }
            }
            // LNReader plugins don't expose a hasNextPage flag — the convention is to keep
            // requesting incrementing pages until popularNovels/searchNovels returns []. Signal
            // that to the pager by reporting hasNextPage = mangas.isNotEmpty().
            MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
        } catch (e: Exception) {
            Logger.e(e) { "NovelSource: Failed to parse novel items" }
            MangasPage(emptyList(), false)
        }
    }

    private fun parseSourceNovel(jsonStr: String, existing: SManga): SManga {
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonStr)
            existing.apply {
                obj["name"]?.jsonPrimitive?.contentOrNull?.let { title = it }
                obj["author"]?.jsonPrimitive?.contentOrNull?.let { author = it }
                obj["artist"]?.jsonPrimitive?.contentOrNull?.let { artist = it }
                obj["summary"]?.jsonPrimitive?.contentOrNull?.let { description = stripHtmlIfPresent(it) }
                obj["cover"]?.jsonPrimitive?.contentOrNull?.let { thumbnail_url = resolveUrl(it) }

                genre = obj["genres"]?.jsonPrimitive?.contentOrNull?.let(::normalizeGenres)

                status = when (obj["status"]?.jsonPrimitive?.contentOrNull) {
                    "Ongoing" -> SManga.ONGOING
                    "Completed" -> SManga.COMPLETED
                    "Licensed" -> SManga.LICENSED
                    "Publishing Finished" -> SManga.PUBLISHING_FINISHED
                    "Cancelled" -> SManga.CANCELLED
                    "On Hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }

                initialized = true
            }
        } catch (e: Exception) {
            Logger.e(e) { "NovelSource: Failed to parse novel details" }
            existing
        }
    }

    /**
     * Per the LNReader contract `genres` is a single comma-separated string, but plugins
     * sometimes concatenate multiple tag sources without dedup (e.g. "Xianxia,Action,Fantasy,
     * Action,Adventure,Fantasy,VIDEO GAMES"). Split, trim each, dedupe case-insensitively
     * (keeping the first occurrence's original casing), and rejoin with a consistent separator.
     * Returns null if the cleaned list is empty so callers can skip the field.
     */
    private fun normalizeGenres(raw: String): String? {
        if (raw.isBlank()) return null
        val seen = LinkedHashMap<String, String>()
        raw.splitToSequence(',', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { tag ->
                val key = tag.lowercase(Locale.ROOT)
                if (key !in seen) seen[key] = tag
            }
        return seen.values.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * LNReader plugins may emit `summary` as HTML (per contract). Most plugins call `.text()` in
     * cheerio so plain text is the common case, but a few return raw markup — strip tags then so
     * users don't see `<p>...</p>` literally in the details panel.
     */
    private fun stripHtmlIfPresent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.contains('<')) return trimmed
        return try {
            // Replace block-ending tags with newlines BEFORE entity decoding — Jsoup.text() would
            // otherwise collapse the inserted whitespace, losing paragraph structure entirely.
            val withBreaks = trimmed
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</(p|div|li|h[1-6])\\s*>", RegexOption.IGNORE_CASE), "\n\n")
            val withoutTags = withBreaks.replace(Regex("<[^>]+>"), "")
            org.jsoup.parser.Parser.unescapeEntities(withoutTags, false)
                .replace(" ", " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun parseChapterArray(chapters: JsonArray): List<SChapter> {
        return chapters.mapNotNull { element ->
            try {
                val ch = element.jsonObject
                SChapter.create().apply {
                    name = ch["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    url = ch["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    chapter_number = ch["chapterNumber"]?.jsonPrimitive?.floatOrNull ?: -1f
                    date_upload = parseReleaseTime(ch["releaseTime"]?.jsonPrimitive?.contentOrNull)
                }
            } catch (e: Exception) {
                Logger.w(e) { "NovelSource: Failed to parse chapter" }
                null
            }
        }
    }

    private fun parseReleaseTime(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            Instant.parse(dateStr).toEpochMilli()
        } catch (_: Exception) {
            try {
                // Try ISO format without timezone
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateStr)?.time ?: 0L
            } catch (_: Exception) {
                try {
                    // Try simple date format
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    try {
                        // Try as epoch millis/seconds
                        val epoch = dateStr.toLong()
                        if (dateStr.length <= 10) epoch * 1000 else epoch
                    } catch (_: Exception) {
                        0L
                    }
                }
            }
        }
    }

    private fun escapeJsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    private sealed class LnFilterDefinition(
        open val key: String,
        open val label: String,
    ) {
        abstract fun toFilter(): Filter<*>
        abstract fun toJson(filter: Filter<*>?): JsonObject
    }

    private data class LnFilterOption(
        val label: String,
        val value: String,
    )

    private data class LnTextFilterDefinition(
        override val key: String,
        override val label: String,
        val defaultValue: String,
    ) : LnFilterDefinition(key, label) {
        override fun toFilter(): Filter<*> = LnTextFilter(key, label, defaultValue)

        override fun toJson(filter: Filter<*>?): JsonObject {
            val currentValue = (filter as? LnTextFilter)?.state ?: defaultValue
            return buildJsonObject {
                put("type", FILTER_TYPE_TEXT)
                put("label", label)
                put("value", currentValue)
            }
        }
    }

    private data class LnSwitchFilterDefinition(
        override val key: String,
        override val label: String,
        val defaultValue: Boolean,
    ) : LnFilterDefinition(key, label) {
        override fun toFilter(): Filter<*> = LnSwitchFilter(key, label, defaultValue)

        override fun toJson(filter: Filter<*>?): JsonObject {
            val currentValue = (filter as? LnSwitchFilter)?.state ?: defaultValue
            return buildJsonObject {
                put("type", FILTER_TYPE_SWITCH)
                put("label", label)
                put("value", currentValue)
            }
        }
    }

    private data class LnPickerFilterDefinition(
        override val key: String,
        override val label: String,
        val options: List<LnFilterOption>,
        val defaultValue: String,
    ) : LnFilterDefinition(key, label) {
        override fun toFilter(): Filter<*> {
            val normalizedOptions = if (options.any { it.value == defaultValue }) {
                options
            } else {
                listOf(
                    LnFilterOption(
                        label = if (defaultValue.isBlank()) DEFAULT_PICKER_LABEL else defaultValue,
                        value = defaultValue,
                    ),
                ) + options
            }
            val selectedIndex = normalizedOptions.indexOfFirst { it.value == defaultValue }.coerceAtLeast(0)
            return LnPickerFilter(key, label, normalizedOptions, selectedIndex)
        }

        override fun toJson(filter: Filter<*>?): JsonObject {
            val currentValue = (filter as? LnPickerFilter)?.selectedValue() ?: defaultValue
            return buildJsonObject {
                put("type", FILTER_TYPE_PICKER)
                put("label", label)
                put("value", currentValue)
                putJsonArray("options") {
                    options.forEach { add(optionToJson(it)) }
                }
            }
        }
    }

    private data class LnCheckboxFilterDefinition(
        override val key: String,
        override val label: String,
        val options: List<LnFilterOption>,
        val defaultValues: Set<String>,
    ) : LnFilterDefinition(key, label) {
        override fun toFilter(): Filter<*> {
            return LnCheckboxGroupFilter(
                key = key,
                name = label,
                state = options.map { option ->
                    LnCheckboxOption(
                        optionValue = option.value,
                        name = option.label,
                        state = option.value in defaultValues,
                    )
                },
            )
        }

        override fun toJson(filter: Filter<*>?): JsonObject {
            val currentValues = (filter as? LnCheckboxGroupFilter)?.state
                ?.filter { it.state }
                ?.map { it.optionValue }
                ?: orderedValues(options, defaultValues)

            return buildJsonObject {
                put("type", FILTER_TYPE_CHECKBOX)
                put("label", label)
                putJsonArray("value") {
                    currentValues.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("options") {
                    options.forEach { add(optionToJson(it)) }
                }
            }
        }
    }

    private data class LnXCheckboxFilterDefinition(
        override val key: String,
        override val label: String,
        val options: List<LnFilterOption>,
        val defaultInclude: Set<String>,
        val defaultExclude: Set<String>,
        val includePresent: Boolean,
        val excludePresent: Boolean,
    ) : LnFilterDefinition(key, label) {
        override fun toFilter(): Filter<*> {
            return LnXCheckboxGroupFilter(
                key = key,
                name = label,
                state = options.map { option ->
                    val state = when {
                        option.value in defaultInclude -> Filter.TriState.STATE_INCLUDE
                        option.value in defaultExclude -> Filter.TriState.STATE_EXCLUDE
                        else -> Filter.TriState.STATE_IGNORE
                    }
                    LnXCheckboxOption(
                        optionValue = option.value,
                        name = option.label,
                        state = state,
                    )
                },
            )
        }

        override fun toJson(filter: Filter<*>?): JsonObject {
            val group = filter as? LnXCheckboxGroupFilter
            val included = group?.state
                ?.filter { it.state == Filter.TriState.STATE_INCLUDE }
                ?.map { it.optionValue }
                ?: orderedValues(options, defaultInclude)
            val excluded = group?.state
                ?.filter { it.state == Filter.TriState.STATE_EXCLUDE }
                ?.map { it.optionValue }
                ?: orderedValues(options, defaultExclude)

            return buildJsonObject {
                put("type", FILTER_TYPE_XCHECKBOX)
                put("label", label)
                putJsonObject("value") {
                    if (includePresent || included.isNotEmpty()) {
                        putJsonArray("include") {
                            included.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    if (excludePresent || excluded.isNotEmpty()) {
                        putJsonArray("exclude") {
                            excluded.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
                putJsonArray("options") {
                    options.forEach { add(optionToJson(it)) }
                }
            }
        }
    }

    private class LnTextFilter(
        val key: String,
        name: String,
        state: String,
    ) : Filter.Text(name, state)

    private class LnSwitchFilter(
        val key: String,
        name: String,
        state: Boolean,
    ) : Filter.CheckBox(name, state)

    private class LnPickerFilter(
        val key: String,
        name: String,
        private val options: List<LnFilterOption>,
        state: Int,
    ) : Filter.Select<String>(name, options.map { it.label }.toTypedArray(), state) {
        fun selectedValue(): String {
            return options.getOrNull(state)?.value ?: options.firstOrNull()?.value.orEmpty()
        }
    }

    private class LnCheckboxOption(
        val optionValue: String,
        name: String,
        state: Boolean,
    ) : Filter.CheckBox(name, state)

    private class LnCheckboxGroupFilter(
        val key: String,
        name: String,
        state: List<LnCheckboxOption>,
    ) : Filter.Group<LnCheckboxOption>(name, state)

    private class LnXCheckboxOption(
        val optionValue: String,
        name: String,
        state: Int,
    ) : Filter.TriState(name, state)

    private class LnXCheckboxGroupFilter(
        val key: String,
        name: String,
        state: List<LnXCheckboxOption>,
    ) : Filter.Group<LnXCheckboxOption>(name, state)

    companion object {
        private const val FILTER_TYPE_TEXT = "Text"
        private const val FILTER_TYPE_SWITCH = "Switch"
        private const val FILTER_TYPE_PICKER = "Picker"
        private const val FILTER_TYPE_CHECKBOX = "Checkbox"
        private const val FILTER_TYPE_XCHECKBOX = "XCheckbox"
        private const val DEFAULT_PICKER_LABEL = "Any"

        private fun orderedValues(options: List<LnFilterOption>, selected: Set<String>): List<String> {
            if (selected.isEmpty()) {
                return emptyList()
            }

            val ordered = options.mapNotNull { option ->
                option.value.takeIf { it in selected }
            }.toMutableList()

            selected.forEach { value ->
                if (value !in ordered) {
                    ordered.add(value)
                }
            }
            return ordered
        }

        private fun optionToJson(option: LnFilterOption): JsonObject {
            return buildJsonObject {
                put("label", option.label)
                put("value", option.value)
            }
        }
    }
}
