package exh.ui.pagepreview.components

import yokai.util.koin.get
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.getMainSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.manga.interactor.GetManga
import yokai.i18n.MR
import yokai.presentation.theme.rememberShimmerAlpha
import yokai.util.coil.appImageLoader
import yokai.util.coil.hayaiPagePreviewDefaults
import yokai.util.coil.loaderForSource

private sealed class PreviewState {
    data object Loading : PreviewState()
    data object Unavailable : PreviewState()
    data class Success(
        val previews: List<PagePreviewInfo>,
        val sourceClient: OkHttpClient? = null,
    ) : PreviewState()
}

private val THUMB_HEIGHT = 152.dp
private val THUMB_WIDTH = 108.dp
private val ROW_CONTENT_PADDING = PaddingValues(horizontal = 16.dp)

@Composable
fun PagePreviewInlineSection(
    mangaId: Long,
    sourceId: Long,
    onOpenPagePreview: () -> Unit,
    onOpenReaderAtPage: (Int) -> Unit,
) {
    var state by remember(mangaId, sourceId) { mutableStateOf<PreviewState>(PreviewState.Loading) }

    LaunchedEffect(mangaId, sourceId) {
        withContext(Dispatchers.IO) {
            try {
                val getManga: GetManga = get()
                val getChapter: GetChapter = get()
                val sourceManager: SourceManager = get()

                val manga = getManga.awaitById(mangaId) ?: run {
                    state = PreviewState.Unavailable; return@withContext
                }
                val chapters = getChapter.awaitAll(mangaId, filterScanlators = false)
                val source = sourceManager.getOrStub(manga.source)
                val previewSource = source.getMainSource<PagePreviewSource>() ?: run {
                    state = PreviewState.Unavailable; return@withContext
                }
                val httpClient = (previewSource as? HttpSource)?.client
                val previewPage = previewSource.getPagePreviewList(
                    manga,
                    chapters.sortedByDescending { it.source_order },
                    page = 1,
                )
                state = if (previewPage.pagePreviews.isNotEmpty()) {
                    PreviewState.Success(previewPage.pagePreviews, httpClient)
                } else {
                    PreviewState.Unavailable
                }
            } catch (_: Exception) {
                state = PreviewState.Unavailable
            }
        }
    }

    when (val s = state) {
        PreviewState.Loading -> {
            // Skeleton row that visually matches the Success layout so there's no shift
            // when previews resolve.
            val alpha by rememberShimmerAlpha()
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = ROW_CONTENT_PADDING,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(5) {
                    Surface(
                        modifier = Modifier
                            .height(THUMB_HEIGHT)
                            .width(THUMB_WIDTH),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                    ) {}
                }
            }
        }
        PreviewState.Unavailable -> { /* Don't show anything */ }
        is PreviewState.Success -> {
            val context = LocalContext.current
            // Route through the source's OkHttp client when we have one so per-source
            // cookies / headers go through; otherwise use the app singleton directly.
            // Either way the loader inherits the singleton's memory cache, disk cache,
            // decoders, and dispatcher pools (see [yokai.util.coil.AppImageLoader]).
            val imageLoader: ImageLoader = remember(context, s.sourceClient) {
                val client = s.sourceClient
                if (client != null) loaderForSource(context, client) else appImageLoader(context)
            }
            val lazyListState = rememberLazyListState()
            // True once the user has scrolled past the start. Flips the prominent under-strip
            // button OFF and the in-row tail card ON, so the affordance follows the user's
            // thumb without ever showing two "View all" buttons at once.
            val hasScrolled by remember {
                derivedStateOf {
                    lazyListState.firstVisibleItemIndex > 0 ||
                        lazyListState.firstVisibleItemScrollOffset > 0
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = lazyListState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ROW_CONTENT_PADDING,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.previews, key = { it.imageUrl }) { preview ->
                        PreviewThumb(
                            preview = preview,
                            imageLoader = imageLoader,
                            onClick = { onOpenReaderAtPage(preview.index - 1) },
                        )
                    }
                    item(key = "view_all_tail") {
                        AnimatedVisibility(
                            visible = hasScrolled,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ViewAllTail(onClick = onOpenPagePreview)
                        }
                    }
                }
                TextButton(
                    onClick = onOpenPagePreview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(text = stringResource(MR.strings.view_all))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewThumb(
    preview: PagePreviewInfo,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(preview.imageUrl, context) {
        ImageRequest.Builder(context)
            .data(preview.imageUrl)
            .hayaiPagePreviewDefaults()
            .build()
    }
    Box(
        modifier = Modifier
            .height(THUMB_HEIGHT)
            .width(THUMB_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        PagePreviewCover(
            data = request,
            imageLoader = imageLoader,
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            contentDescription = "Page ${preview.index}",
        )
        // Bottom scrim + page number, readable over any cover content.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )
        Text(
            text = preview.index.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ViewAllTail(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(THUMB_HEIGHT)
            .width(THUMB_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(MR.strings.view_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
