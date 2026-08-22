package dev.ahmedmohamed.hayai.novel.reader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.databinding.ReaderChapterItemBinding
import eu.kanade.tachiyomi.R

/** Binds J2K's reader chapter rows without introducing a second chapter model. */
internal class NovelReaderChapterAdapter(
    private val onChapterSelected: (Chapter) -> Unit,
    private val onBookmarkToggled: (Chapter) -> Unit,
) : RecyclerView.Adapter<NovelReaderChapterAdapter.ViewHolder>() {
    private var chapters = emptyList<Chapter>()
    private var selectedChapterId: Long? = null

    fun submit(chapters: List<Chapter>, selectedChapterId: Long?) {
        this.chapters = chapters
        this.selectedChapterId = selectedChapterId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ReaderChapterItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun getItemCount(): Int = chapters.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(chapters[position])
    }

    inner class ViewHolder(
        private val binding: ReaderChapterItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(chapter: Chapter) {
            val chapterId = chapter.id
            binding.chapterTitle.text = chapter.name
            binding.chapterSubtitle.text =
                when {
                    chapter.read -> binding.root.context.getString(R.string.read)
                    chapter.last_page_read > 0 -> binding.root.context.getString(R.string.hayai_novel_reader_percent_read, chapter.last_page_read.coerceIn(0, 100))
                    else -> binding.root.context.getString(R.string.unread)
                }
            binding.root.isActivated = chapterId == selectedChapterId
            binding.root.alpha = if (chapter.read) 0.65f else 1f
            binding.bookmarkImage.setImageResource(
                if (chapter.bookmark) eu.kanade.tachiyomi.R.drawable.ic_bookmark_24dp else eu.kanade.tachiyomi.R.drawable.ic_bookmark_border_24dp,
            )
            binding.progress.isVisible = false
            binding.root.setOnClickListener { onChapterSelected(chapter) }
            binding.bookmarkButton.setOnClickListener { onBookmarkToggled(chapter) }
        }
    }
}
