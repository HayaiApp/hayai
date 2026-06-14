package exh.md.follows

import yokai.util.koin.get
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.source.getMainSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MangaDexFollowsScreenModel(
    sourceId: Long,
    sourceManager: SourceManager = get(),
) : StateScreenModel<MangaDexFollowsScreenModel.State>(State.Loading) {

    private val source = sourceManager.get(sourceId)
    private val mangaDex = source?.getMainSource<MangaDex>()

    init {
        loadFollows()
    }

    fun loadFollows(page: Int = 1) {
        val md = mangaDex ?: run {
            mutableState.value = State.Error("MangaDex source not found")
            return
        }
        mutableState.value = State.Loading
        screenModelScope.launch(Dispatchers.IO) {
            try {
                val result = md.fetchFollows(page)
                mutableState.value = State.Success(result.mangas, result.hasNextPage, page)
            } catch (e: Exception) {
                mutableState.value = State.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class State {
        data object Loading : State()
        data class Success(val mangas: List<SManga>, val hasNextPage: Boolean, val page: Int) : State()
        data class Error(val message: String) : State()
    }
}
