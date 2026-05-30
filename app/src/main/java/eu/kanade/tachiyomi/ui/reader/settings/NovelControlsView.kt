package eu.kanade.tachiyomi.ui.reader.settings

import android.content.Context
import android.util.AttributeSet
import eu.kanade.tachiyomi.databinding.ReaderNovelControlsBinding
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.widget.BaseReaderSettingsView
import yokai.i18n.MR
import yokai.util.lang.getString

class NovelControlsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BaseReaderSettingsView<ReaderNovelControlsBinding>(context, attrs) {

    override fun inflateBinding() = ReaderNovelControlsBinding.bind(this)

    override fun initGeneralPreferences() {
        with(binding) {
            bindIntSlider(autoScrollSpeed, MR.strings.novel_auto_scroll_speed, 1, 50, readerPreferences.novelAutoScrollSpeed.get()) {
                readerPreferences.novelAutoScrollSpeed.set(it)
            }

            // Continuous-scroll window + thresholds (verbatim tsundoku keys).
            bindIntSlider(autoLoadNextAt, MR.strings.novel_auto_load_next_at, 50, 100, readerPreferences.novelAutoLoadNextChapterAt.get()) {
                readerPreferences.novelAutoLoadNextChapterAt.set(it)
            }
            // 0 = keep every loaded chapter in the DOM; >0 caps the window around the active one.
            bindIntSlider(keepChaptersLoaded, MR.strings.novel_keep_chapters_loaded, 0, 10, readerPreferences.novelKeepChaptersLoaded.get()) {
                readerPreferences.novelKeepChaptersLoaded.set(it)
            }
            bindIntSlider(markReadThreshold, MR.strings.novel_mark_read_threshold, 50, 100, readerPreferences.novelMarkAsReadThreshold.get()) {
                readerPreferences.novelMarkAsReadThreshold.set(it)
            }
            markShortChapterRead.bindToPreference(readerPreferences.novelMarkShortChapterAsRead)

            volumeKeysScroll.bindToPreference(readerPreferences.novelVolumeKeysScroll)
            tapToScroll.bindToPreference(readerPreferences.novelTapToScroll)
            swipeNavigation.bindToPreference(readerPreferences.novelSwipeNavigation)
            textSelectable.bindToPreference(readerPreferences.novelTextSelectable)
            showProgressSlider.bindToPreference(readerPreferences.novelShowProgressSlider)
            verticalScrollbar.bindToPreference(readerPreferences.novelVerticalScrollbar)

            // Scrollbar position
            val scrollPosEntries = listOf(
                "right" to context.getString(MR.strings.novel_scrollbar_position_right),
                "left" to context.getString(MR.strings.novel_scrollbar_position_left),
            )
            scrollbarPosition.setEntries(scrollPosEntries.map { it.second })
            scrollbarPosition.setSelection(
                scrollPosEntries.indexOfFirst { it.first == readerPreferences.novelVerticalScrollbarPosition.get() }
                    .coerceAtLeast(0),
            )
            scrollbarPosition.onItemSelectedListener = { idx ->
                readerPreferences.novelVerticalScrollbarPosition.set(scrollPosEntries[idx].first)
            }

            // Vertical slider size
            val sliderSizeEntries = listOf(
                "half" to context.getString(MR.strings.novel_vertical_slider_half),
                "full" to context.getString(MR.strings.novel_vertical_slider_full),
            )
            verticalSliderSize.setEntries(sliderSizeEntries.map { it.second })
            verticalSliderSize.setSelection(
                sliderSizeEntries.indexOfFirst { it.first == readerPreferences.novelVerticalProgressSliderSize.get() }
                    .coerceAtLeast(0),
            )
            verticalSliderSize.onItemSelectedListener = { idx ->
                readerPreferences.novelVerticalProgressSliderSize.set(sliderSizeEntries[idx].first)
            }
        }
    }
}
