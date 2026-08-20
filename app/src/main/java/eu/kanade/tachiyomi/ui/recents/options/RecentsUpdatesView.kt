package eu.kanade.tachiyomi.ui.recents.options

import android.content.Context
import android.util.AttributeSet
import dev.ahmedmohamed.hayai.recents.RecentSourceVisibilityEditor
import dev.ahmedmohamed.hayai.recents.RecentSurface
import eu.kanade.tachiyomi.databinding.RecentsUpdatesViewBinding
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.widget.BaseRecentsDisplayView

class RecentsUpdatesView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : BaseRecentsDisplayView<RecentsUpdatesViewBinding>(context, attrs) {
        private val sourceVisibilityEditor by lazy(::RecentSourceVisibilityEditor)

        override fun inflateBinding() = RecentsUpdatesViewBinding.bind(this)

        override fun initGeneralPreferences() {
            binding.showUpdatedTime.bindToPreference(preferences.showUpdatedTime())
            binding.sortFetchedTime.bindToPreference(preferences.sortFetchedTime())
            binding.groupChapters.bindToPreference(preferences.collapseGroupedUpdates()) {
                controller?.presenter?.expandedSectionsMap?.clear()
            }
            updateHiddenSourcesLabel()
            binding.hiddenSources.setOnClickListener {
                val recentsController = controller ?: return@setOnClickListener
                sourceVisibilityEditor.show(context, recentsController.viewScope, RecentSurface.Updates) {
                    updateHiddenSourcesLabel()
                }
            }
        }

        private fun updateHiddenSourcesLabel() {
            binding.hiddenSources.text = sourceVisibilityEditor.label(context, RecentSurface.Updates)
        }
    }
