package dev.ahmedmohamed.hayai.novel.repository

import androidx.annotation.StringRes
import androidx.preference.PreferenceScreen
import com.bluelinelabs.conductor.Controller
import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import dev.ahmedmohamed.hayai.extension.managed.ui.NovelPluginRepositoryController
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.summaryRes
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.ui.source.browse.repos.RepoController
import eu.kanade.tachiyomi.util.view.withFadeTransaction

internal enum class NovelRepositoryKind(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    ApkExtensions(
        titleRes = R.string.hayai_novel_extension_repos,
        summaryRes = R.string.hayai_novel_extension_repository_warning,
    ),
    LnReaderPlugins(
        titleRes = R.string.hayai_novel_plugins,
        summaryRes = R.string.hayai_novel_plugins_summary,
    ),
    ;

    fun destination(): Controller = when (this) {
        ApkExtensions -> RepoController(ContentKind.Novel)
        LnReaderPlugins -> NovelPluginRepositoryController()
    }
}

class NovelRepositoryHubController : SettingsController() {
    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_repository_management

        NovelRepositoryKind.entries.forEach { kind ->
            preference {
                titleRes = kind.titleRes
                summaryRes = kind.summaryRes
                onClick { router.pushController(kind.destination().withFadeTransaction()) }
            }
        }
    }
}
