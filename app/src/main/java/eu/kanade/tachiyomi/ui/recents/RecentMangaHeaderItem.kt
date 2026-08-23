package eu.kanade.tachiyomi.ui.recents

import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.RecentsHeaderItemBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.system.dpToPx
import uy.kohesive.injekt.injectLazy

class RecentMangaHeaderItem(
    val recentsType: Int,
    val sourceId: Long? = null,
    val sourceName: String? = null,
) : AbstractHeaderItem<RecentMangaHeaderItem.Holder>() {
    init {
        require((recentsType == SOURCE) == (sourceId != null && sourceName != null))
    }

    override fun getLayoutRes(): Int = R.layout.recents_header_item

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): Holder = Holder(view, adapter as RecentMangaAdapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: Holder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is RecentMangaHeaderItem) {
            return recentsType == other.recentsType && sourceId == other.sourceId
        }
        return false
    }

    override fun isDraggable(): Boolean = false

    override fun isSwipeable(): Boolean = false

    override fun hashCode(): Int = 31 * recentsType.hashCode() + (sourceId?.hashCode() ?: 0)

    class Holder(
        val view: View,
        private val adapter: RecentMangaAdapter,
    ) : BaseFlexibleViewHolder(
            view,
            adapter,
            true,
        ) {
        private val binding = RecentsHeaderItemBinding.bind(view)
        private val sourceManager: SourceManager by injectLazy()

        fun bind(item: RecentMangaHeaderItem) {
            if (item.recentsType == SOURCE) {
                bindSource(item)
            } else {
                binding.title.setText(
                    when (item.recentsType) {
                        CONTINUE_READING -> R.string.continue_reading
                        NEW_CHAPTERS -> R.string.new_chapters
                        NEWLY_ADDED -> R.string.newly_added
                        else -> R.string.continue_reading
                    },
                )
                binding.title.setCompoundDrawablesRelative(null, null, null, null)
                binding.title.setOnClickListener(null)
                binding.title.isClickable = false
                binding.title.tooltipText = null
            }
        }

        private fun bindSource(item: RecentMangaHeaderItem) {
            val sourceId = requireNotNull(item.sourceId)
            binding.title.text = requireNotNull(item.sourceName)
            val source = sourceManager.getOrStub(sourceId)
            val sourceIcon =
                when (source.id) {
                    LocalSource.ID -> ContextCompat.getDrawable(itemView.context, R.mipmap.ic_local_source)
                    LocalNovelSource.ID -> ContextCompat.getDrawable(itemView.context, R.drawable.ic_local_novel_source)
                    else -> source.icon() ?: ContextCompat.getDrawable(itemView.context, R.drawable.ic_book_24dp)
                }
            sourceIcon?.setBounds(0, 0, 24.dpToPx, 24.dpToPx)
            val menuIcon = ContextCompat.getDrawable(itemView.context, R.drawable.ic_more_vert_24dp)
            menuIcon?.setBounds(0, 0, 24.dpToPx, 24.dpToPx)
            binding.title.compoundDrawablePadding = 8.dpToPx
            binding.title.setCompoundDrawablesRelative(sourceIcon, null, menuIcon, null)
            binding.title.isClickable = true
            binding.title.tooltipText = itemView.context.getString(R.string.display_options)
            binding.title.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor)
                    .apply {
                        menu.add(0, MENU_HIDE_SOURCE, 0, R.string.hide)
                        setOnMenuItemClickListener { menuItem ->
                            if (menuItem.itemId == MENU_HIDE_SOURCE) {
                                adapter.delegate.onHideSourceClicked(sourceId)
                                true
                            } else {
                                false
                            }
                        }
                    }.show()
            }
        }

        override fun onLongClick(view: View?): Boolean {
            super.onLongClick(view)
            return false
        }
    }

    companion object {
        const val CONTINUE_READING = 0
        const val NEW_CHAPTERS = 1
        const val NEWLY_ADDED = 2
        const val SOURCE = 3
        private const val MENU_HIDE_SOURCE = 1
    }
}
