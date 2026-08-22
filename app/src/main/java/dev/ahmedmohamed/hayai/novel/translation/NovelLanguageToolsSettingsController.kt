package dev.ahmedmohamed.hayai.novel.translation

import android.text.InputType
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionaryProvider
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettings
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettingsStore
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.iconRes
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.toast

class NovelLanguageToolsSettingsController : SettingsController() {
    private lateinit var translationStore: NovelTranslationSettingsStore
    private lateinit var dictionaryStore: NovelDictionarySettingsStore
    private lateinit var draftTranslation: NovelTranslationSettings
    private lateinit var draftDictionary: NovelDictionarySettings
    private lateinit var enginePreference: Preference
    private lateinit var sourceLanguagePreference: Preference
    private lateinit var targetLanguagePreference: Preference
    private lateinit var endpointPreference: Preference
    private lateinit var apiKeyPreference: Preference
    private lateinit var modelPreference: Preference
    private lateinit var dictionaryPreference: Preference
    private lateinit var fallbackPreference: Preference

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_language_tools
        translationStore = NovelTranslationSettingsStore(context)
        dictionaryStore = NovelDictionarySettingsStore(context)
        draftTranslation = translationStore.get()
        draftDictionary = dictionaryStore.get()

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_translation_dictionary)
            enginePreference = preference {
                title = context.getString(R.string.hayai_translation_engine)
                onClick { chooseEngine() }
            }
            sourceLanguagePreference = preference {
                title = context.getString(R.string.hayai_source_language)
                onClick {
                    editText(R.string.hayai_source_language, draftTranslation.sourceLanguage) {
                        draftTranslation = draftTranslation.copy(sourceLanguage = it.trim())
                        refreshSummaries()
                    }
                }
            }
            targetLanguagePreference = preference {
                title = context.getString(R.string.hayai_target_language)
                onClick {
                    editText(R.string.hayai_target_language, draftTranslation.targetLanguage) {
                        draftTranslation = draftTranslation.copy(targetLanguage = it.trim())
                        refreshSummaries()
                    }
                }
            }
            endpointPreference = preference {
                title = context.getString(R.string.hayai_provider_endpoint)
                onClick {
                    editText(
                        R.string.hayai_provider_endpoint,
                        draftTranslation.endpoint,
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                    ) {
                        draftTranslation = draftTranslation.copy(endpoint = it.trim())
                        refreshSummaries()
                    }
                }
            }
            apiKeyPreference = preference {
                title = context.getString(R.string.hayai_api_key)
                onClick {
                    editText(
                        R.string.hayai_api_key,
                        draftTranslation.apiKey,
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    ) {
                        draftTranslation = draftTranslation.copy(apiKey = it)
                        refreshSummaries()
                    }
                }
            }
            modelPreference = preference {
                title = context.getString(R.string.hayai_model)
                onClick {
                    editText(R.string.hayai_model, draftTranslation.model) {
                        draftTranslation = draftTranslation.copy(model = it.trim())
                        refreshSummaries()
                    }
                }
            }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_dictionary_provider)
            dictionaryPreference = preference {
                title = context.getString(R.string.hayai_dictionary_provider)
                onClick { chooseDictionaryProvider() }
            }
            fallbackPreference = preference {
                title = context.getString(R.string.hayai_web_fallback)
                onClick { chooseFallback() }
            }
        }

        preference {
            title = context.getString(R.string.save)
            iconRes = R.drawable.ic_check_24dp
            onClick { save() }
        }
        refreshSummaries()
    }

    private fun chooseEngine() {
        val context = activity ?: return
        val values = NovelTranslationEngineId.entries
        context.materialAlertDialog()
            .setTitle(R.string.hayai_translation_engine)
            .setSingleChoiceItems(values.map(::translationEngineText).toTypedArray(), values.indexOf(draftTranslation.engine)) { dialog, index ->
                draftTranslation = draftTranslation.copy(engine = values[index])
                refreshSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseDictionaryProvider() {
        val context = activity ?: return
        val values = NovelDictionaryProvider.entries
        context.materialAlertDialog()
            .setTitle(R.string.hayai_dictionary_provider)
            .setSingleChoiceItems(values.map(::dictionaryProviderText).toTypedArray(), values.indexOf(draftDictionary.provider)) { dialog, index ->
                draftDictionary = draftDictionary.copy(provider = values[index])
                refreshSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFallback() {
        val context = activity ?: return
        val values = NovelDictionaryProvider.entries.filter { it.name.startsWith("WEB_") }
        context.materialAlertDialog()
            .setTitle(R.string.hayai_web_fallback)
            .setSingleChoiceItems(values.map(::dictionaryProviderText).toTypedArray(), values.indexOf(draftDictionary.webFallback)) { dialog, index ->
                draftDictionary = draftDictionary.copy(webFallback = values[index])
                refreshSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editText(title: Int, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT, onApply: (String) -> Unit) {
        val context = activity ?: return
        val input = EditText(context).apply {
            setText(value)
            this.inputType = inputType
            setSelection(text.length)
        }
        context.materialAlertDialog()
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onApply(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        val context = activity ?: return
        runCatching {
            translationStore.set(draftTranslation)
            dictionaryStore.set(draftDictionary)
        }.fold(
            onSuccess = { context.toast(R.string.hayai_settings_saved) },
            onFailure = { context.toast(context.novelFailureMessage(it, R.string.hayai_language_settings_invalid)) },
        )
    }

    private fun refreshSummaries() {
        if (!::enginePreference.isInitialized) return
        val context = activity ?: return
        enginePreference.summary = translationEngineText(draftTranslation.engine)
        sourceLanguagePreference.summary = draftTranslation.sourceLanguage
        targetLanguagePreference.summary = draftTranslation.targetLanguage
        endpointPreference.summary = draftTranslation.endpoint.ifBlank { context.getString(R.string.none) }
        apiKeyPreference.summary = context.getString(if (draftTranslation.apiKey.isBlank()) R.string.none else R.string.hayai_value_configured)
        modelPreference.summary = draftTranslation.model
        dictionaryPreference.summary = dictionaryProviderText(draftDictionary.provider)
        fallbackPreference.summary = dictionaryProviderText(draftDictionary.webFallback)
    }

    private fun translationEngineText(engine: NovelTranslationEngineId): String =
        resources!!.getString(
            when (engine) {
                NovelTranslationEngineId.GOOGLE_WEB -> R.string.hayai_translation_google_web
                NovelTranslationEngineId.LIBRE_TRANSLATE -> R.string.hayai_translation_libretranslate
                NovelTranslationEngineId.OPENAI_COMPATIBLE -> R.string.hayai_translation_openai_compatible
                NovelTranslationEngineId.DEEPL -> R.string.hayai_translation_deepl
            },
        )

    private fun dictionaryProviderText(provider: NovelDictionaryProvider): String =
        resources!!.getString(
            when (provider) {
                NovelDictionaryProvider.SYSTEM -> R.string.hayai_dictionary_system
                NovelDictionaryProvider.AARD2 -> R.string.hayai_dictionary_aard2
                NovelDictionaryProvider.COLOR_DICT -> R.string.hayai_dictionary_colordict
                NovelDictionaryProvider.LIVIO -> R.string.hayai_dictionary_livio
                NovelDictionaryProvider.WEB_GOOGLE -> R.string.hayai_dictionary_google_web
                NovelDictionaryProvider.WEB_WIKTIONARY -> R.string.hayai_dictionary_wiktionary
            },
        )
}
