package eu.kanade.tachiyomi.ui.reader.settings

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.databinding.ReaderNovelTranslationBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.widget.BaseReaderSettingsView
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.service.TranslationPreferences
import yokai.domain.series.SeriesPreferences
import yokai.domain.series.model.TranslationMode
import yokai.i18n.MR
import yokai.util.koin.injectLazy
import yokai.util.lang.getString

class NovelTranslationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BaseReaderSettingsView<ReaderNovelTranslationBinding>(context, attrs) {

    private val translationPreferences: TranslationPreferences by injectLazy()
    private val translationEngineManager: TranslationEngineManager by injectLazy()
    private val seriesPreferences: SeriesPreferences by injectLazy()

    override fun inflateBinding() = ReaderNovelTranslationBinding.bind(this)

    override fun initGeneralPreferences() {
        with(binding) {
            translationEnabled.bindToPreference(translationPreferences.translationEnabled()) {
                reloadCurrentChapter()
                updateProviderStatus()
            }
            translationRealtime.bindToPreference(translationPreferences.realTimeTranslation()) {
                reloadCurrentChapter()
            }
            smartAutoTranslate.bindToPreference(translationPreferences.smartAutoTranslate()) {
                reloadCurrentChapter()
            }
            cacheTranslations.bindToPreference(translationPreferences.cacheTranslations())

            applyTranslationNow.setOnClickListener {
                val engine = translationEngineManager.getSelectedEngine()
                when {
                    !translationPreferences.translationEnabled().get() -> {
                        context.toast(context.getString(MR.strings.chapter_translation_unavailable))
                    }
                    !engine.isConfigured() -> {
                        context.toast(context.getString(MR.strings.pref_translation_provider_not_configured, engine.name))
                    }
                    else -> {
                        reloadCurrentChapter(forceTranslate = true)
                    }
                }
            }

            bindEngineSpinner()
            bindLanguageSpinners()
            bindTranslationModeSpinner()

            sourceLanguageFilter.setOnClickListener { showSourceLanguageFilterDialog() }
            configureProvider.setOnClickListener { showProviderSetupDialog() }

            updateSourceLanguageFilterSummary()
            updateProviderStatus()
        }
    }

    private fun bindEngineSpinner() {
        val engines = translationEngineManager.engines
        binding.translationEngine.setEntries(
            engines.map { engine ->
                if (engine.isOffline) {
                    context.getString(MR.strings.pref_translation_engine_offline_format, engine.name)
                } else {
                    engine.name
                }
            },
        )
        binding.translationEngine.setSelection(
            engines.indexOfFirst { it.id == translationPreferences.selectedEngineId().get() }.coerceAtLeast(0),
        )
        binding.translationEngine.onItemSelectedListener = { index ->
            engines.getOrNull(index)?.let { engine ->
                translationPreferences.selectedEngineId().set(engine.id)
                updateProviderStatus()
                reloadCurrentChapter()
            }
        }
    }

    private fun bindLanguageSpinners() {
        val sourceLanguages = LanguageCodes.common
        binding.sourceLanguage.setEntries(sourceLanguages.map { it.second })
        binding.sourceLanguage.setSelection(
            sourceLanguages.indexOfFirst { it.first == translationPreferences.sourceLanguage().get() }.coerceAtLeast(0),
        )
        binding.sourceLanguage.onItemSelectedListener = { index ->
            sourceLanguages.getOrNull(index)?.first?.let {
                translationPreferences.sourceLanguage().set(it)
                reloadCurrentChapter()
            }
        }

        val targetLanguages = LanguageCodes.common.filterNot { it.first == "auto" }
        binding.targetLanguage.setEntries(targetLanguages.map { it.second })
        binding.targetLanguage.setSelection(
            targetLanguages.indexOfFirst { it.first == translationPreferences.targetLanguage().get() }.coerceAtLeast(0),
        )
        binding.targetLanguage.onItemSelectedListener = { index ->
            targetLanguages.getOrNull(index)?.first?.let {
                translationPreferences.targetLanguage().set(it)
                reloadCurrentChapter()
            }
        }
    }

    private fun bindTranslationModeSpinner() {
        val modes = listOf(
            TranslationMode.SIMPLE to context.getString(MR.strings.pref_translation_mode_simple),
            TranslationMode.ADVANCED to context.getString(MR.strings.pref_translation_mode_advanced),
        )
        binding.translationMode.setEntries(modes.map { it.second })
        binding.translationMode.setSelection(
            modes.indexOfFirst { it.first == TranslationMode.fromDbKey(seriesPreferences.translationMode().get()) }.coerceAtLeast(0),
        )
        binding.translationMode.onItemSelectedListener = { index ->
            modes.getOrNull(index)?.first?.let { mode ->
                seriesPreferences.translationMode().set(mode.dbKey)
                reloadCurrentChapter()
            }
        }
    }

    private fun showSourceLanguageFilterDialog() {
        val languages = LanguageCodes.common.filterNot { it.first == "auto" }
        val selected = languages
            .map { language ->
                translationPreferences.enabledSourceLanguages().get()
                    .any { TranslationHtmlLanguage.matches(language.first, it) }
            }
            .toBooleanArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(MR.strings.pref_translation_language_filter))
            .setMultiChoiceItems(languages.map { it.second }.toTypedArray(), selected) { _, which, checked ->
                selected[which] = checked
            }
            .setNeutralButton(context.getString(MR.strings.pref_translation_all_source_languages)) { _, _ ->
                translationPreferences.enabledSourceLanguages().set(emptySet())
                updateSourceLanguageFilterSummary()
                reloadCurrentChapter()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                translationPreferences.enabledSourceLanguages().set(
                    languages.mapIndexedNotNull { index, language ->
                        language.first.takeIf { selected[index] }
                    }.toSet(),
                )
                updateSourceLanguageFilterSummary()
                reloadCurrentChapter()
            }
            .setNegativeButton(context.getString(MR.strings.action_cancel), null)
            .show()
    }

    private fun updateSourceLanguageFilterSummary() {
        val enabled = translationPreferences.enabledSourceLanguages().get()
        binding.sourceLanguageFilterSummary.text = if (enabled.isEmpty()) {
            context.getString(MR.strings.pref_translation_language_filter_summary_all)
        } else {
            enabled
                .map { LanguageCodes.displayName(it) }
                .sorted()
                .joinToString()
        }
    }

    private fun updateProviderStatus() {
        val engine = translationEngineManager.getSelectedEngine()
        binding.providerStatus.text = context.getString(
            if (engine.isConfigured()) {
                MR.strings.pref_translation_provider_configured
            } else {
                MR.strings.pref_translation_provider_not_configured
            },
            engine.name,
        )
    }

    private fun showProviderSetupDialog() {
        val engine = translationEngineManager.getSelectedEngine()
        val fields = providerFields(engine.id)
        if (fields.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(MR.strings.pref_translation_configure_provider_title, engine.name))
                .setMessage(context.getString(MR.strings.pref_translation_provider_no_setup, engine.name))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val inputs = mutableListOf<Pair<ProviderField, TextInputEditText>>()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx, 8.dpToPx, 24.dpToPx, 0)
        }
        fields.forEach { field ->
            val layout = TextInputLayout(context).apply {
                hint = field.label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 8.dpToPx
                }
            }
            val input = TextInputEditText(layout.context).apply {
                setText(field.preference.get())
                setSingleLine(!field.multiLine)
                minLines = if (field.multiLine) 3 else 1
                inputType = if (field.multiLine) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
            }
            layout.addView(input)
            container.addView(layout)
            inputs += field to input
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(MR.strings.pref_translation_configure_provider_title, engine.name))
            .setMessage(context.getString(MR.strings.pref_translation_subscription_note))
            .setView(container)
            .setPositiveButton(context.getString(MR.strings.save)) { _, _ ->
                inputs.forEach { (field, input) ->
                    field.preference.set(input.text?.toString().orEmpty().trim())
                }
                updateProviderStatus()
                context.toast(context.getString(MR.strings.pref_translation_provider_setup_saved, engine.name))
            }
            .setNegativeButton(context.getString(MR.strings.action_cancel), null)
            .show()
    }

    private fun providerFields(engineId: Long): List<ProviderField> = when (engineId) {
        TranslationEngineManager.ENGINE_OPENAI -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_openai_api_key), translationPreferences.openAiApiKey()),
            ProviderField(context.getString(MR.strings.pref_translation_openai_base_url), translationPreferences.openAiBaseUrl()),
            ProviderField(context.getString(MR.strings.pref_translation_openai_model), translationPreferences.openAiModel()),
        )
        TranslationEngineManager.ENGINE_DEEPSEEK -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_deepseek_api_key), translationPreferences.deepSeekApiKey()),
        )
        TranslationEngineManager.ENGINE_GEMINI -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_gemini_api_key), translationPreferences.geminiApiKey()),
            ProviderField(context.getString(MR.strings.pref_translation_gemini_model), translationPreferences.geminiModel()),
        )
        TranslationEngineManager.ENGINE_NVIDIA_NIM -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_nvidia_base_url), translationPreferences.nvidiaNimBaseUrl()),
            ProviderField(context.getString(MR.strings.pref_translation_nvidia_api_key), translationPreferences.nvidiaNimApiKey()),
            ProviderField(context.getString(MR.strings.pref_translation_nvidia_model), translationPreferences.nvidiaNimModel()),
        )
        TranslationEngineManager.ENGINE_OLLAMA -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_ollama_url), translationPreferences.ollamaUrl()),
            ProviderField(context.getString(MR.strings.pref_translation_ollama_model), translationPreferences.ollamaModel()),
        )
        TranslationEngineManager.ENGINE_LIBRE_TRANSLATE -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_libretranslate_url), translationPreferences.libreTranslateUrl()),
            ProviderField(context.getString(MR.strings.pref_translation_libretranslate_api_key), translationPreferences.libreTranslateApiKey()),
        )
        TranslationEngineManager.ENGINE_DEEPL -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_deepl_api_key), translationPreferences.deepLApiKey()),
        )
        TranslationEngineManager.ENGINE_GOOGLE_CLOUD -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_google_cloud_api_key), translationPreferences.googleApiKey()),
        )
        TranslationEngineManager.ENGINE_SYSTRAN -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_systran_api_key), translationPreferences.systranApiKey()),
        )
        TranslationEngineManager.ENGINE_HUGGING_FACE -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_huggingface_api_key), translationPreferences.huggingFaceApiKey()),
        )
        TranslationEngineManager.ENGINE_CUSTOM_HTTP -> listOf(
            ProviderField(context.getString(MR.strings.pref_translation_custom_http_url), translationPreferences.customHttpUrl()),
            ProviderField(context.getString(MR.strings.pref_translation_custom_http_api_key), translationPreferences.customHttpApiKey()),
            ProviderField(context.getString(MR.strings.pref_translation_custom_http_headers), translationPreferences.customHttpHeaders(), multiLine = true),
            ProviderField(context.getString(MR.strings.pref_translation_custom_http_request_template), translationPreferences.customHttpRequestTemplate(), multiLine = true),
            ProviderField(context.getString(MR.strings.pref_translation_custom_http_response_path), translationPreferences.customHttpResponsePath()),
        )
        else -> emptyList()
    }

    private fun reloadCurrentChapter(forceTranslate: Boolean = false) {
        ((context as? ReaderActivity)?.viewer as? NovelWebViewViewer)?.reloadWithTranslation(forceTranslate)
    }

    private data class ProviderField(
        val label: String,
        val preference: Preference<String>,
        val multiLine: Boolean = false,
    )

    private object TranslationHtmlLanguage {
        fun matches(a: String, b: String): Boolean =
            a.lowercase().substringBefore('-').substringBefore('_') ==
                b.lowercase().substringBefore('-').substringBefore('_')
    }
}
