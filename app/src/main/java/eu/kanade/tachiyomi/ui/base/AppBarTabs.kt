package eu.kanade.tachiyomi.ui.base

import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout

/** A single tab entry passed to [ExpandedAppBarLayout.applyTabs]. */
sealed class TabItem {
    /** Plain text tab — used by Recents (History/Updates) and Browse (Manga/Novel). */
    data class Label(val text: String) : TabItem()

    /**
     * Text tab optionally decorated with a circular count badge — used by Library
     * category pills. [ExpandedAppBarLayout.applyTabs] inflates `chrome_tab_with_count`
     * as the tab's custom view and binds [text] into it; [count] is rendered only when
     * non-null (null honors the "show number of items" pref being off, hiding the badge).
     */
    data class Badged(val text: String, val count: Int?) : TabItem()
}

/** Maps to the corresponding [TabLayout] mode flag. */
enum class TabMode(internal val tabLayoutMode: Int) {
    /** Fixed-width tabs that fill the available width — Recents, Browse Novel/Manga. */
    Fixed(TabLayout.MODE_FIXED),

    /** Variable-width tabs that scroll horizontally — Library categories. */
    Scrollable(TabLayout.MODE_SCROLLABLE),
}

/**
 * Pager synchronisation for [ExpandedAppBarLayout.applyTabs]. When supplied, the
 * appBar installs a [ViewPager.OnPageChangeListener] that drives tab selection
 * from pager swipes and forwards each settle to [onPageSelected]. The listener
 * is detached on every subsequent `applyTabs` / `clearTabs` call.
 */
data class TabsPagerSync(
    val pager: ViewPager,
    val onPageSelected: (Int) -> Unit,
)
