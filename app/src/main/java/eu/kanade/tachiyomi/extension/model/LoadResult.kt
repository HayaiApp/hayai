package eu.kanade.tachiyomi.extension.model

import dev.ahmedmohamed.hayai.extension.ApkLoadFailure

sealed interface LoadResult {
    data class Success(
        val extension: Extension.Installed,
    ) : LoadResult

    data class Untrusted(
        val extension: Extension.Untrusted,
    ) : LoadResult

    data class Error(
        val failure: ApkLoadFailure,
    ) : LoadResult
}
