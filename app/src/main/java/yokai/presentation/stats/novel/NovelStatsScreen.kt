package yokai.presentation.stats.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import java.util.concurrent.TimeUnit
import yokai.domain.stats.models.NovelStats
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.core.enterAlwaysAppBarScrollBehavior
import yokai.presentation.theme.Size
import yokai.util.Screen
import yokai.util.secondaryItemAlpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline

class NovelStatsScreen : Screen() {

    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val screenModel = rememberScreenModel { NovelStatsScreenModel() }
        val state by screenModel.state.collectAsState()

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = stringResource(MR.strings.novel_statistics),
            appBarType = AppBarType.SMALL,
            scrollBehavior = enterAlwaysAppBarScrollBehavior(),
        ) { innerPadding ->
            when (val s = state) {
                NovelStatsScreenModel.State.Loading -> LoadingContent(Modifier.fillMaxSize().padding(innerPadding))
                is NovelStatsScreenModel.State.Loaded -> StatsContent(s.stats, innerPadding)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingContent(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

@Composable
private fun StatsContent(stats: NovelStats, contentPadding: PaddingValues) {
    val none = stringResource(MR.strings.none)
    val cards = buildList {
        add(StatCard(stringResource(MR.strings.novels_in_library), stats.libraryNovels.toString(), Icons.AutoMirrored.Filled.MenuBook))
        add(StatCard(stringResource(MR.strings.novels_started), stats.startedNovels.toString(), Icons.Filled.PlayArrow))
        add(StatCard(stringResource(MR.strings.novels_completed), stats.completedNovels.toString(), Icons.Filled.CheckCircle))
        add(StatCard(stringResource(MR.strings.novel_chapters_read), stats.chaptersRead.toString(), Icons.Filled.Timeline))
        add(StatCard(stringResource(MR.strings.novel_total_read_duration), formatDuration(stats.totalReadDurationMs, none), Icons.Filled.AccessTime))
        add(StatCard(stringResource(MR.strings.novel_reading_streak), pluralDays(stats.currentStreakDays), Icons.Filled.LocalFireDepartment))
        add(StatCard(stringResource(MR.strings.novel_longest_streak), pluralDays(stats.longestStreakDays), Icons.Filled.LocalFireDepartment))
        add(StatCard(stringResource(MR.strings.novel_average_chapters_per_day), formatAverage(stats.averageChaptersPerDay), Icons.Filled.Timeline))
        add(StatCard(stringResource(MR.strings.novel_reading_days), stats.readingDays.toString(), Icons.Filled.AccessTime))
        if (stats.totalWordsRead > 0) {
            add(StatCard(stringResource(MR.strings.novel_words_read), formatCount(stats.totalWordsRead), Icons.Filled.Star))
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Size.medium,
            end = Size.medium,
            top = contentPadding.calculateTopPadding() + Size.small,
            bottom = contentPadding.calculateBottomPadding() + Size.medium,
        ),
        horizontalArrangement = Arrangement.spacedBy(Size.small),
        verticalArrangement = Arrangement.spacedBy(Size.small),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MostReadCard(stats)
        }
        items(cards) { card ->
            StatCardView(card)
        }
    }
}

@Composable
private fun MostReadCard(stats: NovelStats) {
    val title = stats.mostReadNovelTitle ?: stringResource(MR.strings.none)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(Size.medium)) {
            Text(
                text = stringResource(MR.strings.novel_most_read),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.secondaryItemAlpha(),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Size.tiny),
            )
            if (stats.mostReadNovelChapters > 0) {
                Text(
                    text = stringResource(MR.strings.novel_chapters_read_count, stats.mostReadNovelChapters.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        }
    }
}

@Composable
private fun StatCardView(card: StatCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Size.medium),
            verticalArrangement = Arrangement.spacedBy(Size.tiny),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Size.small)) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = Size.tiny),
                )
                Text(
                    text = card.value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = card.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
    }
}

private data class StatCard(
    val label: String,
    val value: String,
    val icon: ImageVector,
)

@Composable
private fun pluralDays(days: Long): String =
    if (days <= 0) stringResource(MR.strings.novel_zero_days)
    else stringResource(MR.strings.novel_day_count, days.toString())

private fun formatAverage(value: Double): String =
    if (value <= 0.0) "0" else String.format("%.1f", value)

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun formatDuration(ms: Long, blank: String): String {
    if (ms <= 0) return blank
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val parts = buildList {
        if (days != 0L) add("${days}d")
        if (hours != 0L) add("${hours}h")
        if (minutes != 0L && days == 0L) add("${minutes}m")
    }
    return parts.joinToString(" ").ifBlank { blank }
}
