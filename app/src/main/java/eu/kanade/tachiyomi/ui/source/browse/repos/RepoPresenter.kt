package eu.kanade.tachiyomi.ui.source.browse.repos

import dev.ahmedmohamed.hayai.novel.extension.NovelApkRepositoryRegistry
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Presenter of [RepoController]. Used to manage the repos for the extensions.
 */
class RepoPresenter(
    private val controller: RepoController,
    private val preferences: PreferencesHelper = Injekt.get(),
    private val novelRepositories: NovelApkRepositoryRegistry = Injekt.get(),
) : BaseCoroutinePresenter<RepoController>() {
    private var scope = CoroutineScope(Job() + Dispatchers.Default)

    /**
     * List containing repos, keyed by their literal index URL (whatever the user entered or a
     * repo.json pointer resolved to) - no filename or path pattern is assumed.
     */
    private val repos: Set<String>
        get() = preferences.extensionRepos().get().sorted().toSet()

    private suspend fun addRepo(url: String) {
        novelRepositories.addGlobal(url)
    }

    private suspend fun removeRepo(url: String) {
        novelRepositories.removeGlobal(url)
    }

    /**
     * Called when the presenter is created.
     */
    fun getRepos() {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                controller.updateRepos()
            }
        }
    }

    /**
     * Re-fetches each of [reposToRefresh] so its cached display metadata (name/website/
     * Discord) is current. Metadata is only ever populated as a side effect of actually
     * fetching a repo's index, so a newly added/renamed repo would otherwise sit with no
     * metadata until some unrelated extension sync happened to run - see
     * [createOrRenameRepo]. Also self-heals a repo still stored in the old bare-base-URL
     * format. Not run on every screen open: any repo already added gets the same treatment
     * as a side effect of the normal extension-list sync, same as mihon relies on its own
     * background refresh rather than eagerly refetching every store when this screen opens.
     *
     * Doesn't touch the UI itself - callers decide when to render, since [createOrRenameRepo]
     * wants this done before its row's loading spinner stops, not as a second visible update
     * after it already showed the bare URL.
     */
    private suspend fun refreshRepoMetadata(reposToRefresh: Set<String>) {
        for (repo in reposToRefresh) {
            try {
                val resolvedUrl = ExtensionApi().validateRepo(repo)
                if (resolvedUrl != repo) {
                    novelRepositories.replaceGlobal(repo, resolvedUrl)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Repo is unreachable right now; leave it as-is and try again next visit.
            }
        }
    }

    fun getReposWithCreate(): List<RepoItem> {
        val metadataByUrl = preferences.extensionRepoMetadata().get()
        return (listOf(CREATE_REPO_ITEM) + repos).map { repo ->
            RepoItem(repo, if (repo == CREATE_REPO_ITEM) null else metadataByUrl[repo])
        }
    }

    fun getRepoUrl(repo: String): String {
        val website = preferences.extensionRepoMetadata().get()[repo]?.website
        if (!website.isNullOrBlank()) return website

        return githubRepoRegex
            .find(repo)
            ?.let {
                val (user, repoName) = it.destructured
                "https://github.com/$user/$repoName"
            } ?: repo
    }

    fun getDiscordUrl(repo: String): String? = preferences.extensionRepoMetadata().get()[repo]?.discordUrl

    /**
     * Returns true if the URL is at least shaped like a URL. Deliberately doesn't require any
     * particular filename or path pattern - a repo's index can be served from anywhere, and
     * the real validation happens in [createOrRenameRepo] by actually fetching it. This is
     * just a fast, offline check to catch obvious typos before bothering the network.
     */
    fun isValidRepoFormat(name: String): Boolean = name.matches(repoRegex)

    /**
     * Validates a repo over the network and, if reachable, adds it (or renames [oldRepo] to
     * it). Mirrors mihon's add flow: the repo is only saved once the fetch succeeds, so a
     * bad URL never ends up in the list.
     *
     * @param oldRepo The repo being renamed, or null when creating a new one.
     * @param newName The new repo URL.
     * @param onResult Called on the main thread with whether the repo was saved.
     */
    fun createOrRenameRepo(
        oldRepo: String?,
        newName: String,
        onResult: (success: Boolean) -> Unit,
    ) {
        if (oldRepo != null && oldRepo.equals(newName, true)) {
            onResult(true)
            return
        }

        if (repoExists(newName)) {
            controller.onRepoExistsError()
            onResult(false)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val resolvedUrl = ExtensionApi().validateRepo(newName)
                oldRepo?.let {
                    removeRepo(it)
                    removeMetadataIfUnreferenced(it)
                }
                addRepo(resolvedUrl)
                // validateRepo() above already fetched and saved metadata for resolvedUrl, but
                // re-fetching it fresh (mirroring what getRepos() does) is what reliably
                // surfaces it. Done before onResult so the row keeps its loading spinner
                // through this too, instead of showing the bare URL first and updating again
                // once metadata lands.
                refreshRepoMetadata(setOf(resolvedUrl))
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    controller.onRepoUnreachableError()
                    onResult(false)
                }
            }
        }
    }

    /**
     * Deletes the repo from the database.
     *
     * @param repo The repo to delete.
     */
    fun deleteRepo(repo: String?) {
        val safeRepo = repo ?: return
        scope.launch(Dispatchers.IO) {
            removeRepo(safeRepo)
            removeMetadataIfUnreferenced(safeRepo)
            withContext(Dispatchers.Main) { controller.updateRepos() }
        }
    }

    private fun removeMetadataIfUnreferenced(repo: String) {
        if (preferences.extensionRepos().get().none { it.equals(repo, true) }) {
            preferences.extensionRepoMetadata().set(preferences.extensionRepoMetadata().get() - repo)
        }
    }

    /**
     * Returns true if a repo with the given name already exists.
     */
    private fun repoExists(name: String): Boolean = repos.any { it.equals(name, true) }

    companion object {
        private val repoRegex = """^https://\S+$""".toRegex()
        private val githubRepoRegex = """https://(?:raw.githubusercontent.com|github.com)/(.+?)/(.+?)/.+""".toRegex()
        const val CREATE_REPO_ITEM = "create_repo"
    }
}
