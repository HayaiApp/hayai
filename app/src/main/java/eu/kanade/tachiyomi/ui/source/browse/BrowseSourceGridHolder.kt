package eu.kanade.tachiyomi.ui.source.browse

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.google.android.material.R as materialR
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.databinding.BrowseSourceGridItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import exh.source.isEhBasedManga
import exh.util.SourceTagsUtil
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.util.coil.loadManga
import yokai.util.lang.getString

/**
 * View-based source-browse grid holder. Mirrors [eu.kanade.tachiyomi.ui.library.LibraryGridHolder]
 * (pure XML/ViewBinding + ImageView + Coil) so each cell costs ~2-5 ms instead of the per-cell
 * ComposeView first composition the previous holder paid. The three badges (Novel / EH category /
 * In Library) are rendered as a single overlay strip; favourite state dims the cover.
 */
class BrowseSourceGridHolder(
    private val view: View,
    adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    private val compact: Boolean,
    showOutline: Boolean,
) : BrowseSourceHolder(view, adapter) {

    private val binding = BrowseSourceGridItemBinding.bind(view)

    init {
        binding.card.strokeWidth = if (showOutline) 1.dpToPx else 0
        if (compact) {
            binding.title.isVisible = false
        } else {
            binding.compactTitle.isVisible = false
            binding.gradient.isVisible = false
        }
    }

    override fun onSetValues(manga: Manga) {
        val title = manga.title
        if (compact) {
            binding.compactTitle.text = title
        } else {
            binding.title.text = title
        }

        // Favourite/in-library state dims the cover (mirrors Compose isSelected alpha).
        binding.coverThumbnail.alpha = if (manga.favorite) 0.34f else 1.0f

        // Badge order matches the Compose cell: Novel, then EH category, then In Library.
        val segments = buildList {
            if (manga.isNovel()) {
                add(
                    BrowseBadgeStrip.Segment(
                        text = view.context.getString(MR.strings.novel),
                        backgroundColor = view.context.getResourceColor(materialR.attr.colorTertiary),
                        textColor = view.context.getResourceColor(materialR.attr.colorOnTertiary),
                    ),
                )
            }
            if (manga.isEhBasedManga()) {
                val token = manga.genre?.substringBefore(',')?.trim()?.lowercase()
                SourceTagsUtil.getEhCategoryDisplay(token)?.let { (genreColor, label) ->
                    add(
                        BrowseBadgeStrip.Segment(
                            text = view.context.getString(label),
                            backgroundColor = genreColor.color,
                            textColor = Color.WHITE,
                        ),
                    )
                }
            }
            if (manga.favorite) {
                add(
                    BrowseBadgeStrip.Segment(
                        text = view.context.getString(MR.strings.in_library),
                        backgroundColor = view.context.getResourceColor(materialR.attr.colorSecondary),
                        textColor = view.context.getResourceColor(materialR.attr.colorOnSecondary),
                    ),
                )
            }
        }
        binding.badgeStrip.setSegments(segments)

        setImage(manga)
    }

    override fun setImage(manga: Manga) {
        if ((view.context as? Activity)?.isDestroyed == true) return
        binding.coverThumbnail.dispose()
        // dispose() leaves the prior drawable in place; CoverViewTarget.onError swaps in a
        // CENTER-scaled broken-image vector, so a recycled cell would briefly flash that stale
        // glyph before the new request lands. Reset scale + clear the drawable to the placeholder.
        binding.coverThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        binding.coverThumbnail.setImageDrawable(null)
        // loadManga applies the singleton's maxBitmapSize(2048) + precision(INEXACT) defaults, so
        // covers decode at view bounds instead of over-decoding. Coil shows the XML placeholder
        // background until the cover (or error) lands; matches the Compose cell behaviour.
        binding.coverThumbnail.loadManga(manga.cover())
    }
}
