package dev.ahmedmohamed.hayai.source.preview

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.slider.Slider
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.ui.EhSettingsController
import dev.ahmedmohamed.hayai.novel.reader.ReaderLauncher
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.databinding.SubDebugControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.EmptyView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okhttp3.CacheControl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

class SourcePreviewController(
    bundle: Bundle? = null,
) : BaseController<SubDebugControllerBinding>(bundle) {
    private val database: DatabaseHelper = Injekt.get()
    private val previews: SourceDetailsPreviewRegistry = Injekt.get()
    private val mangaId = args.getLong(MANGA_ID)
    private val manga: Manga? by lazy { database.getManga(mangaId).executeAsBlocking() }
    private val adapter = PreviewAdapter(::openPage)
    private lateinit var loading: LoadingIndicator
    private lateinit var emptyView: EmptyView
    private var navigation = SourcePreviewNavigation(args.getInt(CURRENT_PAGE, 1))
    private val window = SourcePreviewWindow()
    private var continuous = args.getBoolean(CONTINUOUS, false)
    private var loadGeneration = 0
    private var loadJob: Job? = null
    private var failedPage: Int? = null
    private var viewAlive = false

    constructor(mangaId: Long) : this(Bundle().apply { putLong(MANGA_ID, mangaId) })

    override fun getTitle(): String? = resources?.getString(navigation.titleResource, *navigation.titleArguments)

    override fun createBinding(inflater: LayoutInflater) = SubDebugControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        viewAlive = true
        scrollViewWith(binding.recycler, padBottom = true)
        binding.recycler.layoutManager = GridLayoutManager(view.context, spanCount(view))
        binding.recycler.adapter = adapter
        binding.recycler.setPadding(
            dp(SyPagePreviewLayout.OUTER_PADDING_DP),
            dp(SyPagePreviewLayout.OUTER_PADDING_DP),
            dp(SyPagePreviewLayout.OUTER_PADDING_DP),
            dp(SyPagePreviewLayout.OUTER_PADDING_DP),
        )
        binding.recycler.addItemDecoration(SyPreviewSpacingDecoration())
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                rememberPosition()
                loadAdjacent(dy)
            }
        })

        val root = binding.root as FrameLayout
        loading = LoadingIndicator(view.context).apply { isVisible = false }
        root.addView(loading, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        emptyView = EmptyView(view.context).apply { hide() }
        root.addView(
            emptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        if (manga == null) {
            emptyView.show(R.drawable.ic_search_off_24dp, R.string.no_results_found)
        } else {
            val anchor = args.getInt(ANCHOR_INDEX, 0).takeIf { it > 0 }?.let {
                ScrollAnchor(it, args.getInt(ANCHOR_OFFSET, 0))
            }
            load(navigation.page, anchor = anchor)
        }
    }

    override fun onDestroyView(view: View) {
        viewAlive = false
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        binding.recycler.adapter = null
        adapter.clear()
        super.onDestroyView(view)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        navigation.previousPage?.let {
            menu.add(Menu.NONE, MENU_PREVIOUS, 0, R.string.hayai_preview_previous)
                .setIcon(R.drawable.ic_page_previous_outline_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        navigation.nextPage?.let {
            menu.add(Menu.NONE, MENU_NEXT, 1, R.string.hayai_preview_next)
                .setIcon(R.drawable.ic_page_next_outline_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (navigation.canChoosePage) {
            menu.add(Menu.NONE, MENU_GO_TO, 2, R.string.hayai_go_to_preview_page)
                .setIcon(R.drawable.ic_page_next_outline_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.addSubMenu(R.string.hayai_preview_layout).apply {
            add(MODE_GROUP, MENU_PAGED, 0, R.string.hayai_preview_paged).isChecked = !continuous
            add(MODE_GROUP, MENU_CONTINUOUS, 1, R.string.hayai_preview_continuous).isChecked = continuous
            setGroupCheckable(MODE_GROUP, true, true)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_GO_TO -> {
            showGoToDialog()
            true
        }
        MENU_PREVIOUS -> {
            navigation.previousPage?.let(::seekTo)
            true
        }
        MENU_NEXT -> {
            navigation.nextPage?.let(::seekTo)
            true
        }
        MENU_PAGED, MENU_CONTINUOUS -> {
            val nextContinuous = item.itemId == MENU_CONTINUOUS
            if (continuous != nextContinuous) {
                val anchor = currentAnchor()
                continuous = nextContinuous
                args.putBoolean(CONTINUOUS, continuous)
                load(navigation.page, anchor = anchor)
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun load(
        targetPage: Int,
        forceRefresh: Boolean = false,
        adjacent: Boolean = false,
        anchor: ScrollAnchor? = null,
        direction: Int = 0,
    ) {
        val manga = manga ?: return
        val context = binding.root.context
        val knownTotal = navigation.totalPages
        if (targetPage < 1 || knownTotal != null && targetPage > knownTotal) return
        loadJob?.cancel()
        failedPage = null
        val generation = ++loadGeneration
        loading.isVisible = true
        (loading.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (adjacent) {
                Gravity.CENTER_HORIZONTAL or if (direction < 0) Gravity.TOP else Gravity.BOTTOM
            } else {
                Gravity.CENTER
            }
            loading.layoutParams = this
        }
        emptyView.hide()
        if (!adjacent) binding.recycler.isVisible = false
        loadJob = viewScope.launchIO {
            val result = try {
                val loaded = previews.load(manga, targetPage, CacheControl.FORCE_NETWORK.takeIf { forceRefresh })
                require(loaded.page == targetPage) { context.getString(R.string.hayai_preview_wrong_page) }
                Result.success(loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            withUIContext {
                if (generation != loadGeneration || !viewAlive) return@withUIContext
                loadJob = null
                loading.isVisible = false
                result.onSuccess { loaded ->
                    val visibleAnchor = if (adjacent) currentAnchor() else anchor
                    if (adjacent) window.extend(loaded) else window.replace(loaded)
                    navigation = visibleAnchor?.let { window.navigationForImage(it.index) }
                        ?: requireNotNull(window.navigationForPage(loaded.page))
                    args.putInt(CURRENT_PAGE, navigation.page)
                    adapter.submit(manga, window.previews)
                    val layout = binding.recycler.layoutManager as GridLayoutManager
                    val position = visibleAnchor?.let { window.positionForImage(it.index) } ?: 0
                    layout.scrollToPositionWithOffset(position, visibleAnchor?.offset ?: 0)
                    binding.recycler.isVisible = adapter.itemCount > 0
                    if (adapter.itemCount == 0) {
                        emptyView.show(R.drawable.ic_search_off_24dp, R.string.hayai_no_page_previews)
                    }
                    setTitle()
                    activity?.invalidateOptionsMenu()
                    binding.recycler.doOnNextLayout {
                        if (generation == loadGeneration && viewAlive) {
                            rememberPosition()
                            loadAdjacent(direction)
                        }
                    }
                }.onFailure { error ->
                    failedPage = targetPage
                    if (adjacent && adapter.itemCount > 0) {
                        binding.recycler.snack(error.message ?: emptyView.context.getString(R.string.hayai_page_previews_failed)) {
                            setAction(R.string.retry) {
                                val stillAdjacent = continuous && targetPage in listOf(window.previousPage, window.nextPage)
                                load(targetPage, forceRefresh = true, adjacent = stillAdjacent, direction = direction)
                            }
                        }
                        return@onFailure
                    }
                    adapter.clear()
                    val actions = buildList {
                        add(EmptyView.Action(R.string.retry) { load(targetPage, forceRefresh = true) })
                        if (manga.source == EhSite.ExHentai.sourceId) {
                            add(
                                EmptyView.Action(R.string.hayai_open_ehentai_settings) {
                                    router.pushController(EhSettingsController().withFadeTransaction())
                                },
                            )
                        }
                    }
                    emptyView.show(
                        R.drawable.ic_search_off_24dp,
                        error.message ?: emptyView.context.getString(R.string.hayai_page_previews_failed),
                        actions,
                    )
                }
            }
        }
    }

    private fun seekTo(targetPage: Int) {
        loadJob?.cancel()
        loadJob = null
        loadGeneration++
        val generation = loadGeneration
        loading.isVisible = false
        val position = window.positionForPage(targetPage).takeIf { continuous && adapter.itemCount > 0 }
        if (position != null) {
            failedPage = null
            (binding.recycler.layoutManager as GridLayoutManager).scrollToPositionWithOffset(position, 0)
            navigation = requireNotNull(window.navigationForPage(targetPage))
            setTitle()
            activity?.invalidateOptionsMenu()
            binding.recycler.doOnNextLayout {
                if (generation == loadGeneration && viewAlive) {
                    rememberPosition()
                    loadAdjacent(0)
                }
            }
        } else {
            load(targetPage)
        }
    }

    private fun loadAdjacent(direction: Int) {
        if (!viewAlive || !continuous || loadJob != null || adapter.itemCount == 0) return
        val layout = binding.recycler.layoutManager as? GridLayoutManager ?: return
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        val threshold = layout.spanCount
        val previous = window.previousPage.takeIf { direction <= 0 && first <= threshold }
        val next = window.nextPage.takeIf { direction >= 0 && last >= adapter.itemCount - 1 - threshold }
        val target = previous ?: next ?: return
        if (target == failedPage) return
        load(target, adjacent = true, direction = if (previous != null) -1 else 1)
    }

    private fun currentAnchor(): ScrollAnchor? {
        if (!viewAlive) return null
        val layout = binding.recycler.layoutManager as? GridLayoutManager ?: return null
        val position = layout.findFirstVisibleItemPosition()
        val preview = adapter.itemAt(position) ?: return null
        val offset = layout.findViewByPosition(position)?.let { layout.getDecoratedTop(it) - binding.recycler.paddingTop } ?: 0
        return ScrollAnchor(preview.index, offset)
    }

    private fun rememberPosition() {
        val anchor = currentAnchor() ?: return
        args.putInt(ANCHOR_INDEX, anchor.index)
        args.putInt(ANCHOR_OFFSET, anchor.offset)
        val currentNavigation = window.navigationForImage(anchor.index) ?: return
        args.putInt(CURRENT_PAGE, currentNavigation.page)
        if (currentNavigation != navigation) {
            navigation = currentNavigation
            setTitle()
            activity?.invalidateOptionsMenu()
        }
    }

    private fun showGoToDialog() {
        val lastPage = navigation.totalPages?.takeIf { it > 1 } ?: return
        val context = activity ?: return
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val current = TextView(context).apply { text = navigation.page.toString() }
        val end = TextView(context).apply { text = lastPage.toString() }
        val slider = Slider(context).apply {
            valueFrom = 1f
            valueTo = lastPage.toFloat()
            stepSize = 1f
            value = navigation.page.toFloat()
            addOnChangeListener { _, value, _ -> current.text = value.roundToInt().toString() }
        }
        row.addView(current)
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(end)
        context.materialAlertDialog()
            .setTitle(R.string.hayai_go_to_preview_page)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ -> seekTo(slider.value.roundToInt()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPage(preview: SourcePagePreview) {
        val manga = manga ?: return
        val chapter = database.getChapters(manga).executeAsBlocking().minByOrNull { it.source_order } ?: return
        startActivity(ReaderLauncher.newIntent(requireNotNull(activity), manga, chapter, preview.index - 1))
    }

    private fun spanCount(view: View): Int =
        SyPagePreviewLayout.columns(view.resources.displayMetrics.widthPixels, view.resources.displayMetrics.density)

    private fun dp(value: Int): Int = ((activity?.resources ?: resources)?.displayMetrics?.density?.times(value) ?: value).toInt()

    private inner class PreviewAdapter(
        private val onClick: (SourcePagePreview) -> Unit,
    ) : RecyclerView.Adapter<PreviewHolder>() {
        private var manga: Manga? = null
        private var items = emptyList<SourcePagePreview>()

        init {
            setHasStableIds(true)
        }

        fun itemAt(position: Int): SourcePagePreview? = items.getOrNull(position)

        fun submit(manga: Manga, items: List<SourcePagePreview>) {
            val previous = this.items
            val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size
                override fun getNewListSize(): Int = items.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previous[oldItemPosition].index == items[newItemPosition].index
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previous[oldItemPosition] == items[newItemPosition]
            })
            this.manga = manga
            this.items = items
            changes.dispatchUpdatesTo(this)
        }

        fun clear() {
            items = emptyList()
            manga = null
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = items[position].index.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewHolder {
            val column = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val previewFrame = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(SyPagePreviewLayout.PREVIEW_WIDTH_DP),
                    dp(SyPagePreviewLayout.PREVIEW_HEIGHT_DP),
                )
                background = context.getDrawable(R.drawable.hayai_page_preview_shape)
                clipToOutline = true
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            previewFrame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val loading = LoadingIndicator(parent.context)
            previewFrame.addView(loading, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            column.addView(previewFrame)
            val label = TextView(parent.context).apply { gravity = Gravity.CENTER }
            column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return PreviewHolder(column, image, loading, label)
        }

        override fun onBindViewHolder(holder: PreviewHolder, position: Int) {
            val item = items[position]
            holder.bind(requireNotNull(manga), item)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun onViewRecycled(holder: PreviewHolder) = holder.recycle()
    }

    private inner class SyPreviewSpacingDecoration : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val spanCount = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: return
            val position = parent.getChildAdapterPosition(view).takeIf { it != RecyclerView.NO_POSITION } ?: return
            val spacing = dp(SyPagePreviewLayout.COLUMN_SPACING_DP)
            val column = position % spanCount
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            outRect.bottom = dp(SyPagePreviewLayout.ROW_SPACING_DP)
        }
    }

    private inner class PreviewHolder(
        itemView: View,
        private val image: ImageView,
        private val loading: LoadingIndicator,
        private val label: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        private var generation = 0
        private var bitmap: Bitmap? = null
        private var imageJob: Job? = null

        fun bind(manga: Manga, preview: SourcePagePreview) {
            recycle()
            label.text = preview.index.toString()
            image.contentDescription = image.context.getString(R.string.hayai_gallery_page, preview.index)
            loading.isVisible = true
            val expectedGeneration = generation
            imageJob = viewScope.launchIO {
                var loaded: Bitmap? = null
                try {
                    loaded = SourcePreviewBitmapDecoder.decode(previews.loadImage(manga, preview))
                    withUIContext {
                        if (expectedGeneration == generation && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            bitmap = loaded
                            image.setImageBitmap(loaded)
                            loaded = null
                            loading.isVisible = false
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    withUIContext {
                        if (expectedGeneration == generation) loading.isVisible = false
                    }
                } finally {
                    loaded?.takeUnless(Bitmap::isRecycled)?.recycle()
                }
            }
        }

        fun recycle() {
            generation++
            imageJob?.cancel()
            imageJob = null
            image.setImageDrawable(null)
            loading.isVisible = false
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            bitmap = null
        }
    }

    private data class ScrollAnchor(val index: Int, val offset: Int)

    private companion object {
        const val MANGA_ID = "manga_id"
        const val MENU_GO_TO = 1
        const val MENU_PREVIOUS = 2
        const val MENU_NEXT = 3
        const val MENU_PAGED = 4
        const val MENU_CONTINUOUS = 5
        const val MODE_GROUP = 1
        const val CURRENT_PAGE = "hayai_preview_page"
        const val CONTINUOUS = "hayai_preview_continuous"
        const val ANCHOR_INDEX = "hayai_preview_anchor"
        const val ANCHOR_OFFSET = "hayai_preview_offset"
    }
}
