package dev.ahmedmohamed.hayai.source.metadata

import android.content.ClipData
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.loadingindicator.LoadingIndicator
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.databinding.SubDebugControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.util.system.clipboardManager
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.widget.EmptyView
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceMetadataController(
    bundle: Bundle? = null,
) : BaseController<SubDebugControllerBinding>(bundle) {
    private val database: DatabaseHelper = Injekt.get()
    private val metadata: SourceMetadataProviderRegistry = Injekt.get()
    private val mangaId = args.getLong(MANGA_ID)
    private val manga by lazy { database.getManga(mangaId).executeAsBlocking() }
    private val adapter = MetadataAdapter()
    private lateinit var loading: LoadingIndicator
    private lateinit var emptyView: EmptyView
    private var loadGeneration = 0

    constructor(mangaId: Long) : this(Bundle().apply { putLong(MANGA_ID, mangaId) })

    override fun getTitle(): String? = manga?.title ?: resources?.getString(R.string.hayai_source_metadata_title)

    override fun createBinding(inflater: LayoutInflater) = SubDebugControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        scrollViewWith(binding.recycler, padBottom = true)
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.adapter = adapter

        val root = binding.root as FrameLayout
        loading = LoadingIndicator(view.context)
        root.addView(loading, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        emptyView = EmptyView(view.context).apply { hide() }
        root.addView(
            emptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        if (manga == null) {
            loading.isVisible = false
            emptyView.show(R.drawable.ic_search_off_24dp, R.string.no_results_found)
        } else {
            load()
        }
    }

    override fun onDestroyView(view: View) {
        loadGeneration++
        binding.recycler.adapter = null
        adapter.submit(emptyList())
        super.onDestroyView(view)
    }

    private fun load() {
        val manga = manga ?: return
        val generation = ++loadGeneration
        loading.isVisible = true
        binding.recycler.isVisible = false
        emptyView.hide()
        viewScope.launchIO {
            val result = runCatching { metadata.load(manga) }
            withUIContext {
                if (generation != loadGeneration || !isBindingInitialized) return@withUIContext
                loading.isVisible = false
                result.onSuccess { document ->
                    val rows = SourceMetadataUi.fullRows(requireNotNull(activity), document)
                    adapter.submit(rows)
                    binding.recycler.isVisible = rows.isNotEmpty()
                    if (rows.isEmpty()) emptyView.show(R.drawable.ic_search_off_24dp, R.string.no_results_found)
                }.onFailure {
                    adapter.submit(emptyList())
                    emptyView.show(R.drawable.ic_search_off_24dp, R.string.hayai_source_metadata_failed)
                }
            }
        }
    }

    private inner class MetadataAdapter : RecyclerView.Adapter<MetadataHolder>() {
        private var rows = emptyList<SourceMetadataUi.Row>()

        fun submit(rows: List<SourceMetadataUi.Row>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MetadataHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(8), dp(8), dp(8))
            }
            val title = TextView(parent.context).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) }
            val value = TextView(parent.context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                alpha = 0.7f
                setTextIsSelectable(true)
            }
            row.addView(title, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            return MetadataHolder(row, title, value)
        }

        override fun onBindViewHolder(holder: MetadataHolder, position: Int) = holder.bind(rows[position])
    }

    private inner class MetadataHolder(
        itemView: View,
        private val title: TextView,
        private val value: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(row: SourceMetadataUi.Row) {
            title.text = row.title
            value.text = row.value
            itemView.setOnLongClickListener {
                itemView.context.clipboardManager.setPrimaryClip(ClipData.newPlainText(row.title, row.value))
                true
            }
        }
    }

    private fun dp(value: Int): Int = ((resources?.displayMetrics?.density ?: 1f) * value).toInt()

    private companion object {
        const val MANGA_ID = "manga_id"
    }
}
