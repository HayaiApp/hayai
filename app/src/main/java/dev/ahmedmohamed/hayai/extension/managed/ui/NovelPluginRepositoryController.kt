package dev.ahmedmohamed.hayai.extension.managed.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginRepository
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelPluginRepositoryController : SettingsController() {
    private val manager: NovelPluginManager = Injekt.get()
    private lateinit var repositories: PreferenceCategory
    private var collectionJob: Job? = null
    private var busy = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_plugins
        preferenceCategory {
            preference {
                title = context.getString(R.string.refresh)
                summary = context.getString(R.string.hayai_novel_plugins_security_summary)
                onClick { runAction { manager.refresh() } }
            }
            preference {
                title = context.getString(R.string.add)
                summary = context.getString(R.string.hayai_add_novel_repository)
                onClick { showAddRepository() }
            }
        }
        repositories = preferenceCategory { title = context.getString(R.string.hayai_novel_repositories) }
        collectionJob = viewScope.launch {
            manager.catalog.collectLatest { catalog ->
                repositories.removeAll()
                if (catalog.repositories.isEmpty()) {
                    repositories.preference {
                        summary = context.getString(R.string.hayai_no_novel_repositories)
                        isSelectable = false
                    }
                }
                catalog.repositories.forEach(::addRepository)
                catalog.repositoryErrors.values.firstOrNull()?.let { context.toast(context.novelFailureMessage(it, R.string.unknown_error)) }
            }
        }
        runAction { manager.refresh() }
    }

    override fun onDestroy() {
        collectionJob?.cancel()
        super.onDestroy()
    }

    private fun addRepository(repository: NovelPluginRepository) {
        repositories.switchPreference {
            key = "hayai.plugin.repository.${repository.url.hashCode()}"
            title = repository.name
            summary = repository.url
            isChecked = repository.enabled
            isPersistent = false
            isEnabled = !busy
            onChange { enabled ->
                runAction {
                    manager.setRepositoryEnabled(repository.url, enabled as Boolean)
                    manager.refresh()
                }
                false
            }
        }
        repositories.preference {
            title = requireNotNull(activity).getString(R.string.hayai_remove_named_item, repository.name)
            summary = requireNotNull(activity).getString(R.string.remove)
            onClick {
                activity?.materialAlertDialog()
                    ?.setTitle(R.string.remove)
                    ?.setMessage(activity?.getString(R.string.hayai_remove_repository_confirmation, repository.name))
                    ?.setNegativeButton(android.R.string.cancel, null)
                    ?.setPositiveButton(R.string.remove) { _, _ -> runAction { manager.removeRepository(repository.url); manager.refresh() } }
                    ?.show()
            }
        }
    }

    private fun showAddRepository() {
        val context = activity ?: return
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val name = EditText(context).apply { setHint(R.string.name) }
        val url = EditText(context).apply {
            setHint(R.string.hayai_novel_plugin_repository_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(name)
        form.addView(url)
        val dialog = context.materialAlertDialog()
            .setTitle(R.string.hayai_add_novel_repository)
            .setMessage(R.string.hayai_novel_repository_trust_warning)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                viewScope.launch {
                    runCatching { manager.addRepository(name.text.toString(), url.text.toString()); manager.refresh() }
                        .onSuccess { dialog.dismiss() }
                        .onFailure { url.error = context.novelFailureMessage(it, R.string.unknown_error) }
                }
            }
        }
        dialog.show()
    }

    private fun runAction(action: suspend () -> Any?) {
        if (busy) return
        busy = true
        viewScope.launch {
            runCatching { action() }.onFailure { activity?.toast(activity?.novelFailureMessage(it, R.string.unknown_error).orEmpty()) }
            busy = false
        }
    }
}
