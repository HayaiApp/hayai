package eu.kanade.tachiyomi.ui.extension

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.RecyclerWithScrollerBinding
import eu.kanade.tachiyomi.widget.EmptyView

class RecyclerWithScrollerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    var binding: RecyclerWithScrollerBinding? = null
    fun setUp(sheet: ExtensionBottomSheet, binding: RecyclerWithScrollerBinding, height: Int) {
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.setItemViewCacheSize(8)
        // Bump the recycled view pool size for the layouts used by ExtensionAdapter,
        // SourceAdapter, NovelPluginAdapter. Default per-type cap is 5; with ~13 visible
        // rows the bottom sheet was re-inflating 8+ rows every time the user returned to
        // Browse (each ~15–30ms), the dominant cause of 200–600ms first-attach frames.
        // Share a single pool across all 3 tabs so swaps between them also benefit.
        binding.recycler.setRecycledViewPool(sheet.sharedExtensionPool)
        sheet.sharedExtensionPool.setMaxRecycledViews(R.layout.extension_card_item, 30)
        sheet.sharedExtensionPool.setMaxRecycledViews(R.layout.extension_card_header, 8)
        // novel_plugin_card_* are layout aliases of the extension_card_* layouts;
        // FlexibleAdapter uses them as distinct viewTypes so the shared pool partitions
        // ExtensionHolder vs NovelPluginHolder (and their group headers) correctly.
        // Mirror the caps so novel cards get the same reuse behavior.
        sheet.sharedExtensionPool.setMaxRecycledViews(R.layout.novel_plugin_card_item, 30)
        sheet.sharedExtensionPool.setMaxRecycledViews(R.layout.novel_plugin_card_header, 8)
        // Kill the item animator. Each tab swap (Manga/Extensions <-> Novel sources) ends
        // with an updateDataSet → notifyDataSetChanged on the destination recycler that, with
        // DefaultItemAnimator attached, runs add/change fades on every visible row — visible
        // as a flicker during the ViewPager swipe.
        binding.recycler.itemAnimator = null
        binding.recycler.addItemDecoration(ExtensionDividerItemDecoration(context))
        binding.recycler.updatePaddingRelative(bottom = height)
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE ||
                        newState == RecyclerView.SCROLL_STATE_SETTLING
                    ) {
                        sheet.sheetBehavior?.isDraggable = true
                    } else {
                        sheet.sheetBehavior?.isDraggable = !recyclerView.canScrollVertically(-1)
                    }
                }
            },
        )

        this.binding = binding
    }

    fun onBind(adapter: FlexibleAdapter<IFlexible<*>>) {
        val binding = binding ?: return
        if (binding.recycler.adapter !== adapter) {
            binding.recycler.adapter = adapter
        }
        if (adapter.fastScroller !== binding.fastScroller) {
            adapter.fastScroller = binding.fastScroller
        }
    }

    fun showEmptyState(image: ImageVector, message: String, actions: List<EmptyView.Action> = emptyList()) {
        binding?.emptyView?.show(image, message, actions)
    }

    fun hideEmptyState() {
        binding?.emptyView?.hide()
    }
}
