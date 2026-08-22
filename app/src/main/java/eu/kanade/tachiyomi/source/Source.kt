package eu.kanade.tachiyomi.source

import android.graphics.drawable.Drawable
import dev.ahmedmohamed.hayai.source.presentation.BundledSourceIconResolver
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.awaitSingle
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {
    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether this source provides text-based novel content instead of images.
     *
     * This is part of Tsundoku's extensions-lib 1.5 ABI. Keep the default so
     * ordinary J2K extensions remain binary compatible.
     */
    val isNovelSource: Boolean
        get() = false

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga = fetchMangaDetails(manga).awaitSingle()

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> = fetchChapterList(manga).awaitSingle()

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).awaitSingle()

    /**
     * Fetches the text content for a novel page.
     *
     * This matches Tsundoku's extensions-lib 1.5 source contract so novel
     * extensions can run without a Hayai-specific extension API.
     */
    suspend fun fetchPageText(page: Page): String = throw UnsupportedOperationException("Not a novel source")

    /**
     * Fetches updated information for a manga.
     *
     * Depending on the provided flags, this may include updated manga metadata,
     * available chapters, or both. If a value is not requested, the passed-in value
     * is returned as-is.
     *
     * @since extensions-lib 1.6
     * @param manga the manga to fetch updates for.
     * @param chapters existing chapters of the manga.
     * @param fetchDetails whether to fetch updated manga details.
     * @param fetchChapters whether to fetch available chapters.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate =
        supervisorScope {
            val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
            val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
            SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
        }

    fun includeLangInName(
        enabledLanguages: Set<String>,
        extensionManager: ExtensionManager? = null,
    ): Boolean {
        val httpSource = this as? HttpSource ?: return true
        val extManager = extensionManager ?: Injekt.get()
        val allExt = httpSource.getExtension(extManager)?.lang == "all"
        val onlyAll = httpSource.extOnlyHasAllLanguage(extManager)
        val isMultiLingual = enabledLanguages.filterNot { it == "all" }.size > 1
        return (isMultiLingual && allExt) || (lang == "all" && !onlyAll) || lang !in enabledLanguages
    }

    fun nameBasedOnEnabledLanguages(
        enabledLanguages: Set<String>,
        extensionManager: ExtensionManager? = null,
    ): String = if (includeLangInName(enabledLanguages, extensionManager)) toString() else name

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw IllegalStateException("Not used")
}

fun Source.icon(): Drawable? =
    Injekt.get<ExtensionManager>().getAppIconForSource(this)
        ?: BundledSourceIconResolver.drawable(Injekt.get(), id)

fun Source.pkgName() = Injekt.get<ExtensionManager>().getPackageName(id)

fun Source.preferenceKey(): String = "source_$id"

fun Source.isIncognitoMode(): Boolean = isIncognitoModeForSource(id)

/**
 * Whether [sourceId] should be treated as incognito, either because global incognito mode
 * is on or because the extension providing it has per-extension incognito mode enabled.
 */
fun isIncognitoModeForSource(
    sourceId: Long?,
    preferences: PreferencesHelper = Injekt.get(),
): Boolean {
    if (preferences.incognitoMode().get()) return true
    if (sourceId == null) return false
    val pkgName = Injekt.get<ExtensionManager>().getPackageName(sourceId) ?: return false
    return pkgName in preferences.incognitoExtensions().get()
}
