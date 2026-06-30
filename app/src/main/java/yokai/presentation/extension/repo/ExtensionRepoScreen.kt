package yokai.presentation.extension.repo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExtensionOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource as androidStringResource
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isTablet
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import yokai.domain.DialogHostState
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.i18n.MR
import yokai.presentation.theme.ReducedMotion
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.EmptyScreen
import yokai.presentation.component.ToolTipButton
import yokai.presentation.core.enterAlwaysAppBarScrollBehavior
import yokai.presentation.extension.repo.component.ExtensionRepoInput
import yokai.presentation.extension.repo.component.ExtensionRepoItem
import yokai.util.Screen
import android.R as AR

class ExtensionRepoScreen(
    private val title: String,
    private var repoUrl: String? = null,
): Screen() {
    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val context = LocalContext.current
        val alertDialog = LocalDialogHostState.currentOrThrow
        val currentContext by rememberUpdatedState(context)
        val currentAlertDialog by rememberUpdatedState(alertDialog)

        val scope = rememberCoroutineScope()
        val extensionScreenModel = rememberScreenModel { ExtensionRepoScreenModel() }
        val novelScreenModel = rememberScreenModel { NovelRepoScreenModel() }
        val extensionState by extensionScreenModel.state.collectAsState()
        val novelState by novelScreenModel.state.collectAsState()

        val pagerState = rememberPagerState(pageCount = { 2 })

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = title,
            appBarType = AppBarType.SMALL,
            scrollBehavior = enterAlwaysAppBarScrollBehavior(),
            actions = {
                if (pagerState.currentPage == 0) {
                    ToolTipButton(
                        toolTipLabel = stringResource(MR.strings.refresh),
                        icon = Icons.Outlined.Refresh,
                        buttonClicked = {
                            context.toast("Refreshing...")
                            extensionScreenModel.refreshRepos()
                        },
                    )
                }
            },
            appBarBottomContent = {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch {
                            if (ReducedMotion.isEnabled()) pagerState.scrollToPage(0)
                            else pagerState.animateScrollToPage(0)
                        } },
                        text = { Text(stringResource(MR.strings.manga)) },
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch {
                            if (ReducedMotion.isEnabled()) pagerState.scrollToPage(1)
                            else pagerState.animateScrollToPage(1)
                        } },
                        text = { Text(stringResource(MR.strings.novels)) },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> ExtensionRepoTab(
                            state = extensionState,
                            screenModel = extensionScreenModel,
                            alertDialog = alertDialog,
                            scope = scope,
                        )
                        1 -> NovelRepoTab(
                            state = novelState,
                            screenModel = novelScreenModel,
                            alertDialog = alertDialog,
                            scope = scope,
                        )
                    }
                }
            }
        }

        LaunchedEffect(repoUrl) {
            repoUrl?.let {
                extensionScreenModel.addRepo(repoUrl!!)
                repoUrl = null
            }
        }

        LaunchedEffect(Unit) {
            extensionScreenModel.event.collectLatest { event ->
                when (event) {
                    is ExtensionRepoEvent.NoOp -> {}
                    is ExtensionRepoEvent.LocalizedMessage -> currentContext.toast(event.stringRes)
                    is ExtensionRepoEvent.Success -> {}
                    is ExtensionRepoEvent.ShowDialog -> {
                        when(event.dialog) {
                            is RepoDialog.Conflict -> {
                                currentAlertDialog.awaitExtensionRepoReplacePrompt(
                                    oldRepo = event.dialog.oldRepo,
                                    newRepo = event.dialog.newRepo,
                                    onMigrate = { extensionScreenModel.replaceRepo(event.dialog.newRepo) },
                                )
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            novelScreenModel.event.collectLatest { event ->
                when (event) {
                    is ExtensionRepoEvent.NoOp -> {}
                    is ExtensionRepoEvent.LocalizedMessage -> currentContext.toast(event.stringRes)
                    is ExtensionRepoEvent.Success -> {}
                    is ExtensionRepoEvent.ShowDialog -> {}
                }
            }
        }

        alertDialog.value?.invoke()
    }

    @Composable
    private fun ExtensionRepoTab(
        state: ExtensionRepoScreenModel.State,
        screenModel: ExtensionRepoScreenModel,
        alertDialog: DialogHostState,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        if (state is ExtensionRepoScreenModel.State.Loading) return

        val repos = (state as ExtensionRepoScreenModel.State.Success).repos
        var inputText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
            verticalArrangement = Arrangement.Top,
            state = listState,
        ) {
            item {
                ExtensionRepoInput(
                    inputText = inputText,
                    inputHint = stringResource(MR.strings.label_add_repo),
                    onInputChange = { inputText = it },
                    onAddClick = {
                        screenModel.addRepo(it)
                        inputText = ""
                    },
                )
            }

            if (repos.isEmpty()) {
                item {
                    EmptyScreen(
                        modifier = Modifier.fillParentMaxSize(),
                        image = Icons.Filled.ExtensionOff,
                        message = stringResource(MR.strings.information_empty_repos),
                        isTablet = isTablet(),
                    )
                }
                return@LazyColumn
            }

            repos.forEach { repo ->
                item {
                    ExtensionRepoItem(
                        extensionRepo = repo,
                        onDeleteClick = { repoToDelete ->
                            scope.launch { alertDialog.awaitRepoDeletePrompt(repoToDelete) { screenModel.deleteRepo(it) } }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun NovelRepoTab(
        state: NovelRepoScreenModel.State,
        screenModel: NovelRepoScreenModel,
        alertDialog: DialogHostState,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        if (state is NovelRepoScreenModel.State.Loading) return

        val repos = (state as NovelRepoScreenModel.State.Success).repos
        var inputText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
            verticalArrangement = Arrangement.Top,
            state = listState,
        ) {
            item {
                ExtensionRepoInput(
                    inputText = inputText,
                    inputHint = stringResource(MR.strings.label_add_repo),
                    onInputChange = { inputText = it },
                    onAddClick = {
                        screenModel.addRepo(it)
                        inputText = ""
                    },
                )
            }

            if (repos.isEmpty()) {
                item {
                    EmptyScreen(
                        modifier = Modifier.fillParentMaxSize(),
                        image = Icons.Filled.ExtensionOff,
                        message = stringResource(MR.strings.information_empty_novel_repos),
                        isTablet = isTablet(),
                    )
                }
                return@LazyColumn
            }

            repos.forEach { repo ->
                item {
                    NovelRepoItem(
                        name = repo.name,
                        baseUrl = repo.baseUrl,
                        onDeleteClick = { repoToDelete ->
                            scope.launch { alertDialog.awaitRepoDeletePrompt(repoToDelete) { screenModel.deleteRepo(it) } }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun NovelRepoItem(
        name: String,
        baseUrl: String,
        onDeleteClick: (String) -> Unit,
    ) {
        // Reuse the same layout as ExtensionRepoItem but with NovelRepo data
        ExtensionRepoItem(
            extensionRepo = ExtensionRepo(
                baseUrl = baseUrl,
                name = name,
                shortName = null,
                website = "",
                signingKeyFingerprint = "",
            ),
            onDeleteClick = onDeleteClick,
        )
    }

    private suspend fun DialogHostState.awaitRepoDeletePrompt(
        repoToDelete: String,
        onDelete: (String) -> Unit,
    ): Unit = dialog { cont ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = stringResource(MR.strings.confirm_delete_repo_title),
                    fontStyle = MaterialTheme.typography.titleMedium.fontStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                )
            },
            text = {
                Text(
                    text = stringResource(MR.strings.confirm_delete_repo, repoToDelete),
                    fontStyle = MaterialTheme.typography.bodyMedium.fontStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            },
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(repoToDelete)
                        cont.cancel()
                    }
                ) {
                    Text(
                        text = stringResource(MR.strings.delete),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(
                        text = stringResource(MR.strings.cancel),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            },
        )
    }

    private suspend fun DialogHostState.awaitExtensionRepoReplacePrompt(
        oldRepo: ExtensionRepo,
        newRepo: ExtensionRepo,
        onMigrate: () -> Unit,
    ): Unit = dialog { cont ->
        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMigrate()
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_replace_repo))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = androidStringResource(AR.string.cancel))
                }
            },
            title = {
                Text(text = stringResource(MR.strings.action_replace_repo_title))
            },
            text = {
                Text(text = stringResource(MR.strings.action_replace_repo_message, newRepo.name, oldRepo.name))
            },
        )
    }
}
