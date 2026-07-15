package eu.kanade.tachiyomi.ui.recents

import yokai.util.koin.get
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.asImage
import coil3.load
import dev.icerock.moko.resources.compose.stringResource
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.RecentsHeaderItemBinding
import eu.kanade.tachiyomi.databinding.RecentsSourceHeaderItemBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.view.setText
import hayai.novel.source.NovelSource
import yokai.i18n.MR
import yokai.util.lang.getString

class RecentMangaHeaderItem(
    val recentsType: Int,
    /** Source id when [recentsType] is [SOURCE]; null otherwise. */
    val sourceId: String? = null,
    /** Display name for the source header; null when not a source header. */
    val sourceName: String? = null,
) :
    AbstractHeaderItem<RecentMangaHeaderItem.Holder>() {

    override fun getLayoutRes(): Int {
        return if (recentsType == SOURCE) {
            R.layout.recents_source_header_item
        } else {
            R.layout.recents_header_item
        }
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): Holder {
        return Holder(view, adapter as RecentMangaAdapter, recentsType == SOURCE)
    }

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
            // Source headers are distinguished by the source id so the same
            // source under History vs Updates collapses to one header per tab,
            // while two different sources don't collide.
            return recentsType == other.recentsType && sourceId == other.sourceId
        }
        return false
    }

    override fun isDraggable(): Boolean {
        return false
    }

    override fun isSwipeable(): Boolean {
        return false
    }

    override fun hashCode(): Int {
        var result = recentsType.hashCode()
        if (sourceId != null) result = 31 * result + sourceId.hashCode()
        return result
    }

    override fun shouldNotifyChange(newItem: IFlexible<*>): Boolean {
        val other = newItem as? RecentMangaHeaderItem ?: return true
        return recentsType != other.recentsType ||
            sourceId != other.sourceId ||
            sourceName != other.sourceName
    }

    class Holder(
        val view: View,
        private val adapter: RecentMangaAdapter,
        private val isSourceHeader: Boolean,
    ) : BaseFlexibleViewHolder(view, adapter, true) {

        private val defaultBinding by lazy(LazyThreadSafetyMode.NONE) {
            RecentsHeaderItemBinding.bind(view)
        }
        private val sourceBinding by lazy(LazyThreadSafetyMode.NONE) {
            RecentsSourceHeaderItemBinding.bind(view)
        }

        fun bind(item: RecentMangaHeaderItem) {
            if (isSourceHeader) {
                bindSource(item)
            } else {
                bindDefault(item.recentsType)
            }
        }

        private fun bindDefault(recentsType: Int) {
            defaultBinding.title.setText(
                when (recentsType) {
                    CONTINUE_READING -> MR.strings.continue_reading
                    NEW_CHAPTERS -> MR.strings.new_chapters
                    NEWLY_ADDED -> MR.strings.newly_added
                    else -> MR.strings.continue_reading
                },
            )
        }

        private fun bindSource(item: RecentMangaHeaderItem) {
            val context = view.context
            val name = item.sourceName ?: context.getString(MR.strings.unknown)
            sourceBinding.title.text = name

            val source = item.sourceId?.toLongOrNull()
                ?.let { get<SourceManager>().getOrStub(it) }
            val iconView = sourceBinding.sourceIcon
            val fallback = ContextCompat.getDrawable(context, R.drawable.ic_book_24dp)
            when {
                source == null -> iconView.setImageDrawable(fallback)
                source.id == LocalSource.ID -> iconView.setImageResource(R.mipmap.ic_local_source)
                source is NovelSource -> {
                    val file = source.iconFile
                    when {
                        file != null && file.exists() -> iconView.load(file) {
                            fallback?.asImage()?.let { placeholder(it); error(it) }
                        }
                        !source.iconUrl.isNullOrBlank() -> iconView.load(source.iconUrl) {
                            fallback?.asImage()?.let { placeholder(it); error(it) }
                        }
                        else -> iconView.setImageDrawable(fallback)
                    }
                }
                else -> {
                    val icon = source.icon()
                    if (icon != null) iconView.setImageDrawable(icon)
                    else iconView.setImageDrawable(fallback)
                }
            }

            sourceBinding.overflow.setOnClickListener { anchor ->
                val sourceId = item.sourceId ?: return@setOnClickListener
                val popup = PopupMenu(anchor.context, anchor)
                popup.menu.add(
                    /* groupId = */ 0,
                    /* itemId = */ MENU_HIDE_SOURCE,
                    /* order = */ 0,
                    anchor.context.getString(MR.strings.recents_hide_from_source_, name),
                )
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.itemId == MENU_HIDE_SOURCE) {
                        adapter.delegate.onHideSourceClicked(sourceId)
                        true
                    } else {
                        false
                    }
                }
                popup.show()
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
