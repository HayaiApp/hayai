package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import yokai.i18n.MR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appPrefs: Boolean = true,
    val sourcePrefs: Boolean = true,
    val extensionRepos: Boolean = true,
) {
    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appPrefs,
        sourcePrefs,
        extensionRepos,
    )

    fun canRestore() = libraryEntries || categories || appPrefs || sourcePrefs || extensionRepos

    companion object {
        fun getEntries() = persistentListOf(
            Entry(
                label = MR.strings.library_entries,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appPrefs,
                setter = { options, enabled -> options.copy(appPrefs = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = RestoreOptions::sourcePrefs,
                setter = { options, enabled -> options.copy(sourcePrefs = enabled) },
            ),
            Entry(
                label = MR.strings.source_repos,
                getter = RestoreOptions::extensionRepos,
                setter = { options, enabled -> options.copy(extensionRepos = enabled) },
            ),
        )

        fun fromBooleanArray(array: BooleanArray): RestoreOptions = RestoreOptions(
            libraryEntries = array[0],
            categories = array[1],
            appPrefs = array[2],
            sourcePrefs = array[3],
            extensionRepos = array.getOrElse(4) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
        val enabled: (RestoreOptions) -> Boolean = { true },
    )
}
