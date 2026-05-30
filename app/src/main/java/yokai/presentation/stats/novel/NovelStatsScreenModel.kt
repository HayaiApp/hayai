package yokai.presentation.stats.novel

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import hayai.novel.plugin.NovelPluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import yokai.domain.stats.interactor.GetNovelStats
import yokai.domain.stats.models.NovelStats
import yokai.util.koin.injectLazy

class NovelStatsScreenModel :
    StateScreenModel<NovelStatsScreenModel.State>(State.Loading) {

    private val getNovelStats: GetNovelStats by injectLazy()
    private val novelPluginManager: NovelPluginManager by injectLazy()

    init {
        load()
    }

    fun load() {
        screenModelScope.launch(Dispatchers.IO) {
            // Scope every aggregate to the currently-installed novel sources so manga never leak in.
            val sourceIds = novelPluginManager.installedSourcesFlow.value.map { it.id }
            val stats = getNovelStats.await(sourceIds)
            mutableState.update { State.Loaded(stats) }
        }
    }

    sealed interface State {
        data object Loading : State
        data class Loaded(val stats: NovelStats) : State
    }
}
