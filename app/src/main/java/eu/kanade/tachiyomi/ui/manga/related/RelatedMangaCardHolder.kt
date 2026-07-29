package eu.kanade.tachiyomi.ui.manga.related

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import yokai.domain.manga.models.MangaCover
import yokai.domain.manga.models.cover
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem
import yokai.presentation.theme.YokaiTheme

class RelatedMangaCardHolder(private val view: ComposeView, private val adapter: RelatedMangaCardAdapter) :
    BaseFlexibleViewHolder(view, adapter) {

    private var title by mutableStateOf("")
    private var cover by mutableStateOf(MangaCover(0L, 0L, "", 0L, false))

    init {
        itemView.setOnClickListener {
            val item = adapter.getItem(flexibleAdapterPosition)
            if (item != null) adapter.clickListener.onMangaClick(item.manga)
        }
        view.setContent {
            YokaiTheme {
                if (adapter.compact) {
                    MangaCompactGridItem(
                        coverData = cover,
                        title = title,
                        isSelected = cover.inLibrary,
                        showOutline = adapter.showOutlines,
                        inLibrary = cover.inLibrary,
                    )
                } else {
                    MangaComfortableGridItem(
                        coverData = cover,
                        title = title,
                        isSelected = cover.inLibrary,
                        showOutline = adapter.showOutlines,
                        inLibrary = cover.inLibrary,
                    )
                }
            }
        }
    }

    fun bind(manga: Manga) {
        title = manga.title
        cover = manga.cover()
    }
}
