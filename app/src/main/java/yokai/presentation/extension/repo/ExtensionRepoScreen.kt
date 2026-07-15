package yokai.presentation.extension.repo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExtensionOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource as androidStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import yokai.domain.DialogHostState
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiAppBarTabs
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.ToolTipButton
import yokai.presentation.extension.repo.component.ExtensionRepoInput
import yokai.presentation.extension.repo.component.ExtensionRepoItem
import yokai.presentation.theme.ReducedMotion
import yokai.util.Screen
import yokai.util.lang.getString
import android.R as AR

class ExtensionRepoScreen(
    private val title: String,
    private var repoUrl: String? = null,
) : Screen() {

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
        val extensionIsAdding by extensionScreenModel.isAdding.collectAsState()
        val novelIsAdding by novelScreenModel.isAdding.collectAsState()
        val isRefreshing by extensionScreenModel.isRefreshing.collectAsState()

        var extensionInput by rememberSaveable { mutableStateOf("") }
        var novelInput by rememberSaveable { mutableStateOf("") }
        var extensionError by rememberSaveable { mutableStateOf<String?>(null) }
        var novelError by rememberSaveable { mutableStateOf<String?>(null) }

        val pagerState = rememberPagerState(pageCount = { 2 })
        val extensionCount = (extensionState as? ExtensionRepoScreenModel.State.Success)?.repos?.size
        val novelCount = (novelState as? NovelRepoScreenModel.State.Success)?.repos?.size

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = title,
            appBarType = AppBarType.SMALL,
            actions = {
                if (pagerState.currentPage == 0) {
                    if (isRefreshing) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        ToolTipButton(
                            toolTipLabel = stringResource(MR.strings.refresh),
                            icon = Icons.Outlined.Refresh,
                            buttonClicked = extensionScreenModel::refreshRepos,
                        )
                    }
                }
            },
            appBarBottomContent = {
                YokaiAppBarTabs(
                    labels = listOf(
                        stringResource(MR.strings.manga),
                        stringResource(MR.strings.novels),
                    ),
                    counts = listOf(extensionCount, novelCount),
                    selectedIndex = pagerState.currentPage,
                    onSelected = { page ->
                        scope.launch {
                            if (ReducedMotion.isEnabled()) pagerState.scrollToPage(page)
                            else pagerState.animateScrollToPage(page)
                        }
                    },
                )
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) { page ->
                when (page) {
                    0 -> ExtensionRepoTab(
                        state = extensionState,
                        inputText = extensionInput,
                        inputError = extensionError,
                        isAdding = extensionIsAdding,
                        onInputChange = {
                            extensionInput = it
                            extensionError = null
                        },
                        onAddClick = extensionScreenModel::addRepo,
                        onDeleteClick = { repoToDelete ->
                            scope.launch {
                                alertDialog.awaitRepoDeletePrompt(repoToDelete, extensionScreenModel::deleteRepo)
                            }
                        },
                    )
                    1 -> NovelRepoTab(
                        state = novelState,
                        inputText = novelInput,
                        inputError = novelError,
                        isAdding = novelIsAdding,
                        onInputChange = {
                            novelInput = it
                            novelError = null
                        },
                        onAddClick = novelScreenModel::addRepo,
                        onDeleteClick = { repoToDelete ->
                            scope.launch {
                                alertDialog.awaitRepoDeletePrompt(repoToDelete, novelScreenModel::deleteRepo)
                            }
                        },
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            extensionScreenModel.event.collectLatest { event ->
                when (event) {
                    ExtensionRepoEvent.NoOp -> Unit
                    ExtensionRepoEvent.Success -> {
                        extensionInput = ""
                        extensionError = null
                    }
                    ExtensionRepoEvent.RefreshFailed -> currentContext.toast(MR.strings.repo_refresh_failed)
                    is ExtensionRepoEvent.LocalizedMessage -> {
                        extensionError = currentContext.getString(event.stringRes)
                    }
                    is ExtensionRepoEvent.ShowDialog -> when (event.dialog) {
                        is RepoDialog.Conflict -> currentAlertDialog.awaitExtensionRepoReplacePrompt(
                            oldRepo = event.dialog.oldRepo,
                            newRepo = event.dialog.newRepo,
                            onMigrate = { extensionScreenModel.replaceRepo(event.dialog.newRepo) },
                        )
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            novelScreenModel.event.collectLatest { event ->
                when (event) {
                    ExtensionRepoEvent.NoOp -> Unit
                    ExtensionRepoEvent.Success -> {
                        novelInput = ""
                        novelError = null
                    }
                    is ExtensionRepoEvent.LocalizedMessage -> {
                        novelError = currentContext.getString(event.stringRes)
                    }
                    is ExtensionRepoEvent.ShowDialog -> Unit
                }
            }
        }

        LaunchedEffect(repoUrl) {
            repoUrl?.let { url ->
                extensionInput = url
                extensionError = null
                extensionScreenModel.addRepo(url)
                repoUrl = null
            }
        }

        alertDialog.value?.invoke()
    }

    @Composable
    private fun ExtensionRepoTab(
        state: ExtensionRepoScreenModel.State,
        inputText: String,
        inputError: String?,
        isAdding: Boolean,
        onInputChange: (String) -> Unit,
        onAddClick: (String) -> Unit,
        onDeleteClick: (String) -> Unit,
    ) {
        if (state is ExtensionRepoScreenModel.State.Loading) {
            LoadingRepositories()
            return
        }
        val repos = (state as ExtensionRepoScreenModel.State.Success).repos
        RepositoryList(
            repos = repos,
            inputText = inputText,
            inputError = inputError,
            isAdding = isAdding,
            formTitle = stringResource(MR.strings.add_manga_repo),
            formDescription = stringResource(MR.strings.manga_repo_add_description),
            emptyMessage = stringResource(MR.strings.information_empty_repos),
            onInputChange = onInputChange,
            onAddClick = onAddClick,
            onDeleteClick = onDeleteClick,
        )
    }

    @Composable
    private fun NovelRepoTab(
        state: NovelRepoScreenModel.State,
        inputText: String,
        inputError: String?,
        isAdding: Boolean,
        onInputChange: (String) -> Unit,
        onAddClick: (String) -> Unit,
        onDeleteClick: (String) -> Unit,
    ) {
        if (state is NovelRepoScreenModel.State.Loading) {
            LoadingRepositories()
            return
        }
        val repos = (state as NovelRepoScreenModel.State.Success).repos.map { repo ->
            ExtensionRepo(
                baseUrl = repo.baseUrl,
                name = repo.name,
                shortName = null,
                website = "",
                signingKeyFingerprint = "",
            )
        }
        RepositoryList(
            repos = repos,
            inputText = inputText,
            inputError = inputError,
            isAdding = isAdding,
            formTitle = stringResource(MR.strings.add_novel_repo),
            formDescription = stringResource(MR.strings.novel_repo_add_description),
            emptyMessage = stringResource(MR.strings.information_empty_novel_repos),
            onInputChange = onInputChange,
            onAddClick = onAddClick,
            onDeleteClick = onDeleteClick,
        )
    }

    @Composable
    private fun RepositoryList(
        repos: List<ExtensionRepo>,
        inputText: String,
        inputError: String?,
        isAdding: Boolean,
        formTitle: String,
        formDescription: String,
        emptyMessage: String,
        onInputChange: (String) -> Unit,
        onAddClick: (String) -> Unit,
        onDeleteClick: (String) -> Unit,
    ) {
        val listState = rememberLazyListState()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "repo-input") {
                ExtensionRepoInput(
                    title = formTitle,
                    description = formDescription,
                    inputLabel = stringResource(MR.strings.repository_url),
                    placeholder = stringResource(MR.strings.repository_url_placeholder),
                    actionLabel = stringResource(MR.strings.action_add_repo),
                    pasteLabel = stringResource(MR.strings.paste),
                    clearLabel = stringResource(MR.strings.clear),
                    inputText = inputText,
                    errorMessage = inputError,
                    onInputChange = onInputChange,
                    onAddClick = onAddClick,
                    isLoading = isAdding,
                )
            }

            if (repos.isEmpty()) {
                item(key = "empty") {
                    EmptyRepositoryState(message = emptyMessage)
                }
            } else {
                item(key = "section-header") {
                    RepositorySectionHeader(count = repos.size)
                }
                items(
                    items = repos,
                    key = ExtensionRepo::baseUrl,
                ) { repo ->
                    ExtensionRepoItem(
                        extensionRepo = repo,
                        removeLabel = stringResource(MR.strings.remove_repository),
                        onDeleteClick = onDeleteClick,
                    )
                }
            }
        }
    }

    @Composable
    private fun RepositorySectionHeader(count: Int) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.strings.your_repositories),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    text = stringResource(MR.strings.repository_count, count),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    @Composable
    private fun EmptyRepositoryState(message: String) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.ExtensionOff,
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun LoadingRepositories() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    private suspend fun DialogHostState.awaitRepoDeletePrompt(
        repoToDelete: String,
        onDelete: (String) -> Unit,
    ): Unit = dialog { cont ->
        AlertDialog(
            onDismissRequest = { cont.cancel() },
            title = { Text(text = stringResource(MR.strings.confirm_delete_repo_title)) },
            text = { Text(text = stringResource(MR.strings.confirm_delete_repo, repoToDelete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(repoToDelete)
                        cont.cancel()
                    },
                ) {
                    Text(
                        text = stringResource(MR.strings.remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(MR.strings.cancel))
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
            title = { Text(text = stringResource(MR.strings.action_replace_repo_title)) },
            text = {
                Text(text = stringResource(MR.strings.action_replace_repo_message, newRepo.name, oldRepo.name))
            },
        )
    }
}
