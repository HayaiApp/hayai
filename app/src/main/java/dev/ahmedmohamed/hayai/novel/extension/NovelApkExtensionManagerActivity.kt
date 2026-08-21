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
        title = "Novel extensions"
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                status = TextView(context)
                addView(status)
                addView(
                    Button(context).apply {
                        text = "Refresh"
                        setOnClickListener { launchAction { manager.refresh() } }
                    },
                )
                addView(
                    Button(context).apply {
                        text = "Add repository"
                        setOnClickListener { addRepository() }
                    },
                )
                cancelInstall = Button(context).apply {
                    text = "Cancel installation"
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
        status.text = state.error ?: if (state.refreshing) "Refreshing…" else "${state.installed.size} installed, ${state.updates.size} updates"
        content.removeAllViews()
        state.updates.forEach { extension -> row("Update", extension.name) { install(extension) } }
        state.installed.forEach { extension -> row("Remove", extension.name) { confirmRemove(extension) } }
        state.available.forEach { extension -> row("Install", extension.name) { install(extension) } }
        state.untrusted.forEach { extension ->
            row("Trust signature", "${extension.name}\n${extension.signatureHash}") {
                AlertDialog.Builder(this)
                    .setTitle("Trust extension signature?")
                    .setMessage(extension.signatureHash)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Trust") { _, _ -> manager.trust(extension) }
                    .show()
            }
        }
        state.repositories.forEach { repository -> row("Remove repository", repository) { lifecycleScope.launch { manager.removeRepository(repository) } } }
    }
    private fun row(action: String, label: String, click: () -> Unit) {
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    TextView(context).apply { text = label },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(Button(context).apply {
                    text = action
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
                        InstallStep.Error -> "${extension.name}: installation failed. The package was not activated."
                        InstallStep.Installed, InstallStep.Done -> "${extension.name}: installation complete."
                        else -> "${extension.name}: ${info.first}"
                    }
                }
                if (!terminal) status.text = "${extension.name}: installation stopped before completion."
            } catch (_: CancellationException) {
                status.text = "${extension.name}: installation canceled."
            } catch (error: Throwable) {
                status.text = "${extension.name}: installation failed. ${error.message ?: "Unknown installer error"}"
            } finally {
                cancelInstall.isEnabled = false
            }
        }
    }
    private fun addRepository() {
        val input = EditText(this).apply { hint = "https://example.org/index.min.json" }
        AlertDialog.Builder(this)
            .setTitle("Add novel extension repository")
            .setMessage("Only add repositories whose publisher and signing policy you trust.")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Add") { _, _ ->
                lifecycleScope.launch {
                    runCatching { manager.addRepository(input.text.toString().trim()) }
                        .onFailure { status.text = it.message }
                }
            }
            .show()
    }

    private fun confirmRemove(extension: Extension.Installed) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${extension.name}?")
            .setMessage("The source becomes unavailable until reinstalled. Library data is kept.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove") { _, _ -> manager.remove(extension) }
            .show()
    }

    private fun launchAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }.onFailure { status.text = it.message }
        }
    }
}
