package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    var chapterLocked: Boolean

    var read: Boolean
        get() = false
        set(_) {}

    var last_page_read: Int
        get() = 0
        set(_) {}

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        chapterLocked = other.chapterLocked
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}
