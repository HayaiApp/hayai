package eu.kanade.tachiyomi.ui.recents

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterItem

class RecentMangaItem(
    val mch: MangaChapterHistory = MangaChapterHistory.createBlank(),
    chapter: Chapter = ChapterImpl(),
    header: AbstractHeaderItem<*>?,
) :
    BaseChapterItem<BaseChapterHolder, AbstractHeaderItem<*>>(chapter, header) {

    var downloadInfo = listOf<DownloadInfo>()

    override fun getLayoutRes(): Int {
        return if (mch.manga.id == null) {
            R.layout.recents_footer_item
        } else {
            R.layout.recent_manga_item
        }
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): BaseChapterHolder {
        return if (mch.manga.id == null) {
            RecentMangaFooterHolder(view, adapter as RecentMangaAdapter)
        } else {
            RecentMangaHolder(view, adapter as RecentMangaAdapter)
        }
    }

    override fun isSwipeable(): Boolean {
        return mch.manga.id != null
    }

    /**
     * Selection mode only includes real top-level items. Footers (rows where
     * [mch.manga.id] is null, e.g. "Continue reading" / "Newly added") are not
     * checkable. Sub-chapters are handled inside the holder and never receive a
     * selectable position of their own.
     */
    override fun isSelectable(): Boolean {
        return mch.manga.id != null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is RecentMangaItem) {
            return if (mch.manga.id == null) {
                (header as? RecentMangaHeaderItem)?.recentsType ==
                    (other.header as? RecentMangaHeaderItem)?.recentsType
            } else {
                chapter.id == other.chapter.id
            }
        }
        return false
    }

    override fun hashCode(): Int {
        return if (mch.manga.id == null) {
            -((header as? RecentMangaHeaderItem)?.recentsType ?: 0).hashCode()
        } else {
            (chapter.id ?: 0L).hashCode()
        }
    }

    /**
     * Stable snapshot of every model field consumed by [RecentMangaHolder]. The adapter
     * stores these values after a full submission so repeated presenter emissions with
     * identical content don't trigger another notifyDataSetChanged + visible-row rebind.
     */
    internal fun bindingContentSignature(): Int {
        var result = getLayoutRes()
        fun include(value: Any?) {
            result = 31 * result + (value?.hashCode() ?: 0)
        }

        val header = header
        when (header) {
            is DateItem -> {
                include(header.date.time)
                include(header.addedString)
            }
            is RecentMangaHeaderItem -> {
                include(header.recentsType)
                include(header.sourceId)
                include(header.sourceName)
            }
            else -> include(header)
        }

        if (mch.manga.id == null) return result

        include(mch.manga.id)
        include(mch.manga.source)
        include(mch.manga.title)
        include(mch.manga.thumbnail_url)
        include(mch.manga.date_added)
        include(mch.manga.cover_last_modified)

        includeChapter(chapter, ::include)
        include(mch.history.id)
        include(mch.history.last_read)
        include(mch.history.time_read)

        mch.extraChapters.forEach { extra ->
            includeChapter(extra.chapter, ::include)
            include(extra.history?.id)
            include(extra.history?.last_read)
            include(extra.history?.time_read)
        }

        include(status)
        include(progress)
        downloadInfo.forEach { info ->
            include(info.chapterId)
            include(info.status)
            include(info.progress)
        }
        return result
    }

    private fun includeChapter(chapter: Chapter, include: (Any?) -> Unit) {
        include(chapter.id)
        include(chapter.name)
        include(chapter.scanlator)
        include(chapter.read)
        include(chapter.bookmark)
        include(chapter.last_page_read)
        include(chapter.pages_left)
        include(chapter.date_fetch)
        include(chapter.date_upload)
        include(chapter.chapter_number)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: BaseChapterHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        if (mch.manga.id == null) {
            (holder as? RecentMangaFooterHolder)?.bind((header as? RecentMangaHeaderItem)?.recentsType ?: 0)
        } else if (chapter.id != null) (holder as? RecentMangaHolder)?.bind(this)
    }

    class DownloadInfo {
        private var _status: Download.State = Download.State.default

        var chapterId: Long? = 0L

        val progress: Int
            get() {
                val pages = download?.pages ?: return 0
                return pages.map(Page::progress).average().toInt()
            }

        var status: Download.State
            get() = download?.status ?: _status
            set(value) { _status = value }

        @Transient var download: Download? = null

        val isDownloaded: Boolean
            get() = status == Download.State.DOWNLOADED
    }
}
