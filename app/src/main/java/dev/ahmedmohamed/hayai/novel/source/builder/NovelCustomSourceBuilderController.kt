package dev.ahmedmohamed.hayai.novel.source.builder

import android.text.InputType
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.InputStream

class NovelCustomSourceBuilderController() : SettingsController() {
    private val network: NetworkHelper = Injekt.get()
    private val json = Json { prettyPrint = true }
    private val values = FIELD_KEYS.associateWithTo(linkedMapOf()) { "" }
    private val fieldPreferences = linkedMapOf<String, Preference>()
    private lateinit var store: NovelCustomSourceStore
    private lateinit var advancedPreference: Preference
    private lateinit var statusPreference: Preference
    private var advancedDefinition = ""

    constructor(definition: String) : this() {
        args.putString(DEFINITION, definition)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_custom_novel_source
        store = NovelCustomSourceStore(context, Injekt.get<NovelPluginManager>())

        preferenceCategory {
            title = context.getString(R.string.hayai_custom_source_instructions)
            FIELD_KEYS.forEach { key ->
                fieldPreferences[key] = preference {
                    title = fieldHint(key)
                    onClick { editField(key) }
                }
            }
        }
        preferenceCategory {
            title = context.getString(R.string.hayai_custom_source_advanced_hint)
            advancedPreference = preference {
                title = context.getString(R.string.hayai_custom_source_advanced_hint)
                onClick { editAdvancedDefinition() }
            }
            preference {
                title = context.getString(R.string.hayai_custom_source_load_json)
                onClick { loadJson() }
            }
            preference {
                title = context.getString(R.string.hayai_custom_source_update_json)
                onClick { updateJson() }
            }
        }
        preferenceCategory {
            statusPreference = preference { isSelectable = false }
            preference {
                title = context.getString(R.string.hayai_validate)
                onClick { validateDefinition() }
            }
            preference {
                title = context.getString(R.string.hayai_preview_popular_page)
                onClick { preview() }
            }
            preference {
                title = context.getString(R.string.hayai_install_source)
                onClick { install() }
            }
        }

        args.getString(DEFINITION)?.takeIf(String::isNotBlank)?.let { serialized ->
            advancedDefinition = serialized
            runCatching { store.parse(serialized) }.onSuccess(::populate)
        }
        refreshSummaries()
    }

    private fun editField(key: String) {
        val context = activity ?: return
        val input = EditText(context).apply {
            setText(values.getValue(key))
            setSelection(text.length)
        }
        context.materialAlertDialog()
            .setTitle(fieldHint(key))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                values[key] = input.text.toString().trim()
                refreshSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editAdvancedDefinition() {
        val context = activity ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            setText(advancedDefinition)
            setSelection(text.length)
        }
        context.materialAlertDialog()
            .setTitle(R.string.hayai_custom_source_advanced_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                advancedDefinition = input.text.toString()
                refreshSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadJson() {
        runCatching { store.parse(advancedDefinition) }
            .onSuccess {
                populate(it)
                statusPreference.summary = resources?.getString(R.string.hayai_valid_source_definition, it.name)
            }
            .onFailure { statusPreference.summary = failure(it, R.string.hayai_custom_source_operation_failed) }
    }

    private fun updateJson() {
        runCatching { definitionFromForm() }
            .onSuccess {
                advancedDefinition = json.encodeToString(NovelCustomSourceDefinition.serializer(), it)
                refreshSummaries()
            }
            .onFailure { statusPreference.summary = failure(it, R.string.hayai_custom_source_operation_failed) }
    }

    private fun validateDefinition(): NovelCustomSourceDefinition? = runCatching { definitionFromForm() }.fold(
        onSuccess = { definition ->
            val issues = definition.validate()
            if (issues.isEmpty()) {
                statusPreference.summary = resources?.getString(R.string.hayai_valid_source_definition, definition.name)
                definition
            } else {
                statusPreference.summary = issues.joinToString("\n", transform = ::validationIssueText)
                null
            }
        },
        onFailure = {
            statusPreference.summary = failure(it, R.string.hayai_custom_source_operation_failed)
            null
        },
    )

    private fun definitionFromForm(): NovelCustomSourceDefinition {
        fun value(key: String) = values.getValue(key).trim()
        val advanced = advancedDefinition.takeIf(String::isNotBlank)?.let(store::parse)
        return NovelCustomSourceDefinition(
            id = value("id"),
            name = value("name"),
            language = value("language"),
            baseUrl = value("baseUrl"),
            popularPath = value("popularPath"),
            searchPath = value("searchPath"),
            list = NovelListSelectors(value("list.item"), value("list.title"), value("list.link"), advanced?.list?.cover, advanced?.list?.next),
            details = NovelDetailsSelectors(value("details.title"), advanced?.details?.author, advanced?.details?.description, advanced?.details?.cover, advanced?.details?.genres, advanced?.details?.status),
            chapters = NovelChapterSelectors(value("chapters.item"), value("chapters.title"), value("chapters.link"), advanced?.chapters?.date),
            content = NovelContentSelectors(value("content.body"), advanced?.content?.remove.orEmpty()),
            headers = advanced?.headers.orEmpty(),
        )
    }

    private fun populate(definition: NovelCustomSourceDefinition) {
        mapOf(
            "id" to definition.id,
            "name" to definition.name,
            "language" to definition.language,
            "baseUrl" to definition.baseUrl,
            "popularPath" to definition.popularPath,
            "searchPath" to definition.searchPath,
            "list.item" to definition.list.item,
            "list.title" to definition.list.title,
            "list.link" to definition.list.link,
            "details.title" to definition.details.title,
            "chapters.item" to definition.chapters.item,
            "chapters.title" to definition.chapters.title,
            "chapters.link" to definition.chapters.link,
            "content.body" to definition.content.body,
        ).forEach { (key, value) -> values[key] = value }
        refreshSummaries()
    }

    private fun preview() {
        val definition = validateDefinition() ?: return
        val context = activity ?: return
        viewScope.launch {
            statusPreference.summary = context.getString(R.string.hayai_loading_preview)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val headers = definition.headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray()
                    val request = Request.Builder()
                        .url(definition.baseUrl.trimEnd('/') + definition.popularPath.replace("{page}", "1"))
                        .headers(Headers.headersOf(*headers))
                        .build()
                    network.client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { context.getString(R.string.hayai_http_status_error, response.code) }
                        val bytes = response.body.byteStream().readBounded(MAX_PREVIEW_BYTES)
                        NovelCustomSourcePreviewer.list(bytes.toString(Charsets.UTF_8), response.request.url.toString(), definition)
                    }
                }
            }
            result.fold(
                onSuccess = { result ->
                    statusPreference.summary = result.issues.joinToString("\n", transform = ::validationIssueText)
                        .ifBlank { context.getString(R.string.hayai_custom_source_found_preview, result.title, result.url) }
                },
                onFailure = { statusPreference.summary = failure(it, R.string.hayai_custom_source_preview_failed) },
            )
        }
    }

    private fun install() {
        val definition = validateDefinition() ?: return
        val context = activity ?: return
        context.materialAlertDialog()
            .setTitle(context.getString(R.string.hayai_install_named_item, definition.name))
            .setMessage(R.string.hayai_custom_source_install_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.install) { _, _ ->
                viewScope.launch {
                    statusPreference.summary = context.getString(R.string.hayai_installing)
                    runCatching { store.save(definition) }.fold(
                        onSuccess = { statusPreference.summary = context.getString(R.string.installed_, definition.name) },
                        onFailure = { statusPreference.summary = failure(it, R.string.hayai_custom_source_install_failed) },
                    )
                }
            }
            .show()
    }

    private fun refreshSummaries() {
        fieldPreferences.forEach { (key, preference) ->
            preference.summary = values.getValue(key).ifBlank { resources?.getString(R.string.none) }
        }
        if (::advancedPreference.isInitialized) {
            advancedPreference.summary = resources?.getString(if (advancedDefinition.isBlank()) R.string.none else R.string.hayai_value_configured)
        }
    }

    private fun InputStream.readBounded(max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { resources?.getString(R.string.hayai_preview_response_too_large).orEmpty() }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun fieldHint(key: String): String = requireNotNull(resources).getString(
        when (key) {
            "id" -> R.string.hayai_custom_source_field_id
            "name" -> R.string.hayai_custom_source_field_name
            "language" -> R.string.hayai_custom_source_field_language
            "baseUrl" -> R.string.hayai_custom_source_field_base_url
            "popularPath" -> R.string.hayai_custom_source_field_popular_path
            "searchPath" -> R.string.hayai_custom_source_field_search_path
            "list.item" -> R.string.hayai_custom_source_field_list_item
            "list.title" -> R.string.hayai_custom_source_field_list_title
            "list.link" -> R.string.hayai_custom_source_field_list_link
            "details.title" -> R.string.hayai_custom_source_field_details_title
            "chapters.item" -> R.string.hayai_custom_source_field_chapter_item
            "chapters.title" -> R.string.hayai_custom_source_field_chapter_title
            "chapters.link" -> R.string.hayai_custom_source_field_chapter_link
            "content.body" -> R.string.hayai_custom_source_field_content_body
            else -> error("Unknown custom source field: $key")
        },
    )

    private fun validationIssueText(issue: NovelSourceValidationIssue): String = requireNotNull(resources).getString(
        R.string.hayai_field_issue,
        when (issue.field) {
            "content.remove" -> requireNotNull(resources).getString(R.string.hayai_custom_source_field_content_remove)
            "headers" -> requireNotNull(resources).getString(R.string.hayai_custom_source_field_headers)
            else -> fieldHint(issue.field)
        },
        requireNotNull(resources).getString(
            when (issue.code) {
                NovelSourceValidationCode.InvalidId -> R.string.hayai_custom_source_invalid_id
                NovelSourceValidationCode.InvalidName -> R.string.hayai_custom_source_invalid_name
                NovelSourceValidationCode.InvalidLanguage -> R.string.hayai_custom_source_invalid_language
                NovelSourceValidationCode.InvalidBaseUrl -> R.string.hayai_custom_source_invalid_base_url
                NovelSourceValidationCode.InvalidPath -> R.string.hayai_custom_source_invalid_path
                NovelSourceValidationCode.MissingQueryPlaceholder -> R.string.hayai_custom_source_query_required
                NovelSourceValidationCode.InvalidSelector -> R.string.hayai_custom_source_invalid_selector
                NovelSourceValidationCode.InvalidRemovalSelectors -> R.string.hayai_custom_source_invalid_remove_selectors
                NovelSourceValidationCode.InvalidHeaders -> R.string.hayai_custom_source_invalid_headers
                NovelSourceValidationCode.PreviewItemMissing -> R.string.hayai_custom_source_no_preview_item
                NovelSourceValidationCode.PreviewTitleMissing -> R.string.hayai_custom_source_preview_title_missing
                NovelSourceValidationCode.PreviewLinkMissing -> R.string.hayai_custom_source_preview_link_missing
            },
        ),
    )

    private fun failure(error: Throwable, fallback: Int): String =
        requireNotNull(activity).novelFailureMessage(error, fallback)

    private companion object {
        const val DEFINITION = "hayai.custom_source_definition"
        const val MAX_PREVIEW_BYTES = 4_000_000
        val FIELD_KEYS = listOf(
            "id",
            "name",
            "language",
            "baseUrl",
            "popularPath",
            "searchPath",
            "list.item",
            "list.title",
            "list.link",
            "details.title",
            "chapters.item",
            "chapters.title",
            "chapters.link",
            "content.body",
        )
    }
}
