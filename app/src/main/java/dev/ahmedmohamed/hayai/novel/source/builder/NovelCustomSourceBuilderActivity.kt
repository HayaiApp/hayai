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
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
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
        title = "Custom novel source"
        setContentView(android.widget.ScrollView(this).apply {
            addView(LinearLayout(this@NovelCustomSourceBuilderActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(TextView(context).apply {
                    text = "Define the source with URL templates and CSS selectors. Validate and preview against the real site before installing."
                })
                FIELD_KEYS.forEach { key ->
                    val field = EditText(context).apply { hint = key }
                    fields[key] = field
                    addView(field)
                }
                editor = EditText(context).apply {
                    hint = "Advanced definition JSON for optional cover, dates, removal selectors, and headers"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 4
                    setText(intent.getStringExtra(EXTRA_DEFINITION).orEmpty())
                }
                addView(editor)
                addView(Button(context).apply {
                    text = "Load advanced JSON into form"
                    setOnClickListener {
                        runCatching { store.parse(editor.text.toString()) }
                            .onSuccess(::populate)
                            .onFailure { status.text = it.message }
                    }
                })
                addView(Button(context).apply {
                    text = "Update advanced JSON from form"
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
                            .onFailure { status.text = it.message }
                    }
                })
                status = TextView(context)
                addView(status)
                addView(Button(context).apply { text = "Validate"; setOnClickListener { validateDefinition() } })
                addView(Button(context).apply { text = "Preview popular page"; setOnClickListener { preview() } })
                addView(Button(context).apply { text = "Install source"; setOnClickListener { install() } })
            })
        })
        intent.getStringExtra(EXTRA_DEFINITION)?.takeIf(String::isNotBlank)?.let { serialized ->
            runCatching { store.parse(serialized) }.onSuccess(::populate)
        }
    }

    private fun validateDefinition(): NovelCustomSourceDefinition? = runCatching {
        definitionFromForm().requireValid()
    }.fold(
        onSuccess = {
            status.text = "Valid source definition for ${it.name}"
            it
        },
        onFailure = {
            status.text = it.message
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
            status.text = "Loading preview…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val headerValues = definition.headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray()
                    val request = Request.Builder()
                        .url(definition.baseUrl.trimEnd('/') + definition.popularPath.replace("{page}", "1"))
                        .headers(Headers.headersOf(*headerValues))
                        .build()
                    network.client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
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
                    status.text = preview.issues.joinToString("\n") { "${it.field}: ${it.message}" }
                        .ifBlank { "Found ${preview.title}\n${preview.url}" }
                },
                onFailure = { status.text = it.message },
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
            require(total <= max) { "Preview response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun install() {
        val definition = validateDefinition() ?: return
        AlertDialog.Builder(this)
            .setTitle("Install ${definition.name}?")
            .setMessage("This source can access the network and execute generated parsing code. Review the definition first.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Install") { _, _ ->
                lifecycleScope.launch {
                    status.text = "Installing…"
                    runCatching { store.save(definition) }.fold(
                        onSuccess = { status.text = "Installed ${definition.name}" },
                        onFailure = { status.text = it.message },
                    )
                }
            }
            .show()
    }

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
