package eu.kanade.tachiyomi.ui.reader.settings

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.AttributeSet
import eu.kanade.tachiyomi.databinding.ReaderNovelTtsBinding
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.widget.BaseReaderSettingsView
import yokai.i18n.MR
import yokai.util.lang.getString

class NovelTtsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BaseReaderSettingsView<ReaderNovelTtsBinding>(context, attrs) {

    override fun inflateBinding() = ReaderNovelTtsBinding.bind(this)

    private var probeTts: TextToSpeech? = null
    private val engineEntries = mutableListOf<Pair<String, String>>()
    private val voiceEntries = mutableListOf<Pair<String, String>>()

    override fun initGeneralPreferences() {
        with(binding) {
            ttsAutoNext.bindToPreference(readerPreferences.novelTtsAutoNextChapter)
            ttsEnableHighlight.bindToPreference(readerPreferences.novelTtsEnableHighlight)
            ttsKeepInView.bindToPreference(readerPreferences.novelTtsKeepHighlightInView)
            ttsBackgroundPlayback.bindToPreference(readerPreferences.novelTtsBackgroundPlayback)

            bindFloatSlider(ttsSpeed, MR.strings.novel_tts_speed, 5, 20, readerPreferences.novelTtsSpeed.get()) {
                readerPreferences.novelTtsSpeed.set(it)
            }
            bindFloatSlider(ttsPitch, MR.strings.novel_tts_pitch, 5, 20, readerPreferences.novelTtsPitch.get()) {
                readerPreferences.novelTtsPitch.set(it)
            }

            // Highlight style
            val styleEntries = listOf(
                "background" to context.getString(MR.strings.novel_tts_highlight_background),
                "underline" to context.getString(MR.strings.novel_tts_highlight_underline),
                "outline" to context.getString(MR.strings.novel_tts_highlight_outline),
            )
            ttsHighlightStyle.setEntries(styleEntries.map { it.second })
            ttsHighlightStyle.setSelection(
                styleEntries.indexOfFirst { it.first == readerPreferences.novelTtsHighlightStyle.get() }
                    .coerceAtLeast(0),
            )
            ttsHighlightStyle.onItemSelectedListener = { idx ->
                readerPreferences.novelTtsHighlightStyle.set(styleEntries[idx].first)
            }

            openTtsSettings.setOnClickListener {
                runCatching {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            // Engine probe — populate engine + voice entries asynchronously.
            populateEngineAndVoiceEntries()
        }
    }

    private fun populateEngineAndVoiceEntries() {
        val systemDefaultLabel = context.getString(MR.strings.novel_tts_engine_default)
        val voiceDefaultLabel = context.getString(MR.strings.novel_tts_voice_default)
        val engineSelected = readerPreferences.novelTtsEngine.get()
        val voiceSelected = readerPreferences.novelTtsVoice.get()

        val listener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) return@OnInitListener

            val tts = probeTts ?: return@OnInitListener

            engineEntries.clear()
            engineEntries += "" to systemDefaultLabel
            tts.engines?.forEach { e -> engineEntries += e.name to (e.label ?: e.name) }

            voiceEntries.clear()
            voiceEntries += "" to voiceDefaultLabel
            tts.voices
                ?.filter { !it.isNetworkConnectionRequired }
                ?.sortedBy { "${it.locale.displayLanguage} (${it.name})" }
                ?.forEach { v -> voiceEntries += v.name to "${v.locale.displayLanguage} (${v.name})" }

            post {
                with(binding) {
                    ttsEngine.setEntries(engineEntries.map { it.second })
                    ttsEngine.setSelection(
                        engineEntries.indexOfFirst { it.first == engineSelected }.coerceAtLeast(0),
                    )
                    ttsEngine.onItemSelectedListener = { idx ->
                        readerPreferences.novelTtsEngine.set(engineEntries[idx].first)
                        // Engine changed: clear voice (voices are engine-scoped) and reprobe.
                        readerPreferences.novelTtsVoice.set("")
                        probeTts?.shutdown()
                        probeTts = null
                        populateEngineAndVoiceEntries()
                    }

                    ttsVoice.setEntries(voiceEntries.map { it.second })
                    ttsVoice.setSelection(
                        voiceEntries.indexOfFirst { it.first == voiceSelected }.coerceAtLeast(0),
                    )
                    ttsVoice.onItemSelectedListener = { idx ->
                        readerPreferences.novelTtsVoice.set(voiceEntries[idx].first)
                    }
                }
            }
        }

        probeTts = if (engineSelected.isNotBlank()) {
            TextToSpeech(context.applicationContext, listener, engineSelected)
        } else {
            TextToSpeech(context.applicationContext, listener)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        probeTts?.shutdown()
        probeTts = null
    }
}
