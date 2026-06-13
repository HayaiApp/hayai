package eu.kanade.tachiyomi.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.flow.flowOf
import yokai.domain.manga.interactor.GetUpcomingManga
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.EmptyScreen
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio
import yokai.util.koin.get
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UpcomingController : BaseComposeController() {
    private val getUpcomingManga: GetUpcomingManga = get()
    private val preferences: eu.kanade.tachiyomi.data.preference.PreferencesHelper = get()
    private val sourceManager: eu.kanade.tachiyomi.source.SourceManager = get()

    override val hostsOwnAppBar: Boolean = true

    @Composable
    override fun ScreenContent() {
        val backPress = LocalBackPress.current
        val context = LocalContext.current
        val smartUpdateEnabled by preferences.smartUpdateEnabled().collectAsState()
        val upcoming by remember(smartUpdateEnabled) {
            if (smartUpdateEnabled) getUpcomingManga.subscribe() else flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        var selectedMonth by remember { mutableStateOf(YearMonth.now()) }

        UpcomingScreen(
            smartUpdateEnabled = smartUpdateEnabled,
            manga = upcoming,
            selectedMonth = selectedMonth,
            sourceName = { sourceManager.getOrStub(it.source).toString() },
            onMonthChange = { selectedMonth = it },
            onBack = { backPress?.invoke() },
            onHelp = { context.openInBrowser(HELP_URL) },
            onMangaClick = { manga ->
                manga.id?.let {
                    router.pushController(MangaDetailsController(it).withFadeTransaction())
                }
            },
        )
    }

    companion object {
        private const val HELP_URL = "https://mihon.app/docs/faq/library#why-is-global-update-skipping-entries"
    }
}

@Composable
private fun UpcomingScreen(
    smartUpdateEnabled: Boolean,
    manga: List<Manga>,
    selectedMonth: YearMonth,
    sourceName: (Manga) -> String,
    onMonthChange: (YearMonth) -> Unit,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onMangaClick: (Manga) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val monthItems = remember(manga, selectedMonth) {
        manga
            .filter { YearMonth.from(it.next_update.toLocalDate(zone)) == selectedMonth }
            .groupBy { it.next_update.toLocalDate(zone) }
            .toSortedMap()
    }
    val events = remember(manga, selectedMonth) {
        manga
            .filter { YearMonth.from(it.next_update.toLocalDate(zone)) == selectedMonth }
            .groupingBy { it.next_update.toLocalDate(zone) }
            .eachCount()
    }

    YokaiScaffold(
        title = stringResource(MR.strings.label_upcoming),
        onNavigationIconClicked = onBack,
        appBarType = AppBarType.SMALL,
        actions = {
            IconButton(onClick = onHelp) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(MR.strings.upcoming_guide),
                )
            }
        },
    ) { padding ->
        when {
            !smartUpdateEnabled -> {
                EmptyScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    image = Icons.Outlined.Info,
                    message = stringResource(MR.strings.upcoming_empty_smart_disabled),
                    isTablet = false,
                )
            }
            manga.isEmpty() -> {
                EmptyScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    image = Icons.Outlined.Info,
                    message = stringResource(MR.strings.upcoming_empty),
                    isTablet = false,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        end = 16.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item("calendar") {
                        UpcomingCalendar(
                            selectedMonth = selectedMonth,
                            events = events,
                            onMonthChange = onMonthChange,
                        )
                    }
                    if (monthItems.isEmpty()) {
                        item("empty-month") {
                            Text(
                                text = stringResource(MR.strings.upcoming_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    monthItems.forEach { (date, entries) ->
                        item("header-$date") {
                            DateHeader(date = date, count = entries.size)
                        }
                        items(entries, key = { it.id ?: it.url.hashCode().toLong() }) { item ->
                            UpcomingRow(
                                manga = item,
                                sourceName = sourceName(item),
                                zone = zone,
                                onClick = { onMangaClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingCalendar(
    selectedMonth: YearMonth,
    events: Map<LocalDate, Int>,
    onMonthChange: (YearMonth) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onMonthChange(selectedMonth.minusMonths(1)) }) {
                    Text(stringResource(MR.strings.upcoming_calendar_prev))
                }
                Text(
                    text = selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = { onMonthChange(selectedMonth.plusMonths(1)) }) {
                    Text(stringResource(MR.strings.upcoming_calendar_next))
                }
            }
            Spacer(Modifier.height(8.dp))
            CalendarGrid(selectedMonth = selectedMonth, events = events)
        }
    }
}

@Composable
private fun CalendarGrid(
    selectedMonth: YearMonth,
    events: Map<LocalDate, Int>,
) {
    val first = selectedMonth.atDay(1)
    val leading = first.dayOfWeek.value % 7
    val cells = List(leading) { null } + (1..selectedMonth.lengthOfMonth()).map { selectedMonth.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            week.forEach { date ->
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (date == LocalDate.now()) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (date != null) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (events.containsKey(date)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        events[date]?.let { count ->
                            Badge(
                                modifier = Modifier.align(Alignment.TopEnd),
                                containerColor = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(count.toString())
                            }
                        }
                    }
                }
            }
            repeat(7 - week.size) {
                Spacer(Modifier.size(42.dp))
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, count: Int) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(MR.strings.upcoming_today)
        today.plusDays(1) -> stringResource(MR.strings.upcoming_tomorrow)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        AssistChip(onClick = {}, label = { Text(count.toString()) }, enabled = false)
    }
}

@Composable
private fun UpcomingRow(
    manga: Manga,
    sourceName: String,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover(
                data = manga.cover(),
                ratio = MangaCoverRatio.BOOK,
                contentDescription = manga.title,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(width = 48.dp, height = 72.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabel(manga.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = relativeDate(manga.next_update, zone),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun statusLabel(status: Int): String {
    return when (status) {
        SManga.ONGOING -> stringResource(MR.strings.ongoing)
        SManga.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
        SManga.COMPLETED -> stringResource(MR.strings.completed)
        SManga.CANCELLED -> stringResource(MR.strings.cancelled)
        SManga.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
        else -> stringResource(MR.strings.unknown_status)
    }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate {
    return Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
}

@Composable
private fun relativeDate(timeMillis: Long, zone: ZoneId): String {
    val date = timeMillis.toLocalDate(zone)
    val today = LocalDate.now(zone)
    return when (val days = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()) {
        0 -> stringResource(MR.strings.upcoming_today)
        1 -> stringResource(MR.strings.upcoming_tomorrow)
        else -> stringResource(MR.plurals.day_plural, days, days)
    }
}
