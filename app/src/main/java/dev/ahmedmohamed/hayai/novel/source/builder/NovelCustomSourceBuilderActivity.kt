package dev.ahmedmohamed.hayai.novel.source.builder

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Headers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelCustomSourceBuilderActivity : AppCompatActivity() {
    private val store by lazy { NovelCustomSourceStore(this, Injekt.get<NovelPluginManager>()) }
    private val network by lazy { Injekt.get<NetworkHelper>() }
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private val fields = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hayai_custom_novel_source)
        setContentView(android.widget.ScrollView(this).apply {
            addView(LinearLayout(this@NovelCustomSourceBuilderActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(TextView(context).apply {
                    text = getString(R.string.hayai_custom_source_instructions)
                })
                FIELD_KEYS.forEach { key ->
                    val field = EditText(context).apply { hint = fieldHint(key) }
                    fields[key] = field
                    addView(field)
                }
                editor = EditText(context).apply {
                    hint = getString(R.string.hayai_custom_source_advanced_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 4
                    setText(intent.getStringExtra(EXTRA_DEFINITION).orEmpty())
                }
                addView(editor)
                addView(Button(context).apply {
                    text = getString(R.string.hayai_custom_source_load_json)
                    setOnClickListener {
                        runCatching { store.parse(editor.text.toString()) }
                            .onSuccess(::populate)
                            .onFailure { status.text = novelFailureMessage(it, R.string.hayai_custom_source_operation_failed) }
                    }
                })
                addView(Button(context).apply {
                    text = getString(R.string.hayai_custom_source_update_json)
                    setOnClickListener {
                        runCatching { definitionFromForm() }
                            .onSuccess { definition ->
                                editor.setText(
                                    kotlinx.serialization.json.Json.encodeToString(
                                        NovelCustomSourceDefinition.serializer(),
                                        definition,
                                    ),
                                )
                            }
                            .onFailure { status.text = novelFailureMessage(it, R.string.hayai_custom_source_operation_failed) }
                    }
                })
                status = TextView(context)
                addView(status)
                addView(Button(context).apply { setText(R.string.hayai_validate); setOnClickListener { validateDefinition() } })
                addView(Button(context).apply { setText(R.string.hayai_preview_popular_page); setOnClickListener { preview() } })
                addView(Button(context).apply { setText(R.string.hayai_install_source); setOnClickListener { install() } })
            })
        })
        intent.getStringExtra(EXTRA_DEFINITION)?.takeIf(String::isNotBlank)?.let { serialized ->
            runCatching { store.parse(serialized) }.onSuccess(::populate)
        }
    }

    private fun validateDefinition(): NovelCustomSourceDefinition? = runCatching {
        definitionFromForm()
    }.fold(
        onSuccess = {
            val issues = it.validate()
            if (issues.isEmpty()) {
                status.text = getString(R.string.hayai_valid_source_definition, it.name)
                it
            } else {
                status.text = issues.joinToString("\n", transform = ::validationIssueText)
                null
            }
        },
        onFailure = {
            status.text = novelFailureMessage(it, R.string.hayai_custom_source_operation_failed)
            null
        },
    )

    private fun definitionFromForm(): NovelCustomSourceDefinition {
        fun value(key: String) = fields.getValue(key).text.toString().trim()
        val advanced = editor.text.toString().takeIf(String::isNotBlank)?.let { serialized ->
            store.parse(serialized)
        }
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

    private fun populate(value: NovelCustomSourceDefinition) {
        val values = mapOf(
            "id" to value.id,
            "name" to value.name,
            "language" to value.language,
            "baseUrl" to value.baseUrl,
            "popularPath" to value.popularPath,
            "searchPath" to value.searchPath,
            "list.item" to value.list.item,
            "list.title" to value.list.title,
            "list.link" to value.list.link,
            "details.title" to value.details.title,
            "chapters.item" to value.chapters.item,
            "chapters.title" to value.chapters.title,
            "chapters.link" to value.chapters.link,
            "content.body" to value.content.body,
        )
        values.forEach { (key, text) -> fields[key]?.setText(text) }
    }
    private fun preview() {
        val definition = validateDefinition() ?: return
        lifecycleScope.launch {
            status.text = getString(R.string.hayai_loading_preview)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val headerValues = definition.headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray()
                    val request = Request.Builder()
                        .url(definition.baseUrl.trimEnd('/') + definition.popularPath.replace("{page}", "1"))
                        .headers(Headers.headersOf(*headerValues))
                        .build()
                    network.client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { getString(R.string.hayai_http_status_error, response.code) }
                        val bytes = response.body.byteStream().readBounded(MAX_PREVIEW_BYTES)
                        NovelCustomSourcePreviewer.list(
                            bytes.toString(Charsets.UTF_8),
                            response.request.url.toString(),
                            definition,
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { preview ->
                    status.text =
                        preview.issues
                            .joinToString("\n", transform = ::validationIssueText)
                            .ifBlank { getString(R.string.hayai_custom_source_found_preview, preview.title, preview.url) }
                },
                onFailure = { status.text = novelFailureMessage(it, R.string.hayai_custom_source_preview_failed) },
            )
        }
    }

    private fun java.io.InputStream.readBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { getString(R.string.hayai_preview_response_too_large) }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun install() {
        val definition = validateDefinition() ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hayai_install_named_item, definition.name))
            .setMessage(R.string.hayai_custom_source_install_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.install) { _, _ ->
                lifecycleScope.launch {
                    status.text = getString(R.string.hayai_installing)
                    runCatching { store.save(definition) }.fold(
                        onSuccess = { status.text = getString(R.string.installed_, definition.name) },
                        onFailure = { status.text = novelFailureMessage(it, R.string.hayai_custom_source_install_failed) },
                    )
                }
            }
            .show()
    }

    private fun fieldHint(key: String): String =
        getString(
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

    private fun validationIssueText(issue: NovelSourceValidationIssue): String =
        getString(
            R.string.hayai_field_issue,
            when (issue.field) {
                "content.remove" -> getString(R.string.hayai_custom_source_field_content_remove)
                "headers" -> getString(R.string.hayai_custom_source_field_headers)
                else -> fieldHint(issue.field)
            },
            getString(
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

    companion object {
        const val EXTRA_DEFINITION = "hayai.custom_source_definition"
        private const val MAX_PREVIEW_BYTES = 4_000_000
        private val FIELD_KEYS = listOf(
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
