package dev.ahmedmohamed.hayai.source.metadata

import android.content.Context
import android.content.ClipData
import android.graphics.Color
import android.graphics.Typeface
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.clipboardManager
import java.text.DateFormat
import java.util.Date

object SourceMetadataUi {
    data class Row(val title: String, val value: String)

    fun renderSummary(
        container: LinearLayout,
        document: SourceMetadataDocument,
        onMoreInfo: () -> Unit,
        onSearch: (String) -> Unit,
    ) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(container.context, 16), dp(container.context, 4), dp(container.context, 16), dp(container.context, 8))

        val primary = LinearLayout(container.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        document.value(SourceMetadataKey.Category)?.let { primary.addView(typeBadge(container.context, it)) }
        document.value(SourceMetadataKey.Rating)?.toFloatOrNull()?.let { rating ->
            primary.addView(
                RatingBar(container.context, null, android.R.attr.ratingBarStyleSmall).apply {
                    numStars = 5
                    stepSize = 0.5f
                    this.rating = rating.coerceIn(0f, 5f)
                    setIsIndicator(true)
                },
            )
        }
        container.addView(primary, matchWrap())

        val summaryKeys = listOf(
            SourceMetadataKey.Uploader,
            SourceMetadataKey.Language,
            SourceMetadataKey.Pages,
            SourceMetadataKey.FileSize,
            SourceMetadataKey.Favorites,
            SourceMetadataKey.Posted,
        )
        summaryKeys.mapNotNull { key -> document.value(key)?.let { key to formatValue(container.context, key, it) } }
            .chunked(2)
            .forEach { fields ->
                container.addView(
                    LinearLayout(container.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        fields.forEach { (key, value) ->
                            addView(
                                metadataText(context, key, value).apply {
                                    if (key == SourceMetadataKey.Uploader) {
                                        setOnClickListener { onSearch("uploader:\"$value\"") }
                                    }
                                },
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                            )
                        }
                    },
                    matchWrap(),
                )
            }

        if (document.tags.isNotEmpty()) {
            container.addView(
                ChipGroup(container.context).apply {
                    isSingleLine = false
                    document.tags.take(INLINE_TAG_LIMIT).forEach { tag ->
                        addView(
                            Chip(context).apply {
                                text = tag.name
                                isCheckable = false
                                setOnClickListener { onSearch(tag.searchableValue) }
                                setOnLongClickListener {
                                    context.copyMetadata(tag.searchableValue, tag.searchableValue)
                                    true
                                }
                            },
                        )
                    }
                },
                matchWrap(),
            )
        }

        container.addView(
            MaterialButton(container.context).apply {
                setText(R.string.hayai_source_metadata_more_info)
                setIconResource(R.drawable.ic_info_24dp)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onMoreInfo() }
            },
            matchWrap(),
        )
    }

    fun renderFull(container: LinearLayout, document: SourceMetadataDocument) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        val context = container.context
        document.titles.forEachIndexed { index, title ->
            addField(container, if (index == 0) SourceMetadataKey.EnglishTitle else SourceMetadataKey.AlternativeTitle, title)
        }
        document.fields.forEach { field -> addField(container, field.key, formatValue(context, field.key, field.value)) }
        if (document.tags.isNotEmpty()) {
            container.addView(sectionTitle(context, R.string.tags), matchWrap())
            document.tags.groupBy { it.namespace }.forEach { (namespace, tags) ->
                namespace?.let { container.addView(sectionTitle(context, it), matchWrap()) }
                container.addView(
                    TextView(context).apply {
                        val value = tags.joinToString { it.name }
                        text = value
                        setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 12))
                        setTextIsSelectable(true)
                        setOnLongClickListener {
                            val label = namespace ?: context.getString(R.string.tags)
                            context.copyMetadata(label, value)
                            true
                        }
                    },
                    matchWrap(),
                )
            }
        }
    }

    fun fullRows(context: Context, document: SourceMetadataDocument): List<Row> = buildList {
        document.titles.forEachIndexed { index, title ->
            val key = if (index == 0) SourceMetadataKey.EnglishTitle else SourceMetadataKey.AlternativeTitle
            add(Row(context.getString(label(key)), title))
        }
        document.fields.forEach { field ->
            add(Row(context.getString(label(field.key)), formatValue(context, field.key, field.value)))
        }
        document.tags.groupBy { it.namespace }.forEach { (namespace, tags) ->
            add(Row(namespace ?: context.getString(R.string.tags), tags.joinToString { it.name }))
        }
    }

    fun typeColor(type: String): Int = when (type.trim().lowercase()) {
        "doujinshi" -> Color.rgb(244, 67, 54)
        "manga" -> Color.rgb(255, 152, 0)
        "artistcg", "artist cg", "artist-cg" -> Color.rgb(251, 192, 45)
        "gamecg", "game cg", "game-cg" -> Color.rgb(76, 175, 80)
        "western" -> Color.rgb(139, 195, 74)
        "non-h", "non-hentai" -> Color.rgb(33, 150, 243)
        "imageset", "image set" -> Color.rgb(63, 81, 181)
        "cosplay" -> Color.rgb(156, 39, 176)
        "asianporn", "asian porn" -> Color.rgb(149, 117, 205)
        else -> Color.rgb(240, 98, 146)
    }

    fun typeLabel(context: Context, type: String): String {
        val label = when (type.trim().lowercase()) {
            "doujinshi" -> R.string.hayai_source_type_doujinshi
            "manga" -> R.string.hayai_source_type_manga
            "artistcg", "artist cg", "artist-cg" -> R.string.hayai_source_type_artist_cg
            "gamecg", "game cg", "game-cg" -> R.string.hayai_source_type_game_cg
            "western" -> R.string.hayai_source_type_western
            "non-h", "non-hentai" -> R.string.hayai_source_type_non_h
            "imageset", "image set" -> R.string.hayai_source_type_image_set
            "cosplay" -> R.string.hayai_source_type_cosplay
            "asianporn", "asian porn" -> R.string.hayai_source_type_asian_porn
            "misc" -> R.string.hayai_source_type_misc
            else -> return type
        }
        return context.getString(label)
    }

    fun contrastingTextColor(background: Int): Int =
        if (ColorUtils.calculateContrast(Color.WHITE, background) >= ColorUtils.calculateContrast(Color.BLACK, background)) Color.WHITE else Color.BLACK

    @StringRes
    fun label(key: SourceMetadataKey): Int = when (key) {
        SourceMetadataKey.AlternativeTitle -> R.string.hayai_enhanced_label_alternative_title
        SourceMetadataKey.AlternativeTitles -> R.string.hayai_enhanced_label_alternative_titles
        SourceMetadataKey.ArchiveType -> R.string.hayai_enhanced_label_archive_type
        SourceMetadataKey.Artist -> R.string.artist
        SourceMetadataKey.BaseUrl -> R.string.hayai_source_metadata_base_url
        SourceMetadataKey.Category -> R.string.category
        SourceMetadataKey.Description -> R.string.description
        SourceMetadataKey.EnglishTitle -> R.string.hayai_enhanced_label_english_title
        SourceMetadataKey.Favorites -> R.string.hayai_enhanced_label_favorites
        SourceMetadataKey.File -> R.string.hayai_enhanced_label_file
        SourceMetadataKey.FileSize -> R.string.hayai_enhanced_label_file_size
        SourceMetadataKey.GalleryId -> R.string.hayai_enhanced_label_id
        SourceMetadataKey.JapaneseTitle -> R.string.hayai_enhanced_label_japanese_title
        SourceMetadataKey.IsExHentai -> R.string.hayai_source_metadata_is_exhentai
        SourceMetadataKey.Language -> R.string.language
        SourceMetadataKey.Length -> R.string.length
        SourceMetadataKey.MediaId -> R.string.hayai_enhanced_label_media_id
        SourceMetadataKey.Pages -> R.string.hayai_enhanced_label_pages
        SourceMetadataKey.Posted -> R.string.hayai_enhanced_label_posted
        SourceMetadataKey.Path -> R.string.hayai_source_metadata_path
        SourceMetadataKey.Parent -> R.string.hayai_source_metadata_parent
        SourceMetadataKey.Rating -> R.string.hayai_enhanced_label_rating
        SourceMetadataKey.RatingCount -> R.string.hayai_enhanced_label_rating_count
        SourceMetadataKey.Scanlator -> R.string.hayai_enhanced_label_scanlator
        SourceMetadataKey.ShortTitle -> R.string.hayai_enhanced_label_short_title
        SourceMetadataKey.Summary -> R.string.hayai_enhanced_label_summary
        SourceMetadataKey.Translated -> R.string.hayai_source_metadata_translated
        SourceMetadataKey.ThumbnailUrl -> R.string.hayai_source_metadata_thumbnail_url
        SourceMetadataKey.Token -> R.string.hayai_source_metadata_token
        SourceMetadataKey.Uploader -> R.string.hayai_enhanced_label_uploader
        SourceMetadataKey.Url -> R.string.hayai_source_metadata_url
        SourceMetadataKey.Visibility -> R.string.hayai_source_metadata_visibility
    }

    private fun typeBadge(context: Context, value: String): MaterialCardView {
        val background = typeColor(value)
        return MaterialCardView(context).apply {
            radius = dp(context, 6).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(background)
            addView(
                TextView(context).apply {
                    text = typeLabel(context, value)
                    setTextColor(contrastingTextColor(background))
                    setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
                },
            )
        }
    }

    private fun addField(container: LinearLayout, key: SourceMetadataKey, value: String) {
        val context = container.context
        container.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
                addView(sectionTitle(context, label(key)), LinearLayout.LayoutParams(dp(context, 140), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply { text = value }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnLongClickListener {
                    context.copyMetadata(context.getString(label(key)), value)
                    true
                }
            },
            matchWrap(),
        )
    }

    private fun metadataText(context: Context, key: SourceMetadataKey, value: String): TextView =
        TextView(context).apply {
            text = context.getString(R.string.hayai_source_metadata_field, context.getString(label(key)), value)
            setPadding(0, dp(context, 4), dp(context, 8), dp(context, 4))
            setOnLongClickListener {
                context.copyMetadata(context.getString(label(key)), value)
                true
            }
        }

    private fun sectionTitle(context: Context, @StringRes title: Int): TextView = sectionTitle(context, context.getString(title))

    private fun sectionTitle(context: Context, title: String): TextView =
        TextView(context).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun formatValue(context: Context, key: SourceMetadataKey, value: String): String = when (key) {
        SourceMetadataKey.FileSize -> value.toLongOrNull()?.let { Formatter.formatShortFileSize(context, it) } ?: value
        SourceMetadataKey.Posted -> value.toLongOrNull()?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: value
        SourceMetadataKey.Translated,
        SourceMetadataKey.IsExHentai,
        -> value.toBooleanStrictOrNull()?.let {
            context.getString(if (it) R.string.hayai_source_metadata_yes else R.string.hayai_source_metadata_no)
        } ?: value
        else -> value
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private const val INLINE_TAG_LIMIT = 12
}

private fun Context.copyMetadata(label: String, value: String) {
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, value))
}
