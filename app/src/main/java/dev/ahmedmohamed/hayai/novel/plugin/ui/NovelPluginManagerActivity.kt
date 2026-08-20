package dev.ahmedmohamed.hayai.novel.plugin.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.ahmedmohamed.hayai.novel.plugin.InstalledNovelPlugin
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginCatalog
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginDescriptor
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginRepository
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelPluginManagerActivity : AppCompatActivity() {
    private val manager by lazy { Injekt.get<NovelPluginManager>() }
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var content: LinearLayout
    private var query = ""
    private var latest = NovelPluginCatalog()
    private var actionInProgress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hayai_novel_plugins)
        setContentView(buildScreen())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.catalog.collect { state ->
                    latest = state
                    render()
                }
            }
        }
        if (savedInstanceState == null) runAction(getString(R.string.refresh)) { manager.refresh() }
    }

    private fun buildScreen(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))

            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = getString(R.string.hayai_novel_plugins)
                            textSize = 24f
                            setTypeface(typeface, Typeface.BOLD)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        Button(context).apply {
                            text = getString(R.string.refresh)
                            setOnClickListener { runAction(text.toString()) { manager.refresh() } }
                        },
                    )
                    addView(
                        Button(context).apply {
                            text = getString(R.string.add)
                            setOnClickListener { showAddRepository() }
                        },
                    )
                },
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.hayai_novel_plugins_security_summary)
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
            addView(
                EditText(context).apply {
                    hint = getString(R.string.search)
                    inputType = InputType.TYPE_CLASS_TEXT
                    doAfterTextChanged {
                        query = it?.toString().orEmpty()
                        render()
                    }
                },
                matchWidth(),
            )
            progress =
                ProgressBar(context).apply {
                    isIndeterminate = true
                    isVisible = false
                }
            addView(
                progress,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity =
                        Gravity.CENTER_HORIZONTAL
                },
            )
            errorText = TextView(context).apply { setTextColor(Color.RED) }
            addView(errorText, matchWidth())
            addView(
                ScrollView(context).apply {
                    content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    addView(content, matchWidth())
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

    private fun render() {
        if (!::content.isInitialized) return
        progress.isVisible = latest.refreshing || actionInProgress != null
        errorText.text = latest.repositoryErrors.entries.joinToString("\n") { (url, error) -> "$url: $error" }
        errorText.isVisible = errorText.text.isNotBlank()
        content.removeAllViews()
        content.addView(sectionTitle(getString(R.string.hayai_novel_repositories)))
        if (latest.repositories.isEmpty()) content.addView(message(getString(R.string.hayai_no_novel_repositories)))
        latest.repositories.forEach { content.addView(repositoryRow(it)) }

        content.addView(sectionTitle(getString(R.string.hayai_novel_plugins)))
        val installedById = latest.installed.associateBy { it.descriptor.id }
        val availableById = latest.available.associateBy(NovelPluginDescriptor::id)
        val descriptors =
            (availableById.keys + installedById.keys)
                .mapNotNull { availableById[it] ?: installedById[it]?.descriptor }
                .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) || it.lang.contains(query, true) }
                .sortedWith(compareBy(NovelPluginDescriptor::normalizedLanguage, NovelPluginDescriptor::name))
        if (descriptors.isEmpty()) content.addView(message(getString(R.string.hayai_no_novel_plugins)))
        descriptors.forEach { descriptor -> content.addView(pluginRow(descriptor, installedById[descriptor.id])) }
    }

    private fun repositoryRow(repository: NovelPluginRepository): LinearLayout =
        card().apply {
            addView(
                TextView(context).apply {
                    text = "${repository.name}\n${repository.url}"
                    setTextIsSelectable(true)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                SwitchMaterial(context).apply {
                    isChecked = repository.enabled
                    contentDescription = getString(R.string.enable)
                    setOnCheckedChangeListener { _, enabled ->
                        runAction(repository.name) {
                            manager.setRepositoryEnabled(repository.url, enabled)
                            manager.refresh()
                        }
                    }
                },
            )
            addView(
                Button(context).apply {
                    text = getString(R.string.remove)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@NovelPluginManagerActivity)
                            .setTitle(R.string.remove)
                            .setMessage(getString(R.string.hayai_remove_repository_confirmation, repository.name))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.remove) { _, _ ->
                                runAction(repository.name) {
                                    manager.removeRepository(repository.url)
                                    manager.refresh()
                                }
                            }.show()
                    }
                },
            )
        }

    private fun pluginRow(
        descriptor: NovelPluginDescriptor,
        installed: InstalledNovelPlugin?,
    ): LinearLayout =
        card(vertical = true).apply {
            addView(
                TextView(context).apply {
                    text = descriptor.name
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                },
            )
            addView(
                TextView(context).apply {
                    text =
                        buildString {
                            append(descriptor.lang).append(" · ").append(descriptor.id)
                            append("\n").append(getString(R.string.version)).append(' ').append(descriptor.version)
                            if (installed != null) append(" · ").append(getString(R.string.installed))
                        }
                },
            )
            addView(
                Button(context).apply {
                    val update = installed != null && manager.hasUpdate(descriptor.id)
                    text =
                        when {
                            installed == null -> getString(R.string.install)
                            update -> getString(R.string.update)
                            else -> getString(R.string.uninstall)
                        }
                    isEnabled = actionInProgress == null
                    setOnClickListener {
                        when {
                            installed == null -> confirmPluginInstall(descriptor)
                            update -> confirmPluginInstall(descriptor)
                            else -> runAction(descriptor.name) { manager.uninstall(descriptor.id) }
                        }
                    }
                },
                matchWidth(),
            )
        }

    private fun confirmPluginInstall(descriptor: NovelPluginDescriptor) {
        MaterialAlertDialogBuilder(this)
            .setTitle(descriptor.name)
            .setMessage(R.string.hayai_novel_plugin_install_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.install) { _, _ -> runAction(descriptor.name) { manager.install(descriptor.id) } }
            .show()
    }

    private fun showAddRepository() {
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
            }
        val name = EditText(this).apply { hint = getString(R.string.name) }
        val url =
            EditText(this).apply {
                hint = "https://example.org/plugins.json"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
            }
        form.addView(name, matchWidth())
        form.addView(url, matchWidth())
        val dialog =
            MaterialAlertDialogBuilder(this)
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
                actionInProgress = label.ifBlank { getString(R.string.hayai_add_novel_repository) }
                render()
                lifecycleScope.launch {
                    runCatching {
                        manager.addRepository(label, url.text.toString().trim())
                        manager.refresh()
                    }.onSuccess {
                        dialog.dismiss()
                    }.onFailure {
                        url.error = it.message ?: getString(R.string.unknown_error)
                    }
                    actionInProgress = null
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun runAction(
        label: String,
        action: suspend () -> Any?,
    ) {
        if (actionInProgress != null) return
        actionInProgress = label
        render()
        lifecycleScope.launch {
            runCatching { action() }
                .onFailure {
                    Toast
                        .makeText(
                            this@NovelPluginManagerActivity,
                            it.message ?: getString(R.string.unknown_error),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            actionInProgress = null
            render()
        }
    }

    private fun sectionTitle(value: String) =
        TextView(this).apply {
            text = value
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        }

    private fun message(value: String) =
        TextView(this).apply {
            text = value
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun card(vertical: Boolean = false) =
        LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x11000000)
                }
            layoutParams = matchWidth().apply { setMargins(0, dp(4), 0, dp(4)) }
        }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
