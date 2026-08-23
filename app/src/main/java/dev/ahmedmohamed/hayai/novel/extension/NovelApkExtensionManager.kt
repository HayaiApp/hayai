package dev.ahmedmohamed.hayai.novel.extension

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper

interface NovelApkRepositoryRegistry {
    suspend fun repositories(): Set<String>
    fun repositoriesNow(): Set<String>
    fun novelOnlyRepositoriesNow(): Set<String>
    suspend fun addGlobal(indexUrl: String)
    suspend fun removeGlobal(indexUrl: String)
    suspend fun replaceGlobal(oldUrl: String, newUrl: String)
    suspend fun add(indexUrl: String)
    suspend fun remove(indexUrl: String)
    fun migrate(
        oldUrl: String,
        newUrl: String,
    )
}

class J2kNovelApkRepositoryRegistry(context: Context, private val preferences: PreferencesHelper) : NovelApkRepositoryRegistry {
    private val tags = context.getSharedPreferences("hayai_novel_apk_repositories", Context.MODE_PRIVATE)
    override suspend fun repositories(): Set<String> = repositoriesNow()
    override fun repositoriesNow(): Set<String> = tags.getStringSet(KEY, emptySet()).orEmpty().toSet()
    override fun novelOnlyRepositoriesNow(): Set<String> = tags.getStringSet(OWNED_KEY, emptySet()).orEmpty().toSet()

    override suspend fun addGlobal(indexUrl: String) {
        val global = preferences.extensionRepos().get()
        val novelOnly = novelOnlyRepositoriesNow()
        if (indexUrl in novelOnly) {
            check(tags.edit().putStringSet(OWNED_KEY, novelOnly - indexUrl).commit())
        }
        if (indexUrl !in global) preferences.extensionRepos().set(global + indexUrl)
    }

    override suspend fun removeGlobal(indexUrl: String) {
        preferences.extensionRepos().set(preferences.extensionRepos().get() - indexUrl)
        check(
            tags.edit()
                .putStringSet(KEY, repositoriesNow() - indexUrl)
                .putStringSet(OWNED_KEY, novelOnlyRepositoriesNow() - indexUrl)
                .commit(),
        )
    }

    override suspend fun replaceGlobal(oldUrl: String, newUrl: String) {
        if (oldUrl == newUrl) return
        preferences.extensionRepos().set(preferences.extensionRepos().get() - oldUrl + newUrl)
        migrate(oldUrl, newUrl)
    }

    override suspend fun add(indexUrl: String) {
        val global = preferences.extensionRepos().get()
        val owned = novelOnlyRepositoriesNow()
        check(
            tags.edit()
                .putStringSet(KEY, repositories() + indexUrl)
                .putStringSet(OWNED_KEY, if (indexUrl in global) owned else owned + indexUrl)
                .commit(),
        )
        if (indexUrl !in global) preferences.extensionRepos().set(global + indexUrl)
    }
    override suspend fun remove(indexUrl: String) {
        val owned = novelOnlyRepositoriesNow()
        if (indexUrl in owned) preferences.extensionRepos().set(preferences.extensionRepos().get() - indexUrl)
        check(
            tags.edit()
                .putStringSet(KEY, repositories() - indexUrl)
                .putStringSet(OWNED_KEY, owned - indexUrl)
                .commit(),
        )
    }

    override fun migrate(
        oldUrl: String,
        newUrl: String,
    ) {
        if (oldUrl == newUrl || oldUrl !in repositoriesNow()) return
        val owned = novelOnlyRepositoriesNow()
        check(
            tags.edit()
                .putStringSet(KEY, repositoriesNow() - oldUrl + newUrl)
                .putStringSet(OWNED_KEY, if (oldUrl in owned) owned - oldUrl + newUrl else owned)
                .commit(),
        )
    }

    private companion object {
        const val KEY = "urls_v1"
        const val OWNED_KEY = "owned_global_urls_v1"
    }
}
