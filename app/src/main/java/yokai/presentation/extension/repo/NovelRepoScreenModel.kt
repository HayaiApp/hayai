package yokai.presentation.extension.repo

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.util.system.launchIO
import hayai.novel.repo.interactor.CreateNovelRepo
import hayai.novel.repo.interactor.DeleteNovelRepo
import hayai.novel.repo.interactor.GetNovelRepo
import hayai.novel.repo.model.NovelRepo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.i18n.MR

class NovelRepoScreenModel : StateScreenModel<NovelRepoScreenModel.State>(State.Loading), KoinComponent {

    private val getNovelRepo: GetNovelRepo by inject()
    private val createNovelRepo: CreateNovelRepo by inject()
    private val deleteNovelRepo: DeleteNovelRepo by inject()

    private val internalEvent = MutableSharedFlow<ExtensionRepoEvent>()
    val event: SharedFlow<ExtensionRepoEvent> = internalEvent.asSharedFlow()
    private val mutableIsAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = mutableIsAdding.asStateFlow()

    init {
        screenModelScope.launchIO {
            getNovelRepo.subscribeAll().collectLatest { repos ->
                mutableState.update { State.Success(repos = repos.toImmutableList()) }
            }
        }
    }

    fun addRepo(url: String) {
        if (!mutableIsAdding.compareAndSet(expect = false, update = true)) return
        screenModelScope.launchIO {
            try {
                when (createNovelRepo.await(url.trim())) {
                    is CreateNovelRepo.Result.Success -> {
                        internalEvent.emit(ExtensionRepoEvent.Success)
                    }
                    is CreateNovelRepo.Result.InvalidUrl -> {
                        internalEvent.emit(ExtensionRepoEvent.InvalidUrl)
                    }
                    is CreateNovelRepo.Result.InvalidIndex -> {
                        internalEvent.emit(NovelRepoEvent.InvalidIndex)
                    }
                    is CreateNovelRepo.Result.EmptyRepo -> {
                        internalEvent.emit(NovelRepoEvent.EmptyRepo)
                    }
                    is CreateNovelRepo.Result.RepoAlreadyExists -> {
                        internalEvent.emit(ExtensionRepoEvent.RepoAlreadyExists)
                    }
                }
            } catch (error: Exception) {
                Logger.e(error) { "Failed to add novel repository" }
                internalEvent.emit(ExtensionRepoEvent.AddFailed)
            } finally {
                mutableIsAdding.value = false
            }
        }
    }

    fun deleteRepo(url: String) {
        screenModelScope.launchIO {
            deleteNovelRepo.await(url)
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val repos: ImmutableList<NovelRepo>,
        ) : State
    }
}

sealed class NovelRepoEvent {
    data object InvalidIndex : ExtensionRepoEvent.LocalizedMessage(MR.strings.novel_repo_invalid)
    data object EmptyRepo : ExtensionRepoEvent.LocalizedMessage(MR.strings.novel_repo_empty)
}
