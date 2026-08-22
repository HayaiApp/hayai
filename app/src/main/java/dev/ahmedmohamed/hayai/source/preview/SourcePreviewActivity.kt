package dev.ahmedmohamed.hayai.source.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.ui.EhSettingsActivity
import dev.ahmedmohamed.hayai.novel.reader.ReaderLauncher
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourcePreviewActivity : AppCompatActivity() {
    private val database: DatabaseHelper by lazy { Injekt.get() }
    private val previews: SourceDetailsPreviewRegistry by lazy { Injekt.get() }
    private lateinit var manga: Manga
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var signIn: Button
    private val adapter = PreviewAdapter(::openPage)
    private var page = 1
    private var totalPages: Int? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        manga = database.getManga(mangaId).executeAsBlocking() ?: run {
            finish()
            return
        }
        title = getString(R.string.hayai_page_previews)
        setContentView(createContent())
        setSupportActionBar(toolbar)
        page = savedInstanceState?.getInt(STATE_PAGE, 1) ?: 1
        load(page)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, page)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        if (::recycler.isInitialized) {
            for (index in 0 until recycler.childCount) {
                (recycler.getChildViewHolder(recycler.getChildAt(index)) as? PreviewHolder)?.recycle()
            }
            recycler.adapter = null
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_GO_TO, Menu.NONE, R.string.hayai_go_to_preview_page).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_RETRY, Menu.NONE, R.string.hayai_retry).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_GO_TO -> {
            showGoToDialog()
            true
        }
        MENU_RETRY -> {
            load(page, forceRefresh = true)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.hayai_page_previews)
            setNavigationIcon(R.drawable.ic_arrow_back_24dp)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, matchWrap())
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, centered(dp(48)))
        message = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(message, matchWrap())
        signIn = Button(this).apply {
            text = getString(R.string.hayai_open_ehentai_settings)
            setOnClickListener { startActivity(Intent(this@SourcePreviewActivity, EhSettingsActivity::class.java)) }
        }
        root.addView(signIn, centered(ViewGroup.LayoutParams.WRAP_CONTENT))
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SourcePreviewActivity, spanCount())
            adapter = this@SourcePreviewActivity.adapter
        }
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            previous = Button(context).apply {
                text = getString(R.string.hayai_previous_previews)
                setOnClickListener { load(page - 1) }
            }
            addView(previous)
            next = Button(context).apply {
                text = getString(R.string.hayai_next_previews)
                setOnClickListener { load(page + 1) }
            }
            addView(next)
        }
        root.addView(navigation, matchWrap())
        return root
    }

    private fun load(targetPage: Int, forceRefresh: Boolean = false) {
        if (targetPage < 1 || totalPages?.let { targetPage > it } == true) return
        loadJob?.cancel()
        progress.isVisible = true
        message.isVisible = false
        signIn.isVisible = false
        previous.isEnabled = false
        next.isEnabled = false
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { previews.load(manga, targetPage, okhttp3.CacheControl.FORCE_NETWORK.takeIf { forceRefresh }) }
            }
            progress.isVisible = false
            result.onSuccess { loaded ->
                page = loaded.page
                totalPages = loaded.totalPages
                title = totalPages?.let { getString(R.string.hayai_page_previews_position, page, it) }
                    ?: getString(R.string.hayai_page_previews_position_unknown, page)
                adapter.submit(manga, loaded.previews)
                recycler.scrollToPosition(0)
                message.isVisible = loaded.previews.isEmpty()
                message.text = getString(R.string.hayai_no_page_previews)
                previous.isEnabled = page > 1
                next.isEnabled = loaded.hasNextPage
            }.onFailure { error ->
                adapter.submit(manga, emptyList())
                message.isVisible = true
                message.text = error.message ?: getString(R.string.hayai_page_previews_failed)
                previous.isEnabled = page > 1
                signIn.isVisible = manga.source == EhSite.ExHentai.sourceId
            }
        }
    }

    private fun showGoToDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(page.toString())
            selectAll()
            hint = totalPages?.let { "1-$it" } ?: getString(R.string.hayai_preview_page_number)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hayai_go_to_preview_page)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().toIntOrNull()?.let(::load) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPage(preview: SourcePagePreview) {
        val chapter = database.getChapters(manga).executeAsBlocking().minByOrNull { it.source_order } ?: return
        startActivity(ReaderLauncher.newIntent(this, manga, chapter, preview.index - 1))
    }

    private fun spanCount(): Int = (resources.displayMetrics.widthPixels / dp(120)).coerceAtLeast(2)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun centered(size: Int) = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL }

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

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewHolder {
            val column = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(8))
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            }
            column.addView(image)
            val label = TextView(parent.context).apply { gravity = Gravity.CENTER }
            column.addView(label, matchWrap())
            return PreviewHolder(column, image, label)
        }

        override fun onBindViewHolder(holder: PreviewHolder, position: Int) {
            val item = items[position]
            holder.bind(checkNotNull(manga), item)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun onViewRecycled(holder: PreviewHolder) = holder.recycle()
    }

    private inner class PreviewHolder(
        itemView: View,
        private val image: ImageView,
        private val label: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        private var job: Job? = null
        private var bitmap: Bitmap? = null

        fun bind(manga: Manga, preview: SourcePagePreview) {
            recycle()
            label.text = preview.index.toString()
            image.contentDescription = getString(R.string.hayai_gallery_page, preview.index)
            image.setImageDrawable(null)
            job = lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { SourcePreviewBitmapDecoder.decode(previews.loadImage(manga, preview)) }.getOrNull()
                }
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    bitmap = loaded
                    image.setImageBitmap(loaded)
                } else {
                    loaded?.recycle()
                }
            }
        }

        fun recycle() {
            job?.cancel()
            job = null
            image.setImageDrawable(null)
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            bitmap = null
        }
    }

    companion object {
        private const val EXTRA_MANGA_ID = "manga_id"
        private const val STATE_PAGE = "page"
        private const val MENU_GO_TO = 1
        private const val MENU_RETRY = 2

        fun newIntent(context: Context, mangaId: Long): Intent =
            Intent(context, SourcePreviewActivity::class.java).putExtra(EXTRA_MANGA_ID, mangaId)
    }
}
