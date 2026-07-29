package yokai.presentation.manga.related

import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.flow.collectLatest
import uy.kohesive.injekt.injectLazy
import yokai.domain.manga.interactor.GetManga

class RelatedMangaPresenter(private val mangaIds: List<Long>) : BaseCoroutinePresenter<RelatedMangaController>() {

    private val getManga: GetManga by injectLazy()

    override fun onCreate() {
        super.onCreate()
        presenterScope.launchIO {
            // Subscribed (rather than a one-shot fetch) so favorite/library status shown on each
            // card stays in sync if it changes elsewhere while this screen is open.
            getManga.subscribeAll().collectLatest { allMangas ->
                val byId = allMangas.associateBy { it.id }
                val mangas = mangaIds.mapNotNull { byId[it] }
                withUIContext { view?.setMangas(mangas) }
            }
        }
    }
}
