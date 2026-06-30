package eu.kanade.tachiyomi.ui.reader.settings

import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.tabs.TabLayout
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.widget.TabbedBottomSheetDialog
import yokai.i18n.MR

/**
 * In-reader settings sheet for the novel reader. Shares the manga reader's container
 * ([TabbedBottomSheetDialog] + `tabbed_bottom_sheet.xml` + `bottom_sheet_rounded_background`)
 * so the visual style matches `TabbedReaderSettingsSheet` exactly. Each tab is an XML-inflated
 * [eu.kanade.tachiyomi.widget.BaseReaderSettingsView] that binds to `ReaderPreferences`.
 *
 * Five tabs: Reading | Display | Controls | Translation | TTS.
 */
class TabbedNovelReaderSettingsSheet(
    val readerActivity: ReaderActivity,
) : TabbedBottomSheetDialog(readerActivity) {

    private val readingView: NovelReadingView = View.inflate(
        readerActivity, R.layout.reader_novel_reading, null,
    ) as NovelReadingView
    private val appearanceView: NovelAppearanceView = View.inflate(
        readerActivity, R.layout.reader_novel_appearance, null,
    ) as NovelAppearanceView
    private val controlsView: NovelControlsView = View.inflate(
        readerActivity, R.layout.reader_novel_controls, null,
    ) as NovelControlsView
    private val translationView: NovelTranslationView = View.inflate(
        readerActivity, R.layout.reader_novel_translation, null,
    ) as NovelTranslationView
    private val ttsView: NovelTtsView = View.inflate(
        readerActivity, R.layout.reader_novel_tts, null,
    ) as NovelTtsView

    override fun getTabViews(): List<View> = listOf(
        readingView, appearanceView, controlsView, translationView, ttsView,
    )

    // "Display" is shorter than "Appearance" and prevents tab-text wrap on narrower phones.
    // EPUB toggles fold into Display; Find & Replace is reachable from the in-reader action
    // bar (the FindReplace icon button).
    override fun getTabTitles(): List<StringResource> = listOf(
        MR.strings.reading,
        MR.strings.display,
        MR.strings.controls,
        MR.strings.novel_translation_tab,
        MR.strings.novel_tts_tab,
    )

    init {
        readingView.activity = readerActivity
        appearanceView.activity = readerActivity
        controlsView.activity = readerActivity
        translationView.activity = readerActivity
        ttsView.activity = readerActivity

        binding.menu.isVisible = false
        binding.tabs.tabMode = TabLayout.MODE_SCROLLABLE
        binding.tabs.tabGravity = TabLayout.GRAVITY_START
    }
}
