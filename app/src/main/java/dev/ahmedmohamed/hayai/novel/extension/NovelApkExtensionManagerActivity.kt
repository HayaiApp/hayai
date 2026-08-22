package dev.ahmedmohamed.hayai.novel.extension

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelApkExtensionManagerActivity : AppCompatActivity() {
    private val manager by lazy { Injekt.get<NovelApkExtensionManager>() }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var cancelInstall: Button
    private var installJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hayai_novel_extensions_title)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                status = TextView(context)
                addView(status)
                addView(
                    Button(context).apply {
                        text = getString(R.string.refresh)
                        setOnClickListener { launchAction { manager.refresh() } }
                    },
                )
                addView(
                    Button(context).apply {
                        text = getString(R.string.hayai_add_repository)
                        setOnClickListener { addRepository() }
                    },
                )
                cancelInstall = Button(context).apply {
                    text = getString(R.string.hayai_cancel_installation)
                    isEnabled = false
                    setOnClickListener { installJob?.cancel(CancellationException("Canceled by user")) }
                }
                addView(cancelInstall)
                addView(
                    ScrollView(context).apply {
                        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                        addView(content)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
        lifecycleScope.launch { manager.catalog.collect(::render) }
        launchAction { manager.refresh() }
    }
    private fun render(state: NovelApkExtensionCatalog) {
        status.text =
            state.error?.let { novelFailureMessage(it, R.string.hayai_unknown_installer_error) } ?: if (state.refreshing) {
                getString(R.string.hayai_refreshing)
            } else {
                getString(R.string.hayai_novel_extension_catalog_status, state.installed.size, state.updates.size)
            }
        content.removeAllViews()
        state.updates.forEach { extension -> row(R.string.update, extension.name) { install(extension) } }
        state.installed.forEach { extension -> row(R.string.remove, extension.name) { confirmRemove(extension) } }
        state.available.forEach { extension -> row(R.string.install, extension.name) { install(extension) } }
        state.untrusted.forEach { extension ->
            row(R.string.hayai_trust_signature, "${extension.name}\n${extension.signatureHash}") {
                AlertDialog.Builder(this)
                    .setTitle(R.string.hayai_trust_extension_signature_title)
                    .setMessage(extension.signatureHash)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.trust) { _, _ -> manager.trust(extension) }
                    .show()
            }
        }
        state.repositories.forEach { repository ->
            row(R.string.hayai_remove_repository, repository) {
                lifecycleScope.launch { manager.removeRepository(repository) }
            }
        }
    }
    private fun row(action: Int, label: String, click: () -> Unit) {
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    TextView(context).apply { text = label },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(Button(context).apply {
                    setText(action)
                    setOnClickListener { click() }
                })
            },
        )
    }
    private fun install(extension: Extension.Available) {
        if (installJob?.isActive == true) return
        installJob = lifecycleScope.launch {
            cancelInstall.isEnabled = true
            try {
                var terminal = false
                manager.install(extension, this).collect { info ->
                    terminal = info.first.isCompleted()
                    status.text = when (info.first) {
                        InstallStep.Error -> getString(R.string.hayai_extension_install_failed, extension.name)
                        InstallStep.Installed, InstallStep.Done -> getString(R.string.hayai_extension_install_complete, extension.name)
                        else -> getString(R.string.hayai_extension_install_status, extension.name, installStepText(info.first))
                    }
                }
                if (!terminal) status.text = getString(R.string.hayai_extension_install_stopped, extension.name)
            } catch (_: CancellationException) {
                status.text = getString(R.string.hayai_extension_install_canceled, extension.name)
            } catch (error: Throwable) {
                status.text =
                    getString(
                        R.string.hayai_extension_install_error,
                        extension.name,
                        novelFailureMessage(error, R.string.hayai_unknown_installer_error),
                    )
            } finally {
                cancelInstall.isEnabled = false
            }
        }
    }
    private fun addRepository() {
        val input = EditText(this).apply { setHint(R.string.hayai_novel_extension_repository_url_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.hayai_add_novel_extension_repository)
            .setMessage(R.string.hayai_novel_extension_repository_warning)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                lifecycleScope.launch {
                    runCatching { manager.addRepository(input.text.toString().trim()) }
                        .onFailure { status.text = novelFailureMessage(it, R.string.hayai_unknown_installer_error) }
                }
            }
            .show()
    }

    private fun confirmRemove(extension: Extension.Installed) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hayai_remove_named_item, extension.name))
            .setMessage(R.string.hayai_remove_extension_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> manager.remove(extension) }
            .show()
    }

    private fun launchAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }.onFailure { status.text = novelFailureMessage(it, R.string.hayai_unknown_installer_error) }
        }
    }

    private fun installStepText(step: InstallStep): String =
        getString(
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
