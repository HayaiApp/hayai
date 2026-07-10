package eu.kanade.tachiyomi.ui.source

import android.content.res.Resources
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.launchIO
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background prefetch for root-tab cold paths.
 *
 * Browse is the heaviest of the three root tabs to cold-enter: it inflates the
 * source list AND the bottom-sheet ViewPager (extensions / novel plugins /
 * migration) into the same frame the cross-fade is animating. We can't move
 * the XML inflation itself off the UI thread (AsyncLayoutInflater crashes on
 * any layout containing a View whose constructor touches `Animator`, which
 * burned us on `recent_sub_chapter_item`), but we can pay the *parsing* cost
 * in advance from a background thread so the on-frame inflate skips it.
 *
 * `Resources.getXml` returns an `XmlResourceParser` backed by an `XmlBlock`
 * that the AssetManager caches per (resourceId, configuration). Iterating the
 * parser once forces the binary XML to be decoded and stored — subsequent
 * `LayoutInflater.inflate` / `MenuInflater.inflate` calls reuse the cached
 * block and skip the parse step (~5–15ms saved per layout).
 *
 * We also `Class.forName` the Browse-side classes that the cold path would
 * load lazily (adapters, presenters, custom views). This pulls dexopt + class
 * verification + static-init work off the cold frame.
 *
 * Idempotent — only ever runs once per process. Failures are swallowed (we
 * don't want a warmup miss to crash the activity).
 */
object RootTabWarmup {

    private val primed = AtomicBoolean(false)

    /**
     * Kick off the warmup on a background coroutine. Safe to call multiple times.
     * Call from [eu.kanade.tachiyomi.ui.main.MainActivity.onCreate] after `super.onCreate`.
     */
    fun primeAsync(resources: Resources) {
        if (!primed.compareAndSet(false, true)) return
        launchIO {
            primeXmlCache(resources)
            primeClassCache()
        }
    }

    /**
     * Walks each XML resource end-to-end so the AssetManager parses + caches the
     * binary XML. `runCatching` because a missing resource (e.g. removed in a
     * future refactor) shouldn't take down the cold path.
     */
    private fun primeXmlCache(resources: Resources) {
        XML_IDS.forEach { id ->
            runCatching {
                resources.getXml(id).use { parser ->
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        // Drain the parser to force the AssetManager to fully
                        // decode the binary XML into its cache.
                    }
                }
            }
        }
    }

    private fun primeClassCache() {
        CLASS_NAMES.forEach { name ->
            runCatching { Class.forName(name) }
        }
    }

    // Root layouts and expensive rows identified in first-attach traces. View construction
    // remains on main; only binary XML decoding is paid here.
    private val XML_IDS = intArrayOf(
        R.layout.library_controller,
        R.layout.library_pager_page,
        R.layout.manga_grid_item,
        R.layout.manga_list_item,
        R.layout.library_category_header_item,
        R.layout.recents_controller,
        R.layout.recent_manga_item,
        R.layout.recent_sub_chapter_item,
        R.layout.recent_chapters_section_item,
        R.layout.recents_header_item,
        R.layout.recents_source_header_item,
        R.layout.recents_footer_item,
        R.layout.browse_controller,
        R.layout.extensions_bottom_sheet,
        R.layout.recycler_with_scroller,
        R.layout.source_item,
        R.layout.source_header_item,
        R.layout.extension_card_item,
        R.layout.extension_card_header,
        R.menu.extension_main,
        R.menu.migration_main,
        R.menu.catalogue_main,
    )

    // Classes the root paths lazy-load. Class.forName here triggers dexopt +
    // verification on the IO thread so the UI thread doesn't pay it.
    private val CLASS_NAMES = listOf(
        "eu.kanade.tachiyomi.ui.library.LibraryController",
        "eu.kanade.tachiyomi.ui.library.LibraryCategoryAdapter",
        "eu.kanade.tachiyomi.ui.library.LibraryPagerAdapter",
        "eu.kanade.tachiyomi.ui.library.LibraryGridHolder",
        "eu.kanade.tachiyomi.ui.library.LibraryListHolder",
        "eu.kanade.tachiyomi.ui.recents.RecentsController",
        "eu.kanade.tachiyomi.ui.recents.RecentMangaAdapter",
        "eu.kanade.tachiyomi.ui.recents.RecentMangaHolder",
        "eu.kanade.tachiyomi.ui.recents.DateItem",
        "eu.kanade.tachiyomi.ui.extension.ExtensionBottomSheet",
        "eu.kanade.tachiyomi.ui.extension.ExtensionAdapter",
        "eu.kanade.tachiyomi.ui.extension.ExtensionBottomPresenter",
        "eu.kanade.tachiyomi.ui.extension.RecyclerWithScrollerView",
        "eu.kanade.tachiyomi.ui.source.SourceAdapter",
        "eu.kanade.tachiyomi.ui.source.SourcePresenter",
        "eu.kanade.tachiyomi.ui.migration.SourceAdapter",
        "eu.kanade.tachiyomi.ui.migration.MangaAdapter",
        "hayai.novel.ui.NovelPluginAdapter",
    )
}
