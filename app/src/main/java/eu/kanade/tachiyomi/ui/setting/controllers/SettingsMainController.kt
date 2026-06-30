package eu.kanade.tachiyomi.ui.setting.controllers

import android.app.ActivityManager
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.content.getSystemService
import androidx.preference.PreferenceScreen
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.more.AboutController
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.controllers.legacy.SettingsDataLegacyController
import eu.kanade.tachiyomi.ui.setting.controllers.search.SettingsSearchController
import exh.source.ExhPreferences
import yokai.util.koin.injectLazy
import eu.kanade.tachiyomi.ui.setting.iconRes
import eu.kanade.tachiyomi.ui.setting.iconTint
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.onLongClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceLongClickable
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.fadeTransactionHandler
import eu.kanade.tachiyomi.util.view.openInBrowser
import eu.kanade.tachiyomi.util.view.searchToolbar
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.i18n.MR
import yokai.util.lang.getString
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

class SettingsMainController : SettingsLegacyController(), FloatingSearchInterface {

    private val exhPreferences: ExhPreferences by injectLazy()

    init {
        setHasOptionsMenu(true)
    }

    // Settings hierarchy follows plans/partitioned-foraging-karp.md Phase F.5
    // (issue #13): General, Appearance, Library, Reader (manga+novel merged),
    // Browse & Sources, Storage & Downloads (downloads+data-saver merged),
    // Tracking, Security, Advanced (Compose-only), About.
    //
    // TODO(F.5/issue #13): Merge Browse & Sources entry-points — currently
    // Browse is its own screen and source-specific prefs scatter through
    // ExtensionManager/individual sources. Consolidate into one Settings
    // hub when a per-source prefs registry exists. See SettingsBrowseController
    // and the source prefs read by `SourceFilter*` for the existing surface area.
    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = MR.strings.settings

        val tintColor = context.getResourceColor(materialR.attr.colorSecondary)

        preference {
            iconRes = R.drawable.ic_tune_24dp
            iconTint = tintColor
            titleRes = MR.strings.general
            onClick { navigateTo(SettingsGeneralController()) }
        }
        preference {
            iconRes = R.drawable.ic_appearance_outline_24dp
            iconTint = tintColor
            titleRes = MR.strings.appearance
            onClick { navigateTo(SettingsAppearanceController()) }
        }
        preference {
            iconRes = R.drawable.ic_library_outline_24dp
            iconTint = tintColor
            titleRes = MR.strings.library
            onClick { navigateTo(SettingsLibraryController()) }
        }
        preference {
            iconRes = R.drawable.ic_read_outline_24dp
            iconTint = tintColor
            titleRes = MR.strings.reader
            onClick { navigateTo(SettingsReaderHubController()) }
        }
        preference {
            iconRes = R.drawable.ic_translate_24dp
            iconTint = tintColor
            titleRes = MR.strings.pref_category_translation
            onClick { navigateTo(SettingsTranslationController()) }
        }
        preference {
            iconRes = R.drawable.ic_history_24dp
            iconTint = tintColor
            titleRes = MR.strings.recents
            onClick { navigateTo(SettingsRecentsController()) }
        }
        preference {
            iconRes = R.drawable.ic_browse_outline_24dp
            iconTint = tintColor
            titleRes = MR.strings.browse
            onClick { navigateTo(SettingsBrowseController()) }
        }
        if (exhPreferences.isHentaiEnabled.get()) {
            preference {
                iconRes = R.drawable.ic_eh_logo_24dp
                iconTint = tintColor
                titleRes = MR.strings.pref_category_eh
                onClick { navigateTo(SettingsEhController()) }
            }
        }
        // Storage & Downloads — single entry; SettingsDownloadController hosts
        // download settings and links to the Data Saver Compose screen.
        preference {
            iconRes = R.drawable.ic_file_download_24dp
            iconTint = tintColor
            titleRes = MR.strings.downloads
            onClick { navigateTo(SettingsDownloadController()) }
        }
        preference {
            iconRes = R.drawable.ic_sync_24dp
            iconTint = tintColor
            titleRes = MR.strings.tracking
            onClick { navigateTo(SettingsTrackingController()) }
        }
        // Data & Storage (backup/restore/cache) — kept as its own top-level
        // entry because it covers app-data ops, not download-cache cleanup.
        preferenceLongClickable {
            iconRes = R.drawable.ic_storage_24dp
            iconTint = tintColor
            titleRes = MR.strings.data_and_storage
            onClick { navigateTo(SettingsDataController()) }
            onLongClick {
                navigateTo(SettingsDataLegacyController())
                context.toast("You're entering legacy version of 'Data and storage'")
            }
        }
        preference {
            iconRes = R.drawable.ic_security_24dp
            iconTint = tintColor
            titleRes = MR.strings.security
            onClick { navigateTo(SettingsSecurityController()) }
        }
        // Advanced — Compose-only. Legacy fallback removed per Phase F.5; the
        // SettingsAdvancedLegacyController source file is retained for now to
        // avoid breaking deep links / external references, but it is no longer
        // reachable from the main settings tree.
        preference {
            iconRes = R.drawable.ic_code_24dp
            iconTint = tintColor
            titleRes = MR.strings.advanced
            onClick { navigateTo(SettingsAdvancedController()) }
        }
        preference {
            iconRes = R.drawable.ic_info_outline_24dp
            iconTint = tintColor
            titleRes = MR.strings.about
            onClick { navigateTo(AboutController()) }
        }
        this
    }
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.settings_main, menu)
        // Change hint to show global search.
        searchToolbar()?.searchQueryHint = applicationContext?.getString(MR.strings.search_settings)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_help -> openInBrowser(URL_HELP)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActionViewExpand(item: MenuItem?) {
        val isLowRam = activity?.getSystemService<ActivityManager>()?.isLowRamDevice == true
        router.pushController(
            RouterTransaction.with(SettingsSearchController())
                .pushChangeHandler(SimpleSwapChangeHandler(removesFromViewOnPush = isLowRam))
                .popChangeHandler(fadeTransactionHandler()),
        )
    }

    private fun navigateTo(controller: Controller) {
        router.pushController(controller.withFadeTransaction())
    }

    private companion object {
        private const val URL_HELP = "https://mihon.app/docs/guides/troubleshooting/"
    }
}
