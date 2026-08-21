package dev.ahmedmohamed.hayai.source.enhanced

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.lang.reflect.Proxy

open class EnhancedTestHttpSource(
    override val id: Long,
    override val name: String,
    override val baseUrl: String,
) : HttpSource() {
    override val lang: String = "all"
    override val supportsLatest: Boolean = true

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getMangaDetails(manga: SManga): SManga = manga.apply {
        if (title.isBlank()) title = "Gallery"
        initialized = true
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(getMangaDetails(manga), chapters)

    override fun getMangaUrl(manga: SManga): String = baseUrl.trimEnd('/') + manga.url
}

class EnhancedConfigurableTestSource(
    id: Long,
    name: String,
    baseUrl: String,
) : EnhancedTestHttpSource(id, name, baseUrl), ConfigurableSource {
    val preferences: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Set::class.java -> emptySet<String>()
            else -> null
        }
    } as SharedPreferences

    override fun getSourcePreferences(): SharedPreferences = preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit
}
