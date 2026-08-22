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
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import eu.kanade.tachiyomi.R

class NovelLanguageToolsSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hayai_novel_language_tools)
        val store = NovelTranslationSettingsStore(this)
        val current = store.get()
        val dictionaryStore = NovelDictionarySettingsStore(this)
        val dictionaryCurrent = dictionaryStore.get()
        val engine = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NovelLanguageToolsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NovelTranslationEngineId.entries.map(::translationEngineText),
            )
            setSelection(current.engine.ordinal)
        }
        val source = field(getString(R.string.hayai_source_language), current.sourceLanguage)
        val target = field(getString(R.string.hayai_target_language), current.targetLanguage)
        val endpoint = field(getString(R.string.hayai_provider_endpoint), current.endpoint, InputType.TYPE_TEXT_VARIATION_URI)
        val key = field(getString(R.string.hayai_api_key), current.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = field(getString(R.string.hayai_model), current.model)
        val message = TextView(this)
        val dictionary = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NovelLanguageToolsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NovelDictionaryProvider.entries.map(::dictionaryProviderText),
            )
            setSelection(dictionaryCurrent.provider.ordinal)
        }
        val fallbackOptions = NovelDictionaryProvider.entries.filter { it.name.startsWith("WEB_") }
        val fallback = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NovelLanguageToolsSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                fallbackOptions.map(::dictionaryProviderText),
            )
            setSelection(fallbackOptions.indexOf(dictionaryCurrent.webFallback).coerceAtLeast(0))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(engine)
            listOf(source, target, endpoint, key, model).forEach(::addView)
            addView(TextView(context).apply { text = getString(R.string.hayai_dictionary_provider) })
            addView(dictionary)
            addView(TextView(context).apply { text = getString(R.string.hayai_web_fallback) })
            addView(fallback)
            addView(message)
            addView(Button(context).apply {
                setText(R.string.save)
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
                        onSuccess = { message.text = getString(R.string.hayai_settings_saved) },
                        onFailure = { message.text = languageSettingsError(it) },
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

    private fun translationEngineText(engine: NovelTranslationEngineId): String =
        getString(
            when (engine) {
                NovelTranslationEngineId.GOOGLE_WEB -> R.string.hayai_translation_google_web
                NovelTranslationEngineId.LIBRE_TRANSLATE -> R.string.hayai_translation_libretranslate
                NovelTranslationEngineId.OPENAI_COMPATIBLE -> R.string.hayai_translation_openai_compatible
                NovelTranslationEngineId.DEEPL -> R.string.hayai_translation_deepl
            },
        )

    private fun dictionaryProviderText(provider: NovelDictionaryProvider): String =
        getString(
            when (provider) {
                NovelDictionaryProvider.SYSTEM -> R.string.hayai_dictionary_system
                NovelDictionaryProvider.AARD2 -> R.string.hayai_dictionary_aard2
                NovelDictionaryProvider.COLOR_DICT -> R.string.hayai_dictionary_colordict
                NovelDictionaryProvider.LIVIO -> R.string.hayai_dictionary_livio
                NovelDictionaryProvider.WEB_GOOGLE -> R.string.hayai_dictionary_google_web
                NovelDictionaryProvider.WEB_WIKTIONARY -> R.string.hayai_dictionary_wiktionary
            },
        )

    private fun languageSettingsError(error: Throwable): String =
        novelFailureMessage(error, R.string.hayai_language_settings_invalid)
}
