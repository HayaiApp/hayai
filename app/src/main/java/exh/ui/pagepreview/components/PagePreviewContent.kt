package exh.ui.pagepreview.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.PagePreviewInfo
import exh.ui.pagepreview.PagePreviewState
import kotlinx.coroutines.flow.SharedFlow
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.util.coil.hayaiPagePreviewDefaults

@Composable
fun PagePreviewContent(
    state: PagePreviewState,
    imageLoader: ImageLoader,
    onOpenPage: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onJumpToPage: (Int) -> Unit = {},
    scrollEvents: SharedFlow<Int>? = null,
    navigateUp: () -> Unit,
) {
    var showBatchMenu by remember { mutableStateOf(false) }
    val successState = state as? PagePreviewState.Success
    val batchSize = successState?.batchSize
    val totalPages = successState?.estimatedTotalPages
    val batchRanges = remember(batchSize, totalPages) {
        if (batchSize == null || totalPages == null || batchSize <= 0 || totalPages <= 0) {
            emptyList()
        } else {
            val count = (totalPages + batchSize - 1) / batchSize
            List(count) { i ->
                val first = i * batchSize + 1
                val last = minOf((i + 1) * batchSize, totalPages)
                first to last
            }
        }
    }

    YokaiScaffold(
        onNavigationIconClicked = navigateUp,
        title = stringResource(MR.strings.page_previews),
        appBarType = AppBarType.SMALL,
        actions = {
            if (batchRanges.isNotEmpty()) {
                Box {
                    IconButton(onClick = { showBatchMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Numbers,
                            contentDescription = stringResource(MR.strings.page_preview_go_to),
                        )
                    }
                    DropdownMenu(
                        expanded = showBatchMenu,
                        onDismissRequest = { showBatchMenu = false },
                    ) {
                        batchRanges.forEach { (first, last) ->
                            DropdownMenuItem(
                                text = { Text("$first – $last") },
                                onClick = {
                                    showBatchMenu = false
                                    onJumpToPage(first)
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        when (state) {
            is PagePreviewState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.error.message.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PagePreviewState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is PagePreviewState.Success -> {
                val layoutDirection = LocalLayoutDirection.current
                val gridState = rememberLazyGridState()
                val gridPadding = PaddingValues(
                    start = paddingValues.calculateStartPadding(layoutDirection) + 8.dp,
                    top = paddingValues.calculateTopPadding(),
                    end = paddingValues.calculateEndPadding(layoutDirection) + 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + 16.dp,
                )

                val shouldLoadMore by remember {
                    derivedStateOf {
                        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val totalItems = gridState.layoutInfo.totalItemsCount
                        lastVisible >= totalItems - 6 && state.hasNextPage && !state.isLoadingMore
                    }
                }
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) onLoadMore()
                }

                LaunchedEffect(scrollEvents) {
                    scrollEvents?.collect { previewIndex ->
                        gridState.scrollToItem(previewIndex.coerceAtLeast(0))
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = gridPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.pagePreviews, key = { it.index }) { page ->
                        PagePreviewItem(
                            modifier = Modifier.fillMaxWidth(),
                            page = page,
                            imageLoader = imageLoader,
                            onOpenPage = onOpenPage,
                        )
                    }
                    if (state.isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagePreviewItem(
    modifier: Modifier,
    page: PagePreviewInfo,
    imageLoader: ImageLoader,
    onOpenPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val request = remember(page.imageUrl, context) {
        ImageRequest.Builder(context)
            .data(page.imageUrl)
            .hayaiPagePreviewDefaults()
            .build()
    }

    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { onOpenPage(page.index - 1) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        PagePreviewCover(
            data = request,
            imageLoader = imageLoader,
            modifier = Modifier
                .height(200.dp)
                .width(120.dp),
            shape = MaterialTheme.shapes.small,
            contentDescription = stringResource(MR.strings.page_preview_image, page.index),
        )
        Text(
            text = page.index.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
