package eu.kanade.tachiyomi.ui.upcoming

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import yokai.domain.manga.interactor.GetUpcomingManga
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiAppBarTabs
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.EmptyScreen
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio
import yokai.util.koin.get
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
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
            isNovel = { it.isNovel(sourceManager) },
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
    isNovel: (Manga) -> Boolean,
    onMonthChange: (YearMonth) -> Unit,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onMangaClick: (Manga) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedType by remember { mutableStateOf(UpcomingType.ALL) }
    val filteredManga = remember(manga, selectedType) {
        manga.filter { item ->
            when (selectedType) {
                UpcomingType.ALL -> true
                UpcomingType.MANGA -> !isNovel(item)
                UpcomingType.NOVELS -> isNovel(item)
            }
        }
    }
    val monthItems = remember(filteredManga, selectedMonth) {
        filteredManga
            .filter { YearMonth.from(it.next_update.toLocalDate(zone)) == selectedMonth }
            .groupBy { it.next_update.toLocalDate(zone) }
            .toSortedMap()
    }
    val events = remember(filteredManga, selectedMonth) {
        filteredManga
            .filter { YearMonth.from(it.next_update.toLocalDate(zone)) == selectedMonth }
            .groupingBy { it.next_update.toLocalDate(zone) }
            .eachCount()
    }
    val headerIndexes = remember(monthItems) {
        var index = 1 // Calendar is the first LazyColumn item; tabs live in the app bar.
        monthItems.mapValues { (_, entries) ->
            index.also { index += entries.size + 1 }
        }
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
        appBarBottomContent = {
            UpcomingTypeTabs(
                selected = selectedType,
                onSelected = { selectedType = it },
            )
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
                    state = listState,
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
                            onDayClick = { date ->
                                headerIndexes[date]?.let { headerIndex ->
                                    scope.launch {
                                        listState.animateScrollToItem(headerIndex)
                                    }
                                }
                            },
                        )
                    }
                    if (monthItems.isEmpty()) {
                        item("empty-month") {
                            EmptyScreen(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                image = Icons.Outlined.Info,
                                message = stringResource(MR.strings.upcoming_empty_month),
                                isTablet = false,
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

private enum class UpcomingType {
    ALL,
    MANGA,
    NOVELS,
}

private const val DAYS_OF_WEEK = 7
private const val MAX_VISIBLE_EVENT_INDICATORS = 3
private const val EVENT_INDICATOR_ALPHA_MULTIPLIER = 0.3f
private const val EVENT_INDICATOR_SCALE = 12
private const val MONTH_YEAR_CHANGE_ANIMATION_DURATION = 200
private val CalendarFontSize = 16.sp

@Composable
private fun UpcomingTypeTabs(
    selected: UpcomingType,
    onSelected: (UpcomingType) -> Unit,
) {
    val tabs = listOf(
        UpcomingType.ALL to stringResource(MR.strings.all),
        UpcomingType.MANGA to stringResource(MR.strings.manga),
        UpcomingType.NOVELS to stringResource(MR.strings.novels),
    )
    YokaiAppBarTabs(
        labels = tabs.map { it.second },
        selectedIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0),
        onSelected = { index -> onSelected(tabs[index].first) },
    )
}

@Composable
private fun UpcomingCalendar(
    selectedMonth: YearMonth,
    events: Map<LocalDate, Int>,
    onMonthChange: (YearMonth) -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CalendarHeader(
            yearMonth = selectedMonth,
            onPreviousClick = { onMonthChange(selectedMonth.minusMonths(1)) },
            onNextClick = { onMonthChange(selectedMonth.plusMonths(1)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .padding(start = 16.dp),
        )
        CalendarGrid(
            selectedMonth = selectedMonth,
            events = events,
            onDayClick = onDayClick,
        )
    }
}

@Composable
private fun CalendarHeader(
    yearMonth: YearMonth,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = yearMonth,
            modifier = Modifier.weight(1f),
            transitionSpec = { monthChangeAnimation() },
            label = "Change Month",
        ) { monthYear ->
            Text(
                text = monthYear.calendarTitle(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            IconButton(onClick = onPreviousClick) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = stringResource(MR.strings.upcoming_calendar_prev),
                )
            }
            IconButton(onClick = onNextClick) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = stringResource(MR.strings.upcoming_calendar_next),
                )
            }
        }
    }
}

private fun AnimatedContentTransitionScope<YearMonth>.monthChangeAnimation(): ContentTransform {
    val movingForward = targetState > initialState
    val enterTransition = slideInVertically(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    ) { height -> if (movingForward) height else -height } + fadeIn(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    )
    val exitTransition = slideOutVertically(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    ) { height -> if (movingForward) -height else height } + fadeOut(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    )
    return (enterTransition togetherWith exitTransition)
        .using(SizeTransform(clip = false))
}

@Composable
@ReadOnlyComposable
private fun YearMonth.calendarTitle(): String =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(this)

@Composable
private fun CalendarGrid(
    selectedMonth: YearMonth,
    events: Map<LocalDate, Int>,
    onDayClick: (LocalDate) -> Unit,
) {
    val localeFirstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
    val weekDays = remember(localeFirstDayOfWeek) {
        (0 until DAYS_OF_WEEK).map { DayOfWeek.of((localeFirstDayOfWeek - 1 + it) % DAYS_OF_WEEK + 1) }
    }
    val emptyFieldCount = weekDays.indexOf(selectedMonth.atDay(1).dayOfWeek)
    val cells = List(emptyFieldCount) { null } + (1..selectedMonth.lengthOfMonth()).map { selectedMonth.atDay(it) }

    Row(modifier = Modifier.fillMaxWidth()) {
        weekDays.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = CalendarFontSize,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    cells.chunked(7).forEach { week ->
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            repeat(DAYS_OF_WEEK) { dayIndex ->
                val date = week.getOrNull(dayIndex)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (date != null) {
                        CalendarDay(
                            date = date,
                            events = events[date] ?: 0,
                            onClick = { onDayClick(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    events: Int,
    onClick: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    Box(
        modifier = Modifier
            .then(
                if (date == today) {
                    Modifier.border(
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                        shape = CircleShape,
                    )
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .circleLayout(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (date.isBefore(today)) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        )
        Row(Modifier.offset(y = 12.dp)) {
            val visibleCount = events.coerceAtMost(MAX_VISIBLE_EVENT_INDICATORS)
            repeat(visibleCount) { index ->
                CalendarIndicator(
                    index = index,
                    size = 56.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CalendarIndicator(
    index: Int,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .clip(CircleShape)
            .background(color = color.copy(alpha = (index + 1) * EVENT_INDICATOR_ALPHA_MULTIPLIER))
            .size(size = size.div(EVENT_INDICATOR_SCALE)),
    )
}

private fun Modifier.circleLayout() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val diameter = maxOf(placeable.height, placeable.width, 48.dp.roundToPx())
    layout(diameter, diameter) {
        placeable.placeRelative(
            x = (diameter - placeable.width) / 2,
            y = (diameter - placeable.height) / 2,
        )
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
        Badge(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text(count.toString())
        }
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
