package dev.ahmedmohamed.hayai.novel.plugin.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.plugin.InstalledNovelPlugin
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginCatalog
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginDescriptor
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelPluginManagerController : SettingsController() {
    private val manager: NovelPluginManager = Injekt.get()
    private lateinit var status: Preference
    private lateinit var repositories: PreferenceCategory
    private lateinit var plugins: PreferenceCategory
    private var query = ""
    private var latest = NovelPluginCatalog()
    private var actionInProgress: String? = null
    private var collectionJob: Job? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_plugins
        preferenceCategory {
            status = preference {
                summary = context.getString(R.string.hayai_novel_plugins_security_summary)
                isSelectable = false
            }
            preference {
                title = context.getString(R.string.refresh)
                onClick { runAction(context.getString(R.string.refresh)) { manager.refresh() } }
            }
            preference {
                title = context.getString(R.string.add)
                summary = context.getString(R.string.hayai_add_novel_repository)
                onClick { showAddRepository() }
            }
            preference {
                title = context.getString(R.string.search)
                onClick { editQuery() }
            }
        }
        repositories = preferenceCategory { title = context.getString(R.string.hayai_novel_repositories) }
        plugins = preferenceCategory { title = context.getString(R.string.hayai_novel_plugins) }
        collectionJob = viewScope.launch {
            manager.catalog.collect {
                latest = it
                render()
            }
        }
        runAction(context.getString(R.string.refresh)) { manager.refresh() }
    }

    override fun onDestroy() {
        collectionJob?.cancel()
        super.onDestroy()
    }

    private fun render() {
        val context = activity ?: return
        status.summary = buildString {
            append(context.getString(R.string.hayai_novel_plugins_security_summary))
            latest.repositoryErrors.forEach { (url, error) ->
                appendLine()
                append(context.getString(R.string.hayai_repository_error, url, context.novelFailureMessage(error, R.string.unknown_error)))
            }
            actionInProgress?.let {
                appendLine()
                append(it)
            }
        }
        repositories.removeAll()
        plugins.removeAll()
        if (latest.repositories.isEmpty()) {
            repositories.preference {
                summary = context.getString(R.string.hayai_no_novel_repositories)
                isSelectable = false
            }
        }
        latest.repositories.forEach(::addRepositoryPreferences)

        val installedById = latest.installed.associateBy { it.descriptor.id }
        val availableById = latest.available.associateBy(NovelPluginDescriptor::id)
        val descriptors = (availableById.keys + installedById.keys)
            .mapNotNull { availableById[it] ?: installedById[it]?.descriptor }
            .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) || it.lang.contains(query, true) }
            .sortedWith(compareBy(NovelPluginDescriptor::normalizedLanguage, NovelPluginDescriptor::name))
        if (descriptors.isEmpty()) {
            plugins.preference {
                summary = context.getString(R.string.hayai_no_novel_plugins)
                isSelectable = false
            }
        }
        descriptors.forEach { descriptor -> addPluginPreference(descriptor, installedById[descriptor.id]) }
    }

    private fun addRepositoryPreferences(repository: NovelPluginRepository) {
        val context = requireNotNull(activity)
        repositories.switchPreference {
            key = "hayai.plugin.repository.${repository.url.hashCode()}"
            title = repository.name
            summary = repository.url
            isChecked = repository.enabled
            isPersistent = false
            isEnabled = actionInProgress == null
            onChange { enabled ->
                runAction(repository.name) {
                    manager.setRepositoryEnabled(repository.url, enabled as Boolean)
                    manager.refresh()
                }
                false
            }
        }
        repositories.preference {
            title = context.getString(R.string.hayai_remove_named_item, repository.name)
            summary = context.getString(R.string.remove)
            isEnabled = actionInProgress == null
            onClick { confirmRepositoryRemoval(repository) }
        }
    }

    private fun addPluginPreference(descriptor: NovelPluginDescriptor, installed: InstalledNovelPlugin?) {
        val context = requireNotNull(activity)
        val update = installed != null && manager.hasUpdate(descriptor.id)
        plugins.preference {
            title = descriptor.name
            summary = buildString {
                append(descriptor.lang).append(" · ").append(descriptor.id)
                appendLine()
                append(context.getString(R.string.version)).append(' ').append(descriptor.version)
                if (installed != null) append(" · ").append(context.getString(R.string.installed))
                appendLine()
                append(
                    context.getString(
                        when {
                            installed == null -> R.string.install
                            update -> R.string.update
                            else -> R.string.uninstall
                        },
                    ),
                )
            }
            isEnabled = actionInProgress == null
            onClick {
                when {
                    installed == null || update -> confirmPluginInstall(descriptor)
                    else -> runAction(descriptor.name) { manager.uninstall(descriptor.id) }
                }
            }
        }
    }

    private fun editQuery() {
        val context = activity ?: return
        val input = EditText(context).apply {
            setText(query)
            setSelection(text.length)
        }
        context.materialAlertDialog()
            .setTitle(R.string.search)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                query = input.text.toString()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRepositoryRemoval(repository: NovelPluginRepository) {
        val context = activity ?: return
        context.materialAlertDialog()
            .setTitle(R.string.remove)
            .setMessage(context.getString(R.string.hayai_remove_repository_confirmation, repository.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                runAction(repository.name) {
                    manager.removeRepository(repository.url)
                    manager.refresh()
                }
            }
            .show()
    }

    private fun confirmPluginInstall(descriptor: NovelPluginDescriptor) {
        val context = activity ?: return
        context.materialAlertDialog()
            .setTitle(descriptor.name)
            .setMessage(R.string.hayai_novel_plugin_install_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.install) { _, _ -> runAction(descriptor.name) { manager.install(descriptor.id) } }
            .show()
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
                if (actionInProgress != null) return@setOnClickListener
                val label = name.text.toString().trim()
                actionInProgress = label.ifBlank { context.getString(R.string.hayai_add_novel_repository) }
                render()
                viewScope.launch {
                    runCatching {
                        manager.addRepository(label, url.text.toString().trim())
                        manager.refresh()
                    }.onSuccess {
                        dialog.dismiss()
                    }.onFailure {
                        url.error = context.novelFailureMessage(it, R.string.unknown_error)
                    }
                    actionInProgress = null
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun runAction(label: String, action: suspend () -> Any?) {
        if (actionInProgress != null) return
        val context = activity ?: return
        actionInProgress = label
        render()
        viewScope.launch {
            runCatching { action() }
                .onFailure { context.toast(context.novelFailureMessage(it, R.string.unknown_error)) }
            actionInProgress = null
            render()
        }
    }
}
