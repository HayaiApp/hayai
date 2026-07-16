package eu.kanade.tachiyomi.ui.source.globalsearch

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.domain.manga.models.Manga
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// FIXME: Migrate to compose
class GlobalSearchMangaItem(
    initialManga: Manga,
    private val mangaFlow: Flow<Manga?>,
) : AbstractFlexibleItem<GlobalSearchMangaHolder>() {

    val mangaId: Long? = initialManga.id
    var manga: Manga = initialManga
        private set
    private var job: Job? = null

    override fun getLayoutRes(): Int {
        return R.layout.source_global_search_controller_card_item
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): GlobalSearchMangaHolder {
        return GlobalSearchMangaHolder(view, adapter as GlobalSearchCardAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: GlobalSearchMangaHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        if (job == null) holder.bind(manga)
        job?.cancel()
        job = (adapter as GlobalSearchCardAdapter).controller.viewScope.launch {
            mangaFlow.collectLatest {
                manga = it ?: return@collectLatest
                holder.bind(manga)
            }
        }
    }

    override fun unbindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: GlobalSearchMangaHolder?,
        position: Int
    ) {
        job?.cancel()
        job = null
    }

    override fun equals(other: Any?): Boolean {
        if (other is GlobalSearchMangaItem) {
            return mangaId == other.mangaId
        }
        return false
    }

    override fun hashCode(): Int {
        return mangaId?.toInt() ?: 0
    }
}
