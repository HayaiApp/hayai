package eu.kanade.tachiyomi.ui.manga

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.EightMusesSearchMetadata
import exh.metadata.metadata.HBrowseSearchMetadata
import exh.metadata.metadata.LanraragiSearchMetadata
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.PururinSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.TsuminoSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.ui.metadata.adapters.EHentaiDescription
import exh.ui.metadata.adapters.EightMusesDescription
import exh.ui.metadata.adapters.HBrowseDescription
import exh.ui.metadata.adapters.LanraragiDescription
import exh.ui.metadata.adapters.MangaDexDescription
import exh.ui.metadata.adapters.NHentaiDescription
import exh.ui.metadata.adapters.PururinDescription
import exh.ui.metadata.adapters.TsuminoDescription
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.manga.metadata.MangaMetadataRepository

private object MetadataLoader : KoinComponent {
    val metadataRepository: MangaMetadataRepository by inject()
}

@Composable
fun MangaContinueReadingButton(
    readButtonText: String,
    readEnabled: Boolean,
    showButton: Boolean,
    accentColorInt: Int?,
    onReadClick: () -> Unit,
) {
    if (!showButton) return

    val containerColor = if (accentColorInt != null) {
        Color(accentColorInt)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val contentColor = if (accentColorInt != null) {
        if (ColorUtils.calculateLuminance(accentColorInt) > 0.5) Color.Black else Color.White
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Button(
        onClick = onReadClick,
        enabled = readEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = readButtonText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MangaMetadataSection(
    mangaId: Long,
    sourceId: Long,
    isExpanded: Boolean,
    openMetadataViewer: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val repo = remember { MetadataLoader.metadataRepository }

    val searchMetadata by repo.subscribeMetadataById(mangaId)
        .collectAsState(initial = null)
    val searchTags by repo.subscribeTagsById(mangaId)
        .collectAsState(initial = emptyList())
    val searchTitles by repo.subscribeTitlesById(mangaId)
        .collectAsState(initial = emptyList())

    val meta = remember(searchMetadata, searchTags, searchTitles) {
        val sm = searchMetadata ?: return@remember null
        try {
            FlatMetadata(metadata = sm, tags = searchTags, titles = searchTitles)
                .raise<RaisedSearchMetadata>()
        } catch (_: Exception) { null }
    } ?: return

    when (meta) {
        is EHentaiSearchMetadata -> {
            EHentaiDescription(
                meta = meta,
                sourceId = sourceId,
                isExpanded = isExpanded,
                openMetadataViewer = openMetadataViewer,
                onSearch = onSearch,
            )
        }
        is NHentaiSearchMetadata -> {
            NHentaiDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        is MangaDexSearchMetadata -> {
            MangaDexDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        is EightMusesSearchMetadata -> {
            EightMusesDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        is HBrowseSearchMetadata -> {
            HBrowseDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        is PururinSearchMetadata -> {
            PururinDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        is TsuminoSearchMetadata -> {
            TsuminoDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        is LanraragiSearchMetadata -> {
            LanraragiDescription(
                meta = meta,
                openMetadataViewer = openMetadataViewer,
            )
        }
        else -> {
            // Other metadata types can be added here
        }
    }
}
