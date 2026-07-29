package eu.kanade.tachiyomi.ui.source.browse.compose

import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import eu.kanade.tachiyomi.databinding.SourceFilterSheetComposeBinding
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import yokai.domain.source.browse.filter.FilterTreeSnapshot
import yokai.domain.source.browse.filter.FilterPath
import yokai.domain.source.browse.filter.models.SavedSearch
import yokai.presentation.theme.YokaiTheme

/**
 * Expanded M3 Expressive source-refinement sheet.
 *
 * The host owns a draft filter tree through [FilterSheetStateHolder]. The presenter and pager
 * never see those mutable source objects until the sheet closes and [onCommit] is invoked once.
 */
class ComposeSourceFilterSheet(
    val activity: Activity,
    draftFilters: FilterList,
    baseline: FilterTreeSnapshot,
    sourceQuery: String,
    private val initialPath: FilterPath? = null,
    private val getSavedSearches: () -> List<SavedSearch>,
    private val onCommit: (FilterList, String) -> Unit,
    private val onSaveClicked: (FilterList, String) -> Unit,
    private val onSavedSearchClicked: (Long) -> Unit,
    private val onDeleteSavedSearchClicked: (Long) -> Unit,
) : E2EBottomSheetDialog<SourceFilterSheetComposeBinding>(activity) {

    override fun createBinding(inflater: LayoutInflater) =
        SourceFilterSheetComposeBinding.inflate(inflater)

    private val stateHolder = FilterSheetStateHolder(
        draftFilters = draftFilters,
        baseline = baseline,
        savedSearches = getSavedSearches(),
        sourceQuery = sourceQuery,
    )
    private var committed = false

    init {
        sheetBehavior.skipCollapsed = true
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        binding.root.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT

        binding.filterComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        binding.filterComposeView.setContent {
            YokaiTheme {
                FrictionlessFilterSheetContent(
                    holder = stateHolder,
                    initialPath = initialPath,
                    onDone = { dismiss() },
                    onSave = { onSaveClicked(stateHolder.draftFilters(), stateHolder.sourceQuery()) },
                    onLoadPreset = onSavedSearchClicked,
                    onDeletePreset = onDeleteSavedSearchClicked,
                    onListScrollChange = { canScrollUp ->
                        sheetBehavior.isDraggable = !canScrollUp
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val windowWidth = window?.decorView?.width?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val windowHeight = window?.decorView?.height?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
        val widthDp = activity.resources.configuration.screenWidthDp
        val targetWidth = when {
            widthDp < 600 -> windowWidth
            widthDp < 840 -> minOf(windowWidth, 720.dpToPx)
            else -> minOf(windowWidth, 960.dpToPx)
        }
        (binding.root.parent as? View)?.let { sheet ->
            sheet.layoutParams = sheet.layoutParams.apply {
                width = targetWidth
                height = (windowHeight * 0.94f).toInt()
                if (this is CoordinatorLayout.LayoutParams) {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
            }
        }
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun refreshSavedSearches() {
        stateHolder.updateSavedSearches(getSavedSearches())
    }

    fun loadPreset(search: SavedSearch) {
        val filters = search.filters ?: return
        stateHolder.loadPreset(filters, search.query)
    }

    override fun dismiss() {
        if (!committed) {
            committed = true
            onCommit(stateHolder.draftFilters(), stateHolder.sourceQuery())
        }
        super.dismiss()
    }
}
