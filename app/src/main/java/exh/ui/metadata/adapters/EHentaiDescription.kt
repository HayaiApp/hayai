package exh.ui.metadata.adapters

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.ui.metadata.GenreChip
import exh.ui.metadata.MetadataUIUtil
import exh.ui.metadata.getRatingColor
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EHentaiDescription(
    meta: EHentaiSearchMetadata,
    sourceId: Long,
    isExpanded: Boolean,
    openMetadataViewer: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val isDark = isSystemInDarkTheme()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Row 1: Genre chip on left, single-line rating on right.
        // Previous version stacked stars over "%.2f" as a 2-line right column, which made
        // Row 1 taller than the genre chip and produced visible vertical slack against
        // the next row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val genreInfo = meta.genre?.let { MetadataUIUtil.getGenreAndColour(it, isDark) }
            val genreText = genreInfo?.second ?: meta.genre ?: "Unknown"
            val genreColor = genreInfo?.first
            GenreChip(genre = genreText, color = genreColor)

            Spacer(Modifier.weight(1f, fill = true))

            val ratingFloat = meta.averageRating?.toFloat() ?: 0F
            if (ratingFloat > 0f) {
                val ratingColor = getRatingColor(ratingFloat)
                val fullStars = ratingFloat.toInt()
                val hasHalf = (ratingFloat - fullStars) >= 0.5f
                val starText = buildString {
                    repeat(fullStars) { append("★") }
                    if (hasHalf) append("½")
                    repeat(5 - fullStars - if (hasHalf) 1 else 0) { append("☆") }
                }
                Text(
                    text = starText,
                    style = MaterialTheme.typography.labelLarge,
                    color = ratingColor,
                    maxLines = 1,
                )
                Text(
                    text = "%.2f".format(ratingFloat),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // Row 2: uploader + info button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = meta.uploader ?: "Unknown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = { meta.uploader?.let { onSearch("uploader:\"$it\"") } },
                        onLongClick = { clipboardManager.setText(AnnotatedString(meta.uploader ?: "")) },
                    ),
            )
            IconButton(
                onClick = openMetadataViewer,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val hasAnyStats = meta.length != null ||
            (meta.size != null && meta.size!! > 0) ||
            meta.favorites != null ||
            meta.datePosted != null ||
            meta.language != null ||
            meta.visible != null
        if (hasAnyStats) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                meta.length?.let {
                    StatItem("$it pages")
                }
                meta.size?.takeIf { it > 0 }?.let {
                    StatItem(MetadataUtil.humanReadableByteCount(it, true))
                }
                meta.favorites?.let {
                    StatItem("♡ ${NumberFormat.getInstance().format(it)}")
                }
                meta.datePosted?.let {
                    StatItem(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
                }
                meta.language?.let { lang ->
                    StatItem(if (meta.translated == true) "$lang (TL)" else lang)
                }
                meta.visible?.let {
                    StatItem(it)
                }
            }
        }
    }
}

@Composable
private fun StatItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
