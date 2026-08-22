package dev.ahmedmohamed.hayai.source.enhanced.batch

import android.text.InputType
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class EnhancedBatchAddController : SettingsController() {
    private lateinit var coordinator: EnhancedBatchAddCoordinator
    private lateinit var input: Preference
    private lateinit var start: Preference
    private lateinit var cancel: Preference
    private lateinit var output: Preference
    private var collectionJob: Job? = null
    private var currentInput = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_enhanced_batch_title
        coordinator = EnhancedBatchAddCoordinator(viewScope)
        preferenceCategory {
            title = context.getString(R.string.hayai_enhanced_batch_instructions)
            input = preference {
                title = context.getString(R.string.hayai_enhanced_batch_hint)
                onClick { editInput() }
            }
            start = preference {
                title = context.getString(R.string.add_to_library)
                onClick { coordinator.start() }
            }
            cancel = preference {
                title = context.getString(R.string.cancel)
                onClick { coordinator.cancel() }
            }
        }
        preferenceCategory {
            output = preference { isSelectable = false }
        }
        collectionJob = viewScope.launch { coordinator.state.collect(::render) }
    }

    override fun onDestroy() {
        coordinator.cancel()
        collectionJob?.cancel()
        super.onDestroy()
    }

    private fun editInput() {
        val context = activity ?: return
        val editor = EditText(context).apply {
            hint = context.getString(R.string.hayai_enhanced_batch_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            maxLines = 16
            setText(currentInput)
            setSelection(text.length)
        }
        context.materialAlertDialog()
            .setTitle(R.string.hayai_enhanced_batch_title)
            .setView(editor)
            .setPositiveButton(android.R.string.ok) { _, _ -> coordinator.updateInput(editor.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: EnhancedBatchAddUiState) {
        val context = activity ?: return
        currentInput = state.input
        input.summary = state.input.ifBlank { context.getString(R.string.hayai_enhanced_batch_hint) }
        input.isEnabled = !state.running
        start.isEnabled = !state.running && state.input.isNotBlank()
        cancel.isEnabled = state.running
        output.summary = buildString {
            state.error?.let { appendLine(it.localizedMessage()) }
            state.progress?.let { progress ->
                appendLine(context.getString(R.string.hayai_enhanced_batch_progress, progress.completed, progress.total))
                progress.currentUrl?.let { appendLine(context.getString(R.string.hayai_enhanced_batch_loading, it)) }
                progress.results.forEach { appendLine(it.displayText()) }
            }
            state.report?.let { report ->
                if (isNotEmpty()) appendLine()
                append(
                    context.getString(
                        R.string.hayai_enhanced_batch_summary,
                        report.added,
                        report.alreadyPresent,
                        report.duplicates,
                        report.failed,
                    ),
                )
            }
        }
    }

    private fun EnhancedBatchItemResult.displayText(): String {
        val context = requireNotNull(activity)
        return when (this) {
            is EnhancedBatchItemResult.Added -> context.getString(R.string.hayai_enhanced_batch_added, title, target.sourceName)
            is EnhancedBatchItemResult.AlreadyInLibrary -> context.getString(R.string.hayai_enhanced_batch_present, title)
            is EnhancedBatchItemResult.Duplicate -> context.getString(R.string.hayai_enhanced_batch_duplicate, submittedUrl)
            is EnhancedBatchItemResult.Failed -> context.getString(R.string.hayai_enhanced_batch_failed, submittedUrl, reason.localizedMessage())
        }
    }

    private fun EnhancedBatchFailure.localizedMessage(): String {
        val context = requireNotNull(activity)
        return when (this) {
            is EnhancedBatchFailure.UnsupportedSource -> context.getString(R.string.hayai_enhanced_failure_unsupported_source)
            is EnhancedBatchFailure.AmbiguousSource -> context.getString(R.string.hayai_enhanced_failure_ambiguous_source, sourceNames.joinToString())
            is EnhancedBatchFailure.Network -> context.getString(R.string.hayai_enhanced_failure_network)
            is EnhancedBatchFailure.InvalidGallery -> context.getString(R.string.hayai_enhanced_failure_invalid_gallery)
            is EnhancedBatchFailure.Persistence -> context.getString(R.string.hayai_enhanced_failure_persistence)
        }
    }

    private fun EnhancedBatchUiError.localizedMessage(): String = requireNotNull(resources).getString(
        when (this) {
            EnhancedBatchUiError.InvalidInput -> R.string.hayai_enhanced_batch_invalid_input
            EnhancedBatchUiError.Cancelled -> R.string.cancelled
            EnhancedBatchUiError.Failed -> R.string.hayai_enhanced_batch_run_failed
        },
    )
}
