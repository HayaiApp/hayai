package dev.ahmedmohamed.hayai.source.enhanced.batch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.launch

class EnhancedBatchAddActivity : BaseActivity<ViewBinding>() {
    private val viewModel by viewModels<EnhancedBatchAddViewModel>()
    private lateinit var input: EditText
    private lateinit var start: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var output: TextView
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hayai_enhanced_batch_title)
        setContentView(buildContent())
        savedInstanceState?.getString(STATE_INPUT)?.let(viewModel::updateInput)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_INPUT, input.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx)
        }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.hayai_enhanced_batch_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = getString(R.string.hayai_enhanced_batch_instructions)
        }, matchWidth())
        input = EditText(this).apply {
            hint = getString(R.string.hayai_enhanced_batch_hint)
            minLines = 6
            maxLines = 14
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!rendering) viewModel.updateInput(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        start = MaterialButton(this).apply {
            text = getString(R.string.add_to_library)
            setOnClickListener { viewModel.start() }
        }
        cancel = MaterialButton(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { viewModel.cancel() }
        }
        actions.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, matchWidth())
        val scroll = ScrollView(this)
        output = TextView(this).apply { setPadding(8.dpToPx) }
        scroll.addView(output, matchWidth())
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render(state: EnhancedBatchAddUiState) {
        if (input.text.toString() != state.input) {
            rendering = true
            input.setText(state.input)
            input.setSelection(input.text.length)
            rendering = false
        }
        input.isEnabled = !state.running
        start.isEnabled = !state.running && state.input.isNotBlank()
        cancel.isEnabled = state.running
        output.text = buildString {
            state.error?.let { appendLine(it.localizedMessage()) }
            state.progress?.let { progress ->
                appendLine(getString(R.string.hayai_enhanced_batch_progress, progress.completed, progress.total))
                progress.currentUrl?.let { appendLine(getString(R.string.hayai_enhanced_batch_loading, it)) }
                progress.results.forEach { appendLine(it.displayText()) }
            }
            state.report?.let { report ->
                appendLine()
                append(
                    getString(
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

    private fun EnhancedBatchItemResult.displayText(): String = when (this) {
        is EnhancedBatchItemResult.Added -> getString(R.string.hayai_enhanced_batch_added, title, target.sourceName)
        is EnhancedBatchItemResult.AlreadyInLibrary -> getString(R.string.hayai_enhanced_batch_present, title)
        is EnhancedBatchItemResult.Duplicate -> getString(R.string.hayai_enhanced_batch_duplicate, submittedUrl)
        is EnhancedBatchItemResult.Failed -> getString(R.string.hayai_enhanced_batch_failed, submittedUrl, reason.localizedMessage())
    }

    private fun EnhancedBatchFailure.localizedMessage(): String = when (this) {
        is EnhancedBatchFailure.UnsupportedSource -> getString(R.string.hayai_enhanced_failure_unsupported_source)
        is EnhancedBatchFailure.AmbiguousSource -> getString(R.string.hayai_enhanced_failure_ambiguous_source, sourceNames.joinToString())
        is EnhancedBatchFailure.Network -> getString(R.string.hayai_enhanced_failure_network)
        is EnhancedBatchFailure.InvalidGallery -> getString(R.string.hayai_enhanced_failure_invalid_gallery)
        is EnhancedBatchFailure.Persistence -> getString(R.string.hayai_enhanced_failure_persistence)
    }

    private fun EnhancedBatchUiError.localizedMessage(): String = getString(
        when (this) {
            EnhancedBatchUiError.InvalidInput -> R.string.hayai_enhanced_batch_invalid_input
            EnhancedBatchUiError.Cancelled -> R.string.cancelled
            EnhancedBatchUiError.Failed -> R.string.hayai_enhanced_batch_run_failed
        },
    )

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val STATE_INPUT = "hayai.enhanced.batch.input"
        fun newIntent(context: Context): Intent = Intent(context, EnhancedBatchAddActivity::class.java)
    }
}
