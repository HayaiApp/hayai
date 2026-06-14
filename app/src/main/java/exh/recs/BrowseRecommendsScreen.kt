package exh.recs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import yokai.presentation.manga.components.MangaCover
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import exh.recs.batch.RankedSearchResults
import exh.recs.components.RecommendationTypeBadge
import exh.recs.sources.RecommendationContentType
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.util.Screen
import java.io.Serializable

class BrowseRecommendsScreen(
    private val args: Args,
    private val isExternalSource: Boolean,
) : Screen() {

    sealed interface Args : Serializable {
        data class SingleSourceManga(
            val mangaId: Long,
            val sourceId: Long,
            val recommendationSourceName: String,
        ) : Args
        data class MergedSourceMangas(val results: RankedSearchResults) : Args
    }

    @Composable
    override fun Content() {
        val backPress = LocalBackPress.currentOrThrow
        val router = LocalRouter.current

        val screenModel = rememberScreenModel { BrowseRecommendsScreenModel(args) }
        val state by screenModel.state.collectAsState()

        val title = remember(args) {
            when (val currentArgs = args) {
                is Args.SingleSourceManga -> {
                    val rawName = currentArgs.recommendationSourceName
                        .substringAfterLast('.')
                        .replace("PagingSource", "")
                    SOURCE_NAME_MAP[rawName] ?: rawName
                }
                is Args.MergedSourceMangas -> currentArgs.results.recSourceName
            }
        }

        YokaiScaffold(
            onNavigationIconClicked = backPress::invoke,
            title = title,
            navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
            appBarType = AppBarType.SMALL,
        ) { contentPadding ->
            when (val currentState = state) {
                is BrowseRecommendsScreenModel.State.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is BrowseRecommendsScreenModel.State.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = currentState.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is BrowseRecommendsScreenModel.State.Success -> {
                    if (currentState.mangas.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(MR.strings.rec_no_results),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        BrowseRecommendsGrid(
                            mangas = currentState.mangas,
                            contentType = currentState.sourceContentType,
                            contentPadding = contentPadding,
                            onClickItem = { manga ->
                                router?.pushController(
                                    recommendationDestination(manga).withFadeTransaction(),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Maps raw class-derived names to user-friendly display names.
         * Class names that already produce clean names (e.g., AniList, MyAnimeList, Comick) are not mapped.
         */
        private val SOURCE_NAME_MAP = mapOf(
            "MangaUpdatesCommunity" to "MangaUpdates Community",
            "MangaUpdatesSimilar" to "MangaUpdates Similar",
            "MangaDexSimilar" to "MangaDex Similar",
        )
    }
}

@Composable
private fun BrowseRecommendsGrid(
    mangas: List<Manga>,
    contentType: RecommendationContentType,
    contentPadding: PaddingValues,
    onClickItem: (Manga) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
            start = 8.dp,
            end = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = mangas,
            key = { "${it.source}-${it.url}" },
        ) { manga ->
            BrowseRecommendCard(
                manga = manga,
                contentType = contentType,
                onClick = { onClickItem(manga) },
            )
        }
    }
}

@Composable
private fun BrowseRecommendCard(
    manga: Manga,
    contentType: RecommendationContentType,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column {
            Box {
                MangaCover(
                    data = manga.thumbnail_url,
                    contentDescription = manga.title,
                    modifier = Modifier.fillMaxWidth(),
                    ratio = 2f / 3f,
                    contentScale = ContentScale.Crop,
                )
                RecommendationTypeBadge(
                    contentType = contentType,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                )
            }
            Text(
                text = manga.title,
                modifier = Modifier.padding(4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
