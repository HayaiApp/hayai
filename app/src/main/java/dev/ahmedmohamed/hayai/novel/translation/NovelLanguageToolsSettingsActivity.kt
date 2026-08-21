package dev.ahmedmohamed.hayai.novel.translation

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionaryProvider
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettings
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettingsStore

class NovelLanguageToolsSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Novel language tools"
        val store = NovelTranslationSettingsStore(this)
        val current = store.get()
        val dictionaryStore = NovelDictionarySettingsStore(this)
        val dictionaryCurrent = dictionaryStore.get()
        val engine = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NovelLanguageToolsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NovelTranslationEngineId.entries.map { it.name.replace('_', ' ') },
            )
            setSelection(current.engine.ordinal)
        }
        val source = field("Source language", current.sourceLanguage)
        val target = field("Target language", current.targetLanguage)
        val endpoint = field("Provider endpoint", current.endpoint, InputType.TYPE_TEXT_VARIATION_URI)
        val key = field("API key", current.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = field("Model", current.model)
        val message = TextView(this)
        val dictionary = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NovelLanguageToolsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NovelDictionaryProvider.entries.map { it.name.replace('_', ' ') },
            )
            setSelection(dictionaryCurrent.provider.ordinal)
        }
        val fallbackOptions = NovelDictionaryProvider.entries.filter { it.name.startsWith("WEB_") }
        val fallback = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NovelLanguageToolsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                fallbackOptions.map { it.name.replace('_', ' ') },
            )
            setSelection(fallbackOptions.indexOf(dictionaryCurrent.webFallback).coerceAtLeast(0))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(engine)
            listOf(source, target, endpoint, key, model).forEach(::addView)
            addView(TextView(context).apply { text = "Dictionary provider" })
            addView(dictionary)
            addView(TextView(context).apply { text = "Web fallback" })
            addView(fallback)
            addView(message)
            addView(Button(context).apply {
                text = "Save"
                setOnClickListener {
                    runCatching {
                        store.set(
                            NovelTranslationSettings(
                                engine = NovelTranslationEngineId.entries[engine.selectedItemPosition],
                                sourceLanguage = source.text.toString().trim(),
                                targetLanguage = target.text.toString().trim(),
                                endpoint = endpoint.text.toString().trim(),
                                apiKey = key.text.toString(),
                                model = model.text.toString().trim(),
                            ),
                        )
                        dictionaryStore.set(
                            NovelDictionarySettings(
                                provider = NovelDictionaryProvider.entries[dictionary.selectedItemPosition],
                                webFallback = fallbackOptions[fallback.selectedItemPosition],
                            ),
                        )
                    }.fold(
                        onSuccess = { message.text = "Settings saved" },
                        onFailure = { message.text = it.message },
                    )
                }
            })
        })
    }

    private fun field(hint: String, value: String, variation: Int = 0) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or variation
    }
}
