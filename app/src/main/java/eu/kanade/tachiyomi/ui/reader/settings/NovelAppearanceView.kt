package eu.kanade.tachiyomi.ui.reader.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import eu.kanade.tachiyomi.databinding.ReaderNovelAppearanceBinding
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.widget.BaseReaderSettingsView
import hayai.novel.reader.settings.NovelStylePresetSection
import yokai.i18n.MR
import yokai.presentation.theme.YokaiTheme
import yokai.util.lang.getString

class NovelAppearanceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BaseReaderSettingsView<ReaderNovelAppearanceBinding>(context, attrs) {

    override fun inflateBinding() = ReaderNovelAppearanceBinding.bind(this)

    override fun initGeneralPreferences() {
        with(binding) {
            // Theme spinner
            val themeEntries = listOf(
                "app" to context.getString(MR.strings.novel_theme_app),
                "light" to context.getString(MR.strings.novel_theme_light),
                "dark" to context.getString(MR.strings.novel_theme_dark),
                "sepia" to context.getString(MR.strings.novel_theme_sepia),
                "black" to context.getString(MR.strings.novel_theme_black),
                "grey" to context.getString(MR.strings.novel_theme_grey),
                "custom" to context.getString(MR.strings.novel_theme_custom),
            )
            theme.setEntries(themeEntries.map { it.second })
            theme.setSelection(
                themeEntries.indexOfFirst { it.first == readerPreferences.novelTheme.get() }.coerceAtLeast(0),
            )
            theme.onItemSelectedListener = { idx ->
                readerPreferences.novelTheme.set(themeEntries[idx].first)
                updateCustomColorVisibility(themeEntries[idx].first)
            }
            updateCustomColorVisibility(readerPreferences.novelTheme.get())

            applySwatch(fontColorSwatch, readerPreferences.novelFontColor.get(), 0xFF000000.toInt())
            fontColorRow.setOnClickListener {
                showColorPicker(
                    title = context.getString(MR.strings.novel_font_color),
                    initial = readerPreferences.novelFontColor.get().takeIf { it != 0 } ?: 0xFF000000.toInt(),
                ) { c ->
                    readerPreferences.novelFontColor.set(c)
                    applySwatch(fontColorSwatch, c, 0xFF000000.toInt())
                }
            }
            applySwatch(backgroundColorSwatch, readerPreferences.novelBackgroundColor.get(), 0xFFFFFFFF.toInt())
            backgroundColorRow.setOnClickListener {
                showColorPicker(
                    title = context.getString(MR.strings.novel_background_color),
                    initial = readerPreferences.novelBackgroundColor.get().takeIf { it != 0 } ?: 0xFFFFFFFF.toInt(),
                ) { c ->
                    readerPreferences.novelBackgroundColor.set(c)
                    applySwatch(backgroundColorSwatch, c, 0xFFFFFFFF.toInt())
                }
            }

            val titleEntries = listOf(
                0 to context.getString(MR.strings.novel_chapter_title_name),
                1 to context.getString(MR.strings.novel_chapter_title_number),
                2 to context.getString(MR.strings.novel_chapter_title_both),
            )
            chapterTitleDisplay.setEntries(titleEntries.map { it.second })
            chapterTitleDisplay.setSelection(
                titleEntries.indexOfFirst { it.first == readerPreferences.novelChapterTitleDisplay.get() }
                    .coerceAtLeast(0),
            )
            chapterTitleDisplay.onItemSelectedListener = { idx ->
                readerPreferences.novelChapterTitleDisplay.set(titleEntries[idx].first)
            }

            hideChapterTitle.bindToPreference(readerPreferences.novelHideChapterTitle)
            forceLowercase.bindToPreference(readerPreferences.novelForceTextLowercase)
            // Novel-specific brightness (verbatim tsundoku keys). The viewer applies the value
            // to the activity window and restores system brightness on exit.
            customBrightness.bindToPreference(readerPreferences.novelCustomBrightness)
            keepScreenOn.bindToPreference(readerPreferences.novelKeepScreenOn)
            blockMedia.bindToPreference(readerPreferences.novelBlockMedia)
            showRawHtml.bindToPreference(readerPreferences.novelShowRawHtml)
            // EPUB toggles previously lived in a dedicated Advanced tab. Folded into Display
            // since they affect how the rendered chapter looks; Find & Replace lives on the
            // action bar so we don't need a tab just for those two switches.
            enableEpubStyles.bindToPreference(readerPreferences.enableEpubStyles)
            enableEpubJs.bindToPreference(readerPreferences.enableEpubJs)
            // Let source/EPUB CSS win — skips Hayai's !important overrides + font force.
            sourceCssPriority.bindToPreference(readerPreferences.novelSourceCssPriority)
            // Content cleanup: normalize fancy/fullwidth Unicode + strip ads/links.
            textNormalize.bindToPreference(readerPreferences.novelTextNormalize)
            textAggressiveCleanup.bindToPreference(readerPreferences.novelTextAggressiveCleanup)

            bindIntSlider(brightnessValue, MR.strings.novel_brightness_value, -75, 100, readerPreferences.novelCustomBrightnessValue.get()) {
                readerPreferences.novelCustomBrightnessValue.set(it)
            }

            // Saved global style presets — Compose section hosted in the Appearance tab. Applying a
            // preset writes back to the real style prefs so NovelWebViewPreferenceObserver re-styles live.
            stylePresetsCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            stylePresetsCompose.setContent {
                YokaiTheme {
                    NovelStylePresetSection(readerPreferences)
                }
            }
        }
    }

    private fun updateCustomColorVisibility(themeKey: String) {
        val isCustom = themeKey == "custom"
        binding.fontColorRow.isVisible = isCustom
        binding.backgroundColorRow.isVisible = isCustom
    }

    /**
     * Paints a circular swatch on a 32dp View showing the user's currently saved color.
     * Sentinel `0` (= theme default in the storage scheme) shows [fallback] so the swatch is
     * never blank.
     */
    private fun applySwatch(swatch: View, prefValue: Int, fallback: Int) {
        val color = prefValue.takeIf { it != 0 } ?: fallback
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(1.dpToPx, 0x66888888)
        }
    }

    private fun showColorPicker(title: String, initial: Int, onPick: (Int) -> Unit) {
        val pickerBinding = eu.kanade.tachiyomi.databinding.DialogNovelColorPickerBinding.inflate(
            LayoutInflater.from(context),
        )
        var argb = initial
        fun update() {
            pickerBinding.preview.setBackgroundColor(argb)
            pickerBinding.alpha.value = ((argb shr 24) and 0xFF).toFloat()
            pickerBinding.red.value = ((argb shr 16) and 0xFF).toFloat()
            pickerBinding.green.value = ((argb shr 8) and 0xFF).toFloat()
            pickerBinding.blue.value = (argb and 0xFF).toFloat()
        }
        update()
        val onChange: Slider.OnChangeListener = Slider.OnChangeListener { _, _, _ ->
            argb = (pickerBinding.alpha.value.toInt() shl 24) or
                (pickerBinding.red.value.toInt() shl 16) or
                (pickerBinding.green.value.toInt() shl 8) or
                pickerBinding.blue.value.toInt()
            pickerBinding.preview.setBackgroundColor(argb)
        }
        pickerBinding.alpha.addOnChangeListener(onChange)
        pickerBinding.red.addOnChangeListener(onChange)
        pickerBinding.green.addOnChangeListener(onChange)
        pickerBinding.blue.addOnChangeListener(onChange)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(pickerBinding.root)
            .setPositiveButton(context.getString(MR.strings.save)) { _, _ -> onPick(argb) }
            .setNegativeButton(context.getString(MR.strings.action_cancel), null)
            .show()
    }
}
