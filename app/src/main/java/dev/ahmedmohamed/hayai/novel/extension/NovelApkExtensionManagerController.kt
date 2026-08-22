package dev.ahmedmohamed.hayai.novel.extension

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.ui.source.browse.repos.RepoController
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelApkExtensionManagerController : SettingsController() {
    private val manager: NovelApkExtensionManager = Injekt.get()
    private lateinit var status: Preference
    private lateinit var cancelInstall: Preference
    private lateinit var updates: PreferenceCategory
    private lateinit var installed: PreferenceCategory
    private lateinit var available: PreferenceCategory
    private lateinit var untrusted: PreferenceCategory
    private var installJob: Job? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_extensions_title
        preferenceCategory {
            status = preference { isSelectable = false }
            preference {
                title = context.getString(R.string.refresh)
                onClick { launchAction { manager.refresh() } }
            }
            preference {
                title = context.getString(R.string.hayai_novel_extension_repos)
                onClick { router.pushController(RepoController(ContentKind.Novel).withFadeTransaction()) }
            }
            cancelInstall = preference {
                title = context.getString(R.string.hayai_cancel_installation)
                isEnabled = false
                onClick { installJob?.cancel(CancellationException()) }
            }
        }
        updates = preferenceCategory { title = context.getString(R.string.updates) }
        installed = preferenceCategory { title = context.getString(R.string.installed) }
        available = preferenceCategory { title = context.getString(R.string.hayai_available) }
        untrusted = preferenceCategory { title = context.getString(R.string.untrusted) }

        viewScope.launch { manager.catalog.collect(::render) }
        launchAction { manager.refresh() }
    }

    private fun render(state: NovelApkExtensionCatalog) {
        val context = activity ?: return
        status.summary = state.error?.let { context.novelFailureMessage(it, R.string.hayai_unknown_installer_error) }
            ?: if (state.refreshing) {
                context.getString(R.string.hayai_refreshing)
            } else {
                context.getString(R.string.hayai_novel_extension_catalog_status, state.installed.size, state.updates.size)
            }
        updates.removeAll()
        installed.removeAll()
        available.removeAll()
        untrusted.removeAll()
        state.updates.forEach { extension -> updates.extensionPreference(extension, R.string.update) { install(extension) } }
        state.installed.forEach { extension -> installed.extensionPreference(extension, R.string.remove) { confirmRemove(extension) } }
        state.available.forEach { extension -> available.extensionPreference(extension, R.string.install) { install(extension) } }
        state.untrusted.forEach { extension ->
            untrusted.extensionPreference(extension, R.string.hayai_trust_signature) { confirmTrust(extension) }
        }
        updates.isVisible = state.updates.isNotEmpty()
        installed.isVisible = state.installed.isNotEmpty()
        available.isVisible = state.available.isNotEmpty()
        untrusted.isVisible = state.untrusted.isNotEmpty()
    }

    private fun PreferenceCategory.extensionPreference(extension: Extension, action: Int, click: () -> Unit) {
        preference {
            title = extension.name
            summary = context.getString(action)
            onClick(click)
        }
    }

    private fun install(extension: Extension.Available) {
        if (installJob?.isActive == true) return
        val context = activity ?: return
        installJob = viewScope.launch {
            cancelInstall.isEnabled = true
            try {
                var terminal = false
                manager.install(extension, this).collect { info ->
                    terminal = info.first.isCompleted()
                    status.summary = when (info.first) {
                        InstallStep.Error -> context.getString(R.string.hayai_extension_install_failed, extension.name)
                        InstallStep.Installed, InstallStep.Done -> context.getString(R.string.hayai_extension_install_complete, extension.name)
                        else -> context.getString(R.string.hayai_extension_install_status, extension.name, installStepText(info.first))
                    }
                }
                if (!terminal) status.summary = context.getString(R.string.hayai_extension_install_stopped, extension.name)
            } catch (_: CancellationException) {
                status.summary = context.getString(R.string.hayai_extension_install_canceled, extension.name)
            } catch (error: Throwable) {
                status.summary = context.getString(
                    R.string.hayai_extension_install_error,
                    extension.name,
                    context.novelFailureMessage(error, R.string.hayai_unknown_installer_error),
                )
            } finally {
                cancelInstall.isEnabled = false
            }
        }
    }

    private fun confirmTrust(extension: Extension.Untrusted) {
        val context = activity ?: return
        context.materialAlertDialog()
            .setTitle(R.string.hayai_trust_extension_signature_title)
            .setMessage(extension.signatureHash)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.trust) { _, _ -> manager.trust(extension) }
            .show()
    }

    private fun confirmRemove(extension: Extension.Installed) {
        val context = activity ?: return
        context.materialAlertDialog()
            .setTitle(context.getString(R.string.hayai_remove_named_item, extension.name))
            .setMessage(R.string.hayai_remove_extension_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> manager.remove(extension) }
            .show()
    }

    private fun launchAction(block: suspend () -> Unit) {
        val context = activity ?: return
        viewScope.launch {
            runCatching { block() }
                .onFailure { status.summary = context.novelFailureMessage(it, R.string.hayai_unknown_installer_error) }
        }
    }

    private fun installStepText(step: InstallStep): String = requireNotNull(resources).getString(
        when (step) {
            InstallStep.Pending -> R.string.pending
            InstallStep.Downloading -> R.string.downloading
            InstallStep.Loading -> R.string.hayai_extension_install_loading
            InstallStep.Installing -> R.string.installing
            InstallStep.Installed, InstallStep.Done -> R.string.installed
            InstallStep.Error -> R.string.hayai_extension_install_failed_step
        },
    )
}
