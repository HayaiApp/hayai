package yokai.presentation.extension.repo

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import co.touchlab.kermit.Logger
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.launchIO
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
import yokai.domain.extension.repo.interactor.CreateExtensionRepo
import yokai.domain.extension.repo.interactor.DeleteExtensionRepo
import yokai.domain.extension.repo.interactor.GetExtensionRepo
import yokai.domain.extension.repo.interactor.ReplaceExtensionRepo
import yokai.domain.extension.repo.interactor.UpdateExtensionRepo
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.i18n.MR

class ExtensionRepoScreenModel : StateScreenModel<ExtensionRepoScreenModel.State>(State.Loading), KoinComponent {

    private val extensionManager: ExtensionManager by inject()

    private val getExtensionRepo: GetExtensionRepo by inject()
    private val createExtensionRepo: CreateExtensionRepo by inject()
    private val deleteExtensionRepo: DeleteExtensionRepo by inject()
    private val replaceExtensionRepo: ReplaceExtensionRepo by inject()
    private val updateExtensionRepo: UpdateExtensionRepo by inject()

    private val internalEvent = MutableSharedFlow<ExtensionRepoEvent>()
    val event: SharedFlow<ExtensionRepoEvent> = internalEvent.asSharedFlow()
    private val mutableIsAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = mutableIsAdding.asStateFlow()
    private val mutableIsRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = mutableIsRefreshing.asStateFlow()

    init {
        screenModelScope.launchIO {
            getExtensionRepo.subscribeAll().collectLatest { repos ->
                mutableState.update { State.Success(repos = repos.toImmutableList()) }
                extensionManager.refreshTrust()
            }
        }
    }

    fun addRepo(url: String) {
        if (!mutableIsAdding.compareAndSet(expect = false, update = true)) return
        screenModelScope.launchIO {
            try {
                when (val result = createExtensionRepo.await(url.trim())) {
                    is CreateExtensionRepo.Result.Success -> {
                        internalEvent.emit(ExtensionRepoEvent.Success)
                        extensionManager.findAvailableExtensions()
                    }
                    is CreateExtensionRepo.Result.Error -> internalEvent.emit(ExtensionRepoEvent.AddFailed)
                    is CreateExtensionRepo.Result.InvalidUrl -> internalEvent.emit(ExtensionRepoEvent.InvalidUrl)
                    is CreateExtensionRepo.Result.RepoAlreadyExists -> internalEvent.emit(ExtensionRepoEvent.RepoAlreadyExists)
                    is CreateExtensionRepo.Result.DuplicateFingerprint -> {
                        internalEvent.emit(ExtensionRepoEvent.ShowDialog(RepoDialog.Conflict(result.oldRepo, result.newRepo)))
                    }
                }
            } catch (error: Exception) {
                Logger.e(error) { "Failed to add extension repository" }
                internalEvent.emit(ExtensionRepoEvent.AddFailed)
            } finally {
                mutableIsAdding.value = false
            }
        }
    }

    fun replaceRepo(newRepo: ExtensionRepo) {
        screenModelScope.launchIO {
            replaceExtensionRepo.await(newRepo)
        }
    }

    fun refreshRepos() {
        val status = state.value

        if (status is State.Success && mutableIsRefreshing.compareAndSet(expect = false, update = true)) {
            screenModelScope.launchIO {
                try {
                    updateExtensionRepo.awaitAll()
                } catch (error: Exception) {
                    Logger.e(error) { "Failed to refresh extension repositories" }
                    internalEvent.emit(ExtensionRepoEvent.RefreshFailed)
                } finally {
                    mutableIsRefreshing.value = false
                }
            }
        }
    }

    fun deleteRepo(url: String) {
        screenModelScope.launchIO {
            deleteExtensionRepo.await(url)
            extensionManager.findAvailableExtensions()
        }
    }

    sealed interface State {

        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val repos: ImmutableList<ExtensionRepo>,
        ) : State {

            val isEmpty: Boolean
                get() = repos.isEmpty()
        }
    }
}

sealed class RepoDialog {
    data class Conflict(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : RepoDialog()
}

sealed class ExtensionRepoEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : ExtensionRepoEvent()
    data object InvalidUrl : LocalizedMessage(MR.strings.invalid_repo_url)
    data object RepoAlreadyExists : LocalizedMessage(MR.strings.repo_already_exists)
    data object AddFailed : LocalizedMessage(MR.strings.repo_add_failed)
    data object RefreshFailed : LocalizedMessage(MR.strings.repo_refresh_failed)
    data class ShowDialog(val dialog: RepoDialog) : ExtensionRepoEvent()
    data object NoOp : ExtensionRepoEvent()
    data object Success : ExtensionRepoEvent()
}
