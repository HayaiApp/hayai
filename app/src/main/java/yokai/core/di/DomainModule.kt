package yokai.core.di

import org.koin.dsl.module
import yokai.data.category.CategoryRepositoryImpl
import yokai.data.chapter.ChapterRepositoryImpl
import yokai.data.extension.repo.ExtensionRepoRepositoryImpl
import yokai.data.history.HistoryRepositoryImpl
import yokai.data.library.custom.CustomMangaRepositoryImpl
import yokai.data.recents.RecentsHiddenRepositoryImpl
import yokai.data.series.SeriesKnowledgeRepositoryImpl
import yokai.data.stats.NovelStatsRepositoryImpl
import yokai.data.manga.EhFavoritesRepositoryImpl
import yokai.data.manga.MangaRepositoryImpl
import yokai.data.manga.MergedMangaRepositoryImpl
import yokai.data.source.browse.filter.SavedSearchRepositoryImpl
import yokai.data.track.TrackRepositoryImpl
import yokai.domain.category.CategoryRepository
import yokai.domain.category.interactor.DeleteCategories
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.InsertCategories
import yokai.domain.category.interactor.SetMangaCategories
import yokai.domain.category.interactor.UpdateCategories
import yokai.domain.chapter.ChapterRepository
import yokai.domain.chapter.interactor.DeleteChapter
import yokai.domain.chapter.interactor.GetAvailableScanlators
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.InsertChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.extension.interactor.TrustExtension
import yokai.domain.extension.repo.ExtensionRepoRepository
import yokai.domain.extension.repo.interactor.CreateExtensionRepo
import yokai.domain.extension.repo.interactor.DeleteExtensionRepo
import yokai.domain.extension.repo.interactor.GetExtensionRepo
import yokai.domain.extension.repo.interactor.GetExtensionRepoCount
import yokai.domain.extension.repo.interactor.ReplaceExtensionRepo
import yokai.domain.extension.repo.interactor.UpdateExtensionRepo
import yokai.domain.history.HistoryRepository
import yokai.domain.history.interactor.GetHistory
import yokai.domain.history.interactor.UpsertHistory
import yokai.domain.library.custom.CustomMangaRepository
import yokai.domain.library.custom.interactor.CreateCustomManga
import yokai.domain.library.custom.interactor.DeleteCustomManga
import yokai.domain.library.custom.interactor.GetCustomManga
import yokai.domain.library.custom.interactor.RelinkCustomManga
import yokai.domain.manga.EhFavoritesRepository
import yokai.domain.manga.MangaRepository
import yokai.domain.manga.MergedMangaRepository
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.GetMergedReferencesById
import yokai.domain.manga.interactor.FetchInterval
import yokai.domain.manga.interactor.GetUpcomingManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.recents.RecentsHiddenRepository
import yokai.domain.recents.interactor.GetHiddenRecents
import yokai.domain.recents.interactor.GetRecents
import yokai.domain.recents.interactor.HideRecents
import yokai.domain.recents.interactor.UnhideRecents
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.source.browse.filter.FilterSerializer
import yokai.domain.stats.NovelStatsRepository
import yokai.domain.stats.interactor.GetNovelStats
import yokai.domain.stats.interactor.SetChapterWordCount
import yokai.domain.source.browse.filter.SavedSearchRepository
import yokai.domain.source.browse.filter.interactor.DeleteSavedSearch
import yokai.domain.source.browse.filter.interactor.GetSavedSearch
import yokai.domain.source.browse.filter.interactor.InsertSavedSearch
import yokai.domain.track.TrackRepository
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack

fun domainModule() = module {
    factory { TrustExtension(get(), get()) }

    single<CategoryRepository> { CategoryRepositoryImpl(get()) }
    factory { DeleteCategories(get()) }
    factory { GetCategories(get()) }
    factory { InsertCategories(get()) }
    factory { UpdateCategories(get()) }

    single<ExtensionRepoRepository> { ExtensionRepoRepositoryImpl(get()) }
    factory { CreateExtensionRepo(get()) }
    factory { DeleteExtensionRepo(get()) }
    factory { GetExtensionRepo(get()) }
    factory { GetExtensionRepoCount(get()) }
    factory { ReplaceExtensionRepo(get()) }
    factory { UpdateExtensionRepo(get(), get()) }

    single<CustomMangaRepository> { CustomMangaRepositoryImpl(get()) }
    factory { CreateCustomManga(get()) }
    factory { DeleteCustomManga(get()) }
    factory { GetCustomManga(get()) }
    factory { RelinkCustomManga(get()) }

    single<MangaRepository> { MangaRepositoryImpl(get()) }
    factory { GetManga(get()) }
    factory { GetLibraryManga(get()) }
    factory { GetUpcomingManga(get()) }
    factory { InsertManga(get()) }
    factory { FetchInterval(get()) }
    factory { UpdateManga(get(), get()) }

    single<MergedMangaRepository> { MergedMangaRepositoryImpl(get()) }
    factory { GetMergedReferencesById(get()) }

    single<EhFavoritesRepository> { EhFavoritesRepositoryImpl(get()) }

    factory { SetMangaCategories(get()) }

    single<ChapterRepository> { ChapterRepositoryImpl(get()) }
    factory { DeleteChapter(get()) }
    factory { GetAvailableScanlators(get()) }
    factory { GetChapter(get()) }
    factory { InsertChapter(get()) }
    factory { UpdateChapter(get()) }

    single<HistoryRepository> { HistoryRepositoryImpl(get()) }
    factory { GetHistory(get()) }
    factory { UpsertHistory(get()) }

    single<NovelStatsRepository> { NovelStatsRepositoryImpl(get()) }
    factory { GetNovelStats(get()) }
    factory { SetChapterWordCount(get()) }

    factory { GetRecents(get(), get()) }

    single<RecentsHiddenRepository> { RecentsHiddenRepositoryImpl(get()) }
    factory { HideRecents(get()) }
    factory { UnhideRecents(get()) }
    factory { GetHiddenRecents(get()) }

    single<SeriesKnowledgeRepository> { SeriesKnowledgeRepositoryImpl(get()) }

    single<TrackRepository> { TrackRepositoryImpl(get()) }
    factory { DeleteTrack(get()) }
    factory { GetTrack(get()) }
    factory { InsertTrack(get()) }

    single<SavedSearchRepository> { SavedSearchRepositoryImpl(get()) }
    factory { DeleteSavedSearch(get()) }
    factory { GetSavedSearch(get()) }
    factory { InsertSavedSearch(get()) }
    factory { FilterSerializer() }
}
