package yokai.presentation.manga.related

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import eu.kanade.tachiyomi.databinding.RelatedMangaControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCardAdapter
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCardItem
import eu.kanade.tachiyomi.util.view.liftAppbarWith
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.i18n.MR
import yokai.util.lang.getString

class RelatedMangaController(bundle: Bundle) :
    BaseCoroutineController<RelatedMangaControllerBinding, RelatedMangaPresenter>(bundle),
    RelatedMangaCardAdapter.OnMangaClickListener,
    SmallToolbarInterface {

    constructor(mangaId: Long, mangaTitle: String, mangaIds: List<Long>) : this(
        bundleOf(MANGA_ID to mangaId, MANGA_TITLE to mangaTitle, MANGA_IDS to mangaIds.toLongArray()),
    )

    override val presenter = RelatedMangaPresenter(args.getLongArray(MANGA_IDS)?.toList().orEmpty())

    private var adapter: RelatedMangaCardAdapter? = null

    override fun createBinding(inflater: LayoutInflater) = RelatedMangaControllerBinding.inflate(inflater)

    override fun getTitle(): String? = args.getString(MANGA_TITLE)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        binding.sectionTitle.text = activity?.getString(MR.strings.related_mangas_site_suggestions)
        binding.sectionTitle.isVisible = true
        binding.sectionDividerTop.isVisible = true
        binding.sectionDividerBottom.isVisible = true
        val cardAdapter = RelatedMangaCardAdapter(this)
        adapter = cardAdapter
        binding.recycler.layoutManager = GridLayoutManager(view.context, 3)
        binding.recycler.adapter = cardAdapter

        // padView = false pads the whole screen root once (header block + recycler together)
        // instead of the recycler alone - the header block sits above the recycler here, so
        // giving the recycler its own top margin on top of that would double-count the inset.
        liftAppbarWith(binding.recycler, padView = false)
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    fun setMangas(mangas: List<Manga>) {
        adapter?.updateDataSet(mangas.map { RelatedMangaCardItem(it) })
    }

    override fun onMangaClick(manga: Manga) {
        router.pushController(MangaDetailsController(manga, true).withFadeTransaction())
    }

    companion object {
        private const val MANGA_ID = "manga_id"
        private const val MANGA_TITLE = "manga_title"
        private const val MANGA_IDS = "manga_ids"
    }
}
