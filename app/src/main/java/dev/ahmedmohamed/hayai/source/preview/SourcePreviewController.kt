package dev.ahmedmohamed.hayai.source.preview

import android.graphics.Bitmap
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
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.slider.Slider
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.ui.EhSettingsActivity
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
import eu.kanade.tachiyomi.widget.EmptyView
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
    private var page = 1
    private var totalPages: Int? = null
    private var loadGeneration = 0

    constructor(mangaId: Long) : this(Bundle().apply { putLong(MANGA_ID, mangaId) })

    override fun getTitle(): String? = resources?.getString(R.string.hayai_page_previews)

    override fun createBinding(inflater: LayoutInflater) = SubDebugControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        scrollViewWith(binding.recycler, padBottom = true)
        binding.recycler.layoutManager = GridLayoutManager(view.context, spanCount(view))
        binding.recycler.adapter = adapter
        binding.recycler.setPadding(dp(8), dp(8), dp(8), dp(8))

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
            load(page)
        }
    }

    override fun onDestroyView(view: View) {
        loadGeneration++
        binding.recycler.adapter = null
        adapter.clear()
        super.onDestroyView(view)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if ((totalPages ?: 0) > 1) {
            menu.add(Menu.NONE, MENU_GO_TO, Menu.NONE, R.string.hayai_go_to_preview_page)
                .setIcon(R.drawable.ic_page_next_outline_24dp)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_GO_TO -> {
            showGoToDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun load(targetPage: Int, forceRefresh: Boolean = false) {
        val manga = manga ?: return
        val knownTotal = totalPages
        if (targetPage < 1 || knownTotal != null && targetPage > knownTotal) return
        val generation = ++loadGeneration
        loading.isVisible = true
        emptyView.hide()
        binding.recycler.isVisible = false
        viewScope.launchIO {
            val result = runCatching {
                previews.load(manga, targetPage, CacheControl.FORCE_NETWORK.takeIf { forceRefresh })
            }
            withUIContext {
                if (generation != loadGeneration || !isBindingInitialized) return@withUIContext
                loading.isVisible = false
                result.onSuccess { loaded ->
                    page = loaded.page
                    totalPages = loaded.totalPages
                    adapter.submit(manga, loaded.previews)
                    binding.recycler.scrollToPosition(0)
                    binding.recycler.isVisible = loaded.previews.isNotEmpty()
                    if (loaded.previews.isEmpty()) {
                        emptyView.show(R.drawable.ic_search_off_24dp, R.string.hayai_no_page_previews)
                    }
                    activity?.invalidateOptionsMenu()
                }.onFailure { error ->
                    adapter.clear()
                    val actions = buildList {
                        add(EmptyView.Action(R.string.retry) { load(targetPage, forceRefresh = true) })
                        if (manga.source == EhSite.ExHentai.sourceId) {
                            add(EmptyView.Action(R.string.hayai_open_ehentai_settings) { startActivity(EhSettingsActivity.newIntent(emptyView.context)) })
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

    private fun showGoToDialog() {
        val lastPage = totalPages?.takeIf { it > 1 } ?: return
        val context = activity ?: return
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val current = TextView(context).apply { text = page.toString() }
        val end = TextView(context).apply { text = lastPage.toString() }
        val slider = Slider(context).apply {
            valueFrom = 1f
            valueTo = lastPage.toFloat()
            stepSize = 1f
            value = page.toFloat()
            addOnChangeListener { _, value, _ -> current.text = value.roundToInt().toString() }
        }
        row.addView(current)
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(end)
        context.materialAlertDialog()
            .setTitle(R.string.hayai_go_to_preview_page)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ -> load(slider.value.roundToInt()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPage(preview: SourcePagePreview) {
        val manga = manga ?: return
        val chapter = database.getChapters(manga).executeAsBlocking().minByOrNull { it.source_order } ?: return
        startActivity(ReaderLauncher.newIntent(requireNotNull(activity), manga, chapter, preview.index - 1))
    }

    private fun spanCount(view: View): Int = (view.resources.displayMetrics.widthPixels / dp(120)).coerceAtLeast(2)

    private fun dp(value: Int): Int = ((activity?.resources ?: resources)?.displayMetrics?.density?.times(value) ?: value).toInt()

    private inner class PreviewAdapter(
        private val onClick: (SourcePagePreview) -> Unit,
    ) : RecyclerView.Adapter<PreviewHolder>() {
        private var manga: Manga? = null
        private var items = emptyList<SourcePagePreview>()

        fun submit(manga: Manga, items: List<SourcePagePreview>) {
            this.manga = manga
            this.items = items
            notifyDataSetChanged()
        }

        fun clear() {
            items = emptyList()
            manga = null
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewHolder {
            val column = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(8))
            }
            val previewFrame = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(120), dp(200))
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

    private inner class PreviewHolder(
        itemView: View,
        private val image: ImageView,
        private val loading: LoadingIndicator,
        private val label: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        private var generation = 0
        private var bitmap: Bitmap? = null

        fun bind(manga: Manga, preview: SourcePagePreview) {
            recycle()
            label.text = preview.index.toString()
            image.contentDescription = image.context.getString(R.string.hayai_gallery_page, preview.index)
            loading.isVisible = true
            val expectedGeneration = generation
            viewScope.launchIO {
                val loaded = runCatching { SourcePreviewBitmapDecoder.decode(previews.loadImage(manga, preview)) }.getOrNull()
                withUIContext {
                    if (expectedGeneration == generation && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        bitmap = loaded
                        image.setImageBitmap(loaded)
                        loading.isVisible = false
                    } else {
                        loaded?.recycle()
                    }
                }
            }
        }

        fun recycle() {
            generation++
            image.setImageDrawable(null)
            loading.isVisible = false
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            bitmap = null
        }
    }

    private companion object {
        const val MANGA_ID = "manga_id"
        const val MENU_GO_TO = 1
    }
}
