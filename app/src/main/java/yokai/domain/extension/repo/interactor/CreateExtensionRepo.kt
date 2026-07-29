package yokai.domain.extension.repo.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import yokai.util.koin.injectLazy
import yokai.domain.extension.repo.ExtensionRepoRepository
import yokai.domain.extension.repo.exception.SaveExtensionRepoException
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.domain.extension.repo.service.ExtensionRepoService

class CreateExtensionRepo(
    private val extensionRepoRepository: ExtensionRepoRepository
) {
    private val repoRegex = """^https://\S+$""".toRegex()

    private val networkService: NetworkHelper by injectLazy()

    private val client: OkHttpClient
        get() = networkService.client

    private val extensionRepoService = ExtensionRepoService(client)

    suspend fun await(repoUrl: String): Result {
        val trimmedUrl = repoUrl.trim().removeSuffix("/")
        if (!trimmedUrl.matches(repoRegex)) {
            return Result.InvalidUrl
        }

        // Accept either the repo's base URL, or a direct link to one of the files it serves
        // (index.min.json, repo.json, index.pb, ...).
        val lastSegment = trimmedUrl.substringAfterLast('/')
        val baseUrl = if ('.' in lastSegment) {
            trimmedUrl.substringBeforeLast('/')
        } else {
            trimmedUrl
        }

        return extensionRepoService.fetchRepoDetails(baseUrl)?.let { insert(it) } ?: Result.InvalidUrl
    }

    private suspend fun insert(repo: ExtensionRepo): Result {
        return try {
            extensionRepoRepository.insertRepository(
                repo.baseUrl,
                repo.name,
                repo.shortName,
                repo.website,
                repo.signingKeyFingerprint,
            )
            Result.Success
        } catch (e: SaveExtensionRepoException) {
            Logger.e(e) { "SQL Conflict attempting to add new repository ${repo.baseUrl}" }
            return handleInsertionError(repo)
        }
    }

    /**
     * Error Handler for insert when there are trying to create new repositories
     *
     * SaveExtensionRepoException doesn't provide constraint info in exceptions.
     * First check if the conflict was on primary key. if so return RepoAlreadyExists
     * Then check if the conflict was on fingerprint. if so Return DuplicateFingerprint
     * If neither are found, there was some other Error, and return Result.Error
     *
     * @param repo Extension Repo holder for passing to DB/Error Dialog
     */
    @Suppress("ReturnCount")
    private suspend fun handleInsertionError(repo: ExtensionRepo): Result {
        val repoExists = extensionRepoRepository.getRepository(repo.baseUrl)
        if (repoExists != null) {
            return Result.RepoAlreadyExists
        }
        val matchingFingerprintRepo =
            extensionRepoRepository.getRepositoryBySigningKeyFingerprint(repo.signingKeyFingerprint)
        if (matchingFingerprintRepo != null) {
            return Result.DuplicateFingerprint(matchingFingerprintRepo, repo)
        }
        return Result.Error
    }

    sealed interface Result {
        data class DuplicateFingerprint(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : Result
        data object InvalidUrl : Result
        data object RepoAlreadyExists : Result
        data object Success : Result
        data object Error : Result
    }
}
