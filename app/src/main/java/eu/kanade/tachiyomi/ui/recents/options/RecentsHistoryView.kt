package eu.kanade.tachiyomi.ui.recents.options

import android.content.Context
import android.util.AttributeSet
import dev.ahmedmohamed.hayai.recents.RecentSourceVisibilityEditor
import dev.ahmedmohamed.hayai.recents.RecentSurface
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.RecentsHistoryViewBinding
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.widget.BaseRecentsDisplayView

class RecentsHistoryView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : BaseRecentsDisplayView<RecentsHistoryViewBinding>(context, attrs) {
        private val sourceVisibilityEditor by lazy(::RecentSourceVisibilityEditor)

        override fun inflateBinding() = RecentsHistoryViewBinding.bind(this)

        override fun initGeneralPreferences() {
            binding.groupChapters.bindToPreference(preferences.groupChaptersHistory())
            binding.collapseGroupedChapters.bindToPreference(preferences.collapseGroupedHistory()) {
                controller?.presenter?.expandedSectionsMap?.clear()
            }
            updateHiddenSourcesLabel()
            binding.hiddenSources.setOnClickListener {
                val recentsController = controller ?: return@setOnClickListener
                sourceVisibilityEditor.show(context, recentsController.viewScope, RecentSurface.History) {
                    updateHiddenSourcesLabel()
                }
            }
            binding.clearHistory.setOnClickListener {
                val activity = controller?.activity ?: return@setOnClickListener
                activity
                    .materialAlertDialog()
                    .setMessage(R.string.clear_history_confirmation)
                    .setPositiveButton(R.string.clear) { _, _ ->
                        controller?.presenter?.deleteAllHistory()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        private fun updateHiddenSourcesLabel() {
            binding.hiddenSources.text = sourceVisibilityEditor.label(context, RecentSurface.History)
        }
    }
