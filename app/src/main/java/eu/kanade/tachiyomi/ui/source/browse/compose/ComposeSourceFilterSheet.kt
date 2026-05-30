package eu.kanade.tachiyomi.ui.source.browse.compose

import android.app.Activity
import android.view.LayoutInflater
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import eu.kanade.tachiyomi.databinding.SourceFilterSheetComposeBinding
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import kotlinx.coroutines.flow.MutableStateFlow
import yokai.domain.source.browse.filter.models.SavedSearch
import yokai.presentation.theme.YokaiTheme

/**
 * M3 Expressive replacement for [eu.kanade.tachiyomi.ui.source.browse.SourceFilterSheet].
 *
 * Same dialog shell (`E2EBottomSheetDialog`) and same callback contract — the controller can
 * swap between the two implementations at the call site without touching anything downstream.
 * Internally the sheet hosts a single [androidx.compose.ui.platform.ComposeView]; the tabs,
 * filter rows, saved-search list, and action bar all live in Compose.
 *
 * Filter state mutation is preserved verbatim: composables receive the same `Filter.X` instances
 * the presenter holds and mutate `.state` in place. This is what
 * `BrowseSourceController.showFilters()` relies on when it snapshots `oldFilters` to detect
 * whether anything actually changed after the sheet dismisses.
 */
class ComposeSourceFilterSheet(
    val activity: Activity,
    private val getSavedSearches: () -> List<SavedSearch>,
    private val getFilters: () -> FilterList,
    private val onSearchClicked: () -> Unit,
    private val onResetClicked: () -> Unit,
    private val onSaveClicked: () -> Unit,
    private val onSavedSearchClicked: (Long) -> Unit,
    private val onDeleteSavedSearchClicked: (Long) -> Unit,
) : E2EBottomSheetDialog<SourceFilterSheetComposeBinding>(activity) {

    override fun createBinding(inflater: LayoutInflater) =
        SourceFilterSheetComposeBinding.inflate(inflater)

    // Change tokens. Each external call to setFilters() / refreshSavedSearches() bumps the
    // matching counter and the composition re-keys, so any FilterList-pointer swap (reset,
    // saved-search apply) or saved-search-list change is observed without a separate data
    // pipeline.
    private val filterVersion = MutableStateFlow(0)
    private val savedSearchesVersion = MutableStateFlow(0)

    init {
        // The sheet content sizes itself (wrap_content): the body LazyColumn is bounded by a
        // responsive heightIn(max) in Compose that leaves room for the tabs and the action bar,
        // so the whole Column always fits inside the peek window and the bottom buttons are
        // visible without dragging. Derive the peek from screen height (matching the body's
        // ~55% rule plus chrome) so it adapts to device size and landscape, clamped to stay
        // usable on tiny screens and bounded on tablets.
        val screenHeightPx = activity.resources.displayMetrics.heightPixels
        sheetBehavior.peekHeight = (screenHeightPx * 0.7f).toInt()
            .coerceIn(380.dpToPx, 700.dpToPx)

        binding.filterComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        binding.filterComposeView.setContent {
            val filterVer by filterVersion.collectAsState()
            val savedVer by savedSearchesVersion.collectAsState()
            YokaiTheme {
                SourceFilterSheetContent(
                    filters = getFilters(),
                    savedSearches = getSavedSearches(),
                    filterVersion = filterVer,
                    savedSearchesVersion = savedVer,
                    onApply = { dismiss() },
                    onReset = {
                        onResetClicked()
                        // Reset replaces the FilterList instance; bump the version so the
                        // composition picks up the new pointer.
                        filterVersion.value = filterVer + 1
                    },
                    onSave = onSaveClicked,
                    onSavedSearchClicked = onSavedSearchClicked,
                    onDeleteSavedSearchClicked = onDeleteSavedSearchClicked,
                    onListScrollChange = { canScrollUp ->
                        // Replicates E2EBottomSheetDialog's "only drag when scrolled to top"
                        // gesture, which the base class wires only for RecyclerView.
                        sheetBehavior.isDraggable = !canScrollUp
                    },
                )
            }
        }
    }

    /**
     * Called by the controller after [onResetClicked] or [onSavedSearchClicked] swap
     * `presenter.sourceFilters` for a fresh [FilterList]. Bumps the filter version so the
     * composition reads the new instance.
     */
    fun refreshFilters() {
        filterVersion.value = filterVersion.value + 1
    }

    /**
     * Called by the controller after a successful save so the "Saved" tab's count and list
     * pick up the new entry.
     */
    fun refreshSavedSearches() {
        savedSearchesVersion.value = savedSearchesVersion.value + 1
    }

    override fun dismiss() {
        super.dismiss()
        onSearchClicked()
    }
}
