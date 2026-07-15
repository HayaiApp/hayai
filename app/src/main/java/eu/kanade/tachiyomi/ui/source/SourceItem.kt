package eu.kanade.tachiyomi.ui.source

import yokai.util.koin.get
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import hayai.novel.source.NovelSource
import yokai.domain.ui.UiPreferences

/**
 * Item that contains source information.
 *
 * @param source Instance of [CatalogueSource] containing source information.
 * @param header The header for this item.
 */
class SourceItem(val source: CatalogueSource, header: LangItem? = null, val isPinned: Boolean? = null) :
    AbstractSectionableItem<SourceHolder, LangItem>(header) {

    /**
     * Returns the layout resource of this item.
     */
    override fun getLayoutRes(): Int {
        return R.layout.source_item
    }

    override fun isSwipeable(): Boolean {
        return get<UiPreferences>().enableSourceSwipeAction().get() && source.id != LocalSource.ID && header != null && header.code != SourcePresenter.LAST_USED_KEY
    }

    /**
     * Creates a new view holder for this item.
     */
    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): SourceHolder {
        return SourceHolder(view, adapter as SourceAdapter)
    }

    /**
     * Binds this item to the given view holder.
     */
    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: SourceHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        holder.bind(this)
    }

    override fun equals(other: Any?): Boolean {
        if (other is SourceItem) {
            return source.id == other.source.id &&
                header?.code == other.header?.code &&
                isPinned == other.isPinned
        }
        return false
    }

    override fun hashCode(): Int {
        var result = source.id.hashCode()
        result = 31 * result + (header?.hashCode() ?: 0)
        result = 31 * result + isPinned.hashCode()
        return result
    }

    /** Complete state consumed by [SourceHolder], separate from list-item identity. */
    internal fun bindingContentSignature(): Int {
        var result = getLayoutRes()
        fun include(value: Any?) {
            result = 31 * result + (value?.hashCode() ?: 0)
        }
        include(source::class)
        include(source.id)
        include(source.name)
        include(source.toString())
        include(source.lang)
        include(source.supportsLatest)
        include(header?.code)
        include(isPinned)
        if (source is NovelSource) {
            include(source.iconFile?.path)
            include(source.iconFile?.lastModified())
            include(source.iconUrl)
        }
        return result
    }
}
