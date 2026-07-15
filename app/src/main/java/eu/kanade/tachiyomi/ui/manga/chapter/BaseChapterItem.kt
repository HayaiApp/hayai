package eu.kanade.tachiyomi.ui.manga.chapter

import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.model.Download

abstract class BaseChapterItem<T : BaseChapterHolder, H : AbstractHeaderItem<*>>(
    val chapter: Chapter,
    header: H? = null,
) :
    AbstractSectionableItem<T, H?>(header),
    Chapter by chapter {

    private var _status: Download.State = Download.State.default

    val progress: Int
        get() {
            val pages = download?.pages ?: return 0
            if (pages.isEmpty()) return 0
            var total = 0
            pages.forEach { total += it.progress }
            return total / pages.size
        }

    var status: Download.State
        get() = download?.status ?: _status
        set(value) { _status = value }

    @Transient var download: Download? = null

    val isDownloaded: Boolean
        get() = status == Download.State.DOWNLOADED

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is BaseChapterItem<*, *>) {
            return chapter.id == other.chapter.id
        }
        return false
    }

    override fun hashCode(): Int {
        return (chapter.id ?: 0L).hashCode()
    }
}
