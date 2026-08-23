package dev.ahmedmohamed.hayai.source.details

import android.graphics.Bitmap
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.source.metadata.SourceMetadataProviderRegistry
import dev.ahmedmohamed.hayai.source.metadata.SourceMetadataUi
import dev.ahmedmohamed.hayai.source.preview.SourceDetailsPreviewRegistry
import dev.ahmedmohamed.hayai.source.preview.SourcePagePreview
import dev.ahmedmohamed.hayai.source.preview.SourcePreviewBitmapDecoder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.widget.EmptyView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

data class SourceDetailsSlots(
    val previewRoot: View,
    val previewSection: View,
    val previewContent: LinearLayout,
    val metadataSection: View,
    val metadataContent: LinearLayout,
)

data class SourceDetailsActions(
    val onMoreInfo: () -> Unit,
    val onSearch: (String) -> Unit,
    val onMorePreviews: () -> Unit,
    val onPreview: (SourcePagePreview) -> Unit,
)

class SourceDetailsHost(
    private val previews: SourceDetailsPreviewRegistry,
    private val metadata: SourceMetadataProviderRegistry,
    private val preferences: HayaiPreferences,
) {
    private var previewJob: Job? = null
    private var metadataJob: Job? = null
    private val imageJobs = mutableListOf<Job>()
    private val bitmaps = mutableListOf<Bitmap>()
    private var identity: String? = null

    fun bind(
        scope: CoroutineScope,
        manga: Manga,
        slots: SourceDetailsSlots,
        actions: SourceDetailsActions,
    ) {
        val ownsPreviews = previews.owns(manga)
        val ownsMetadata = metadata.owns(manga)
        if (!ownsPreviews && !ownsMetadata) {
            clear(slots)
            return
        }
        identity = "${manga.source}:${manga.url}"
        if (ownsMetadata) loadMetadata(scope, manga, slots, actions) else slots.metadataSection.visibility = View.GONE
        if (ownsPreviews) {
            slots.previewRoot.visibility = View.VISIBLE
            loadPreviews(scope, manga, slots, actions)
        } else {
            slots.previewRoot.visibility = View.GONE
            slots.previewSection.visibility = View.GONE
        }
    }

    fun clear(slots: SourceDetailsSlots? = null) {
        identity = null
        previewJob?.cancel()
        metadataJob?.cancel()
        previewJob = null
        metadataJob = null
        releaseImages()
        slots?.apply {
            previewContent.removeAllViews()
            metadataContent.removeAllViews()
            previewContent.tag = null
            metadataContent.tag = null
            previewRoot.visibility = View.GONE
            metadataSection.visibility = View.GONE
        }
    }

    private fun loadMetadata(
        scope: CoroutineScope,
        manga: Manga,
        slots: SourceDetailsSlots,
        actions: SourceDetailsActions,
    ) {
        val expected = identity ?: return
        if (slots.metadataContent.tag == expected) return
        slots.metadataContent.tag = expected
        slots.metadataContent.removeAllViews()
        slots.metadataSection.visibility = View.VISIBLE
        slots.metadataContent.addView(ProgressBar(slots.metadataContent.context).apply { isIndeterminate = true })
        metadataJob?.cancel()
        metadataJob = scope.launchIO {
            val result = runCatching { metadata.load(manga) }
            withUIContext {
                if (identity != expected || slots.metadataContent.tag != expected) return@withUIContext
                result.onSuccess { document ->
                    SourceMetadataUi.renderSummary(slots.metadataContent, document, actions.onMoreInfo, actions.onSearch)
                    slots.metadataSection.visibility = View.VISIBLE
                }.onFailure {
                    slots.metadataContent.removeAllViews()
                    slots.metadataSection.visibility = View.GONE
                }
            }
        }
    }

    private fun loadPreviews(
        scope: CoroutineScope,
        manga: Manga,
        slots: SourceDetailsSlots,
        actions: SourceDetailsActions,
    ) {
        val expected = "${identity ?: return}:1"
        if (slots.previewContent.tag == expected) return
        slots.previewContent.tag = expected
        slots.previewContent.removeAllViews()
        releaseImages()
        slots.previewSection.visibility = View.VISIBLE
        slots.previewContent.addView(ProgressBar(slots.previewContent.context).apply { isIndeterminate = true })
        previewJob?.cancel()
        previewJob = scope.launchIO {
            val result = runCatching { previews.load(manga, 1) }
            withUIContext {
                if (identity == null || slots.previewContent.tag != expected) return@withUIContext
                slots.previewContent.removeAllViews()
                result.onSuccess { loaded ->
                    renderPreviews(scope, manga, loaded.previews, slots, actions)
                }.onFailure { error ->
                    slots.previewContent.addView(
                        EmptyView(slots.previewContent.context).apply {
                            show(
                                R.drawable.ic_search_off_24dp,
                                error.message ?: context.getString(R.string.hayai_page_previews_failed),
                                listOf(
                                    EmptyView.Action(R.string.retry) {
                                        slots.previewContent.tag = null
                                        loadPreviews(scope, manga, slots, actions)
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun renderPreviews(
        scope: CoroutineScope,
        manga: Manga,
        items: List<SourcePagePreview>,
        slots: SourceDetailsSlots,
        actions: SourceDetailsActions,
    ) {
        val rows = preferences.pagePreviewRows.get().coerceIn(0, 10)
        if (rows == 0) {
            slots.previewRoot.visibility = View.GONE
            return
        }
        val density = slots.previewContent.resources.displayMetrics.density
        val columns = previewColumns(slots.previewContent)
        val visible = items.take(rows * columns)
        visible.chunked(columns).forEach { rowItems ->
            slots.previewContent.addView(
                LinearLayout(slots.previewContent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (8 * density).toInt())
                    rowItems.forEachIndexed { index, preview ->
                        addView(
                            createPreviewCell(scope, this, manga, preview, density, actions),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (index > 0) marginStart = (16 * density).toInt()
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        if (visible.isEmpty()) slots.previewContent.addView(TextView(slots.previewContent.context).apply { setText(R.string.no_results_found) })
        slots.previewContent.addView(
            (LayoutInflater.from(slots.previewContent.context).inflate(R.layout.material_text_button, slots.previewContent, false) as MaterialButton).apply {
                setText(R.string.hayai_more_previews)
                setOnClickListener { actions.onMorePreviews() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        slots.previewRoot.visibility = View.VISIBLE
    }

    private fun createPreviewCell(
        scope: CoroutineScope,
        parent: LinearLayout,
        manga: Manga,
        preview: SourcePagePreview,
        density: Float,
        actions: SourceDetailsActions,
    ): View {
        val cell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = context.getString(R.string.hayai_gallery_page, preview.index)
            setOnClickListener { actions.onPreview(preview) }
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        cell.addView(image, LinearLayout.LayoutParams((120 * density).toInt(), (200 * density).toInt()))
        cell.addView(TextView(parent.context).apply { text = preview.index.toString() })
        val expected = identity
        imageJobs += scope.launchIO {
            val bitmap = runCatching { SourcePreviewBitmapDecoder.decode(previews.loadImage(manga, preview)) }.getOrNull()
            withUIContext {
                if (identity == expected && bitmap != null) {
                    bitmaps += bitmap
                    image.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
        return cell
    }

    private fun previewColumns(container: View): Int {
        val width = container.width.takeIf { it > 0 } ?: container.resources.displayMetrics.widthPixels
        val density = container.resources.displayMetrics.density
        val horizontalPadding = (16 * density).toInt()
        val minimumCell = (120 * density).toInt()
        val spacing = (16 * density).toInt()
        return ((width - horizontalPadding + spacing) / (minimumCell + spacing)).coerceAtLeast(1)
    }

    private fun releaseImages() {
        imageJobs.forEach(Job::cancel)
        imageJobs.clear()
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
    }
}
