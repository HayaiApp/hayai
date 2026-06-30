package yokai.presentation.library.update

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.library.LibraryUpdateReportEntry
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isTablet
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import yokai.domain.manga.models.MangaCover
import yokai.i18n.MR
import yokai.presentation.theme.ReducedMotion
import yokai.presentation.theme.Size
import yokai.presentation.theme.header
import yokai.presentation.theme.isReducedMotion
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.core.enterAlwaysAppBarScrollBehavior
import yokai.presentation.component.EmptyScreen
import yokai.presentation.component.ToolTipButton
import yokai.presentation.manga.components.MangaCover as MangaCoverImage
import yokai.presentation.manga.components.MangaCoverRatio
import yokai.util.Screen
import yokai.util.secondaryItemAlpha

class LibraryUpdateReportScreen(
    private val initialTab: LibraryUpdateReportScreenModel.ReportTab =
        LibraryUpdateReportScreenModel.ReportTab.ERRORS,
) : Screen() {

    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val context = LocalContext.current
        val router = LocalRouter.current
        val screenModel = rememberScreenModel { LibraryUpdateReportScreenModel(context) }
        val state by screenModel.state.collectAsState()

        val pagerState = rememberPagerState(
            initialPage = if (initialTab == LibraryUpdateReportScreenModel.ReportTab.SKIPPED) 1 else 0,
            pageCount = { 2 },
        )
        val scope = rememberCoroutineScope()
        val tabAt: (Int) -> LibraryUpdateReportScreenModel.ReportTab = { idx ->
            if (idx == 0) LibraryUpdateReportScreenModel.ReportTab.ERRORS
            else LibraryUpdateReportScreenModel.ReportTab.SKIPPED
        }

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = stringResource(MR.strings.library_update_report),
            appBarType = AppBarType.SMALL,
            scrollBehavior = enterAlwaysAppBarScrollBehavior(),
            actions = {
                ToolTipButton(
                    toolTipLabel = stringResource(MR.strings.action_open_log_file),
                    icon = Icons.Filled.Description,
                    buttonClicked = {
                        val uri = screenModel.logFileUri(tabAt(pagerState.currentPage))
                        if (uri == null) {
                            context.toast(MR.strings.no_results_found)
                            return@ToolTipButton
                        }
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/plain")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { context.toast(it.message ?: it::class.simpleName.orEmpty()) }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val errorCount = (state as? LibraryUpdateReportScreenModel.State.Loaded)
                    ?.errorGroups?.sumOf { it.entries.size } ?: 0
                val skippedCount = (state as? LibraryUpdateReportScreenModel.State.Loaded)
                    ?.skippedGroups?.sumOf { it.entries.size } ?: 0

                TabRow(selectedTabIndex = pagerState.currentPage) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch {
                            if (ReducedMotion.isEnabled()) pagerState.scrollToPage(0)
                            else pagerState.animateScrollToPage(0)
                        } },
                        text = {
                            TabLabel(
                                label = stringResource(MR.strings.library_update_report_errors),
                                count = errorCount,
                            )
                        },
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch {
                            if (ReducedMotion.isEnabled()) pagerState.scrollToPage(1)
                            else pagerState.animateScrollToPage(1)
                        } },
                        text = {
                            TabLabel(
                                label = stringResource(MR.strings.library_update_report_skipped),
                                count = skippedCount,
                            )
                        },
                    )
                }

                (state as? LibraryUpdateReportScreenModel.State.Loaded)?.report?.timestampMs?.let { ts ->
                    val formatted = remember(ts) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
                    }
                    Text(
                        text = stringResource(MR.strings.library_update_report_last_run, formatted),
                        style = MaterialTheme.typography.header,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Size.medium, vertical = Size.small),
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val tab = tabAt(page)
                    ReportTabContent(
                        state = state,
                        tab = tab,
                        onMangaClick = { mangaId ->
                            if (mangaId <= 0L) return@ReportTabContent
                            router?.pushController(MangaDetailsController(mangaId).withFadeTransaction())
                        },
                    )
                }
            }
        }

        LaunchedEffect(initialTab) {
            // Re-load on entry; the underlying file may have been replaced by a more recent run.
            screenModel.load()
        }
    }
}

@Composable
private fun TabLabel(label: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Size.small),
    ) {
        Text(text = label)
        if (count > 0) {
            CountBadge(count = count)
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                .padding(horizontal = Size.small, vertical = Size.extraExtraTiny),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ReportTabContent(
    state: LibraryUpdateReportScreenModel.State,
    tab: LibraryUpdateReportScreenModel.ReportTab,
    onMangaClick: (Long) -> Unit,
) {
    when (state) {
        LibraryUpdateReportScreenModel.State.Loading -> Box(modifier = Modifier.fillMaxSize())
        is LibraryUpdateReportScreenModel.State.Loaded -> {
            val groups = when (tab) {
                LibraryUpdateReportScreenModel.ReportTab.ERRORS -> state.errorGroups
                LibraryUpdateReportScreenModel.ReportTab.SKIPPED -> state.skippedGroups
            }
            if (groups.isEmpty()) {
                val message = when {
                    state.report == null -> stringResource(MR.strings.library_update_report_empty)
                    tab == LibraryUpdateReportScreenModel.ReportTab.ERRORS ->
                        stringResource(MR.strings.library_update_report_no_errors)
                    else -> stringResource(MR.strings.library_update_report_no_skipped)
                }
                EmptyScreen(
                    modifier = Modifier.fillMaxSize(),
                    image = Icons.Filled.CheckCircle,
                    message = message,
                    isTablet = isTablet(),
                )
                return
            }

            val expanded = remember(groups) {
                // Default-expand the first group; collapse the rest to keep the screen scannable
                // when many distinct reasons are present.
                mutableStateMapOf<String, Boolean>().apply {
                    groups.forEachIndexed { index, group -> put(group.message, index == 0) }
                }
            }
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = Size.medium),
            ) {
                groups.forEachIndexed { index, group ->
                    val isExpanded = expanded[group.message] ?: true
                    item(key = "header-${group.message}") {
                        ReasonHeader(
                            message = group.message,
                            count = group.entries.size,
                            expanded = isExpanded,
                            onToggle = { expanded[group.message] = !isExpanded },
                        )
                    }
                    if (isExpanded) {
                        items(
                            count = group.entries.size,
                            key = { idx -> "${group.message}-${group.entries[idx].mangaId}-$idx" },
                        ) { idx ->
                            val entry = group.entries[idx]
                            MangaRow(entry = entry, onClick = { onMangaClick(entry.mangaId) })
                        }
                    }
                    if (index < groups.lastIndex) {
                        item(key = "div-${group.message}") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = Size.medium),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasonHeader(
    message: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Size.medium, vertical = Size.smedium)
            .animateContentSize(animationSpec = if (isReducedMotion) snap() else spring()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Size.smedium),
    ) {
        val icon: ImageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
        )
        CountBadge(count = count)
    }
}

@Composable
private fun MangaRow(
    entry: LibraryUpdateReportEntry,
    onClick: () -> Unit,
) {
    val cover = remember(entry.mangaId, entry.mangaThumbnailUrl, entry.mangaCoverLastModified) {
        MangaCover(
            mangaId = entry.mangaId.takeIf { it > 0L },
            sourceId = entry.sourceId,
            url = entry.mangaThumbnailUrl ?: "",
            lastModified = entry.mangaCoverLastModified,
            inLibrary = entry.mangaInLibrary,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 72.dp)
            .clickable(enabled = entry.mangaId > 0L, onClick = onClick)
            .padding(horizontal = Size.medium, vertical = Size.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Size.smedium),
    ) {
        MangaCoverImage(
            data = cover,
            ratio = MangaCoverRatio.BOOK,
            modifier = Modifier
                .height(56.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Size.extraTiny),
        ) {
            Text(
                text = entry.mangaTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.secondaryItemAlpha(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
