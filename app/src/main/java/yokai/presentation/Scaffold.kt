package yokai.presentation

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import yokai.presentation.core.AppBarScrollBehavior
import yokai.presentation.core.pinnedAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR
import yokai.presentation.component.ToolTipButton
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YokaiScaffold(
    onNavigationIconClicked: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    scrollBehavior: AppBarScrollBehavior? = null,
    fab: @Composable () -> Unit = {},
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navigationIconLabel: String = stringResource(MR.strings.back),
    actions: @Composable RowScope.() -> Unit = {},
    appBarType: AppBarType = AppBarType.LARGE,
    snackbarHost: @Composable () -> Unit = {},
    textFieldState: TextFieldState? = null,
    searchResult: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = scrollBehavior ?: pinnedAppBarScrollBehavior()
    val m3ScrollBehavior = behavior.m3ScrollBehavior
    val view = LocalView.current
    val useDarkIcons = MaterialTheme.colorScheme.surface.luminance() > .5
    val (color, scrolledColor) = getTopAppBarColor(title)

    SideEffect {
        val activity = view.context as Activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM)
                activity.window.statusBarColor = Color.Transparent.toArgb()
            WindowInsetsControllerCompat(activity.window, view).isAppearanceLightStatusBars = useDarkIcons
        }
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var appBarHeight by remember { mutableStateOf(0f) }

    Scaffold(
        modifier = modifier.nestedScroll(m3ScrollBehavior.nestedScrollConnection),
        floatingActionButton = fab,
        topBar = {
            if (appBarType != AppBarType.NONE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .onSizeChanged { size ->
                            appBarHeight = size.height.toFloat()
                            m3ScrollBehavior.state.heightOffsetLimit = -appBarHeight
                        }
                        .offset { IntOffset(x = 0, y = m3ScrollBehavior.state.heightOffset.roundToInt()) }
                ) {
                    if (textFieldState != null && appBarType == AppBarType.SMALL) {
                        // Small appBar with SearchBar: only display SearchBar
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        val query = textFieldState.text.toString()
                        SearchBar(
                            inputField = {
                                SearchBarDefaults.InputField(
                                    query = query,
                                    onQueryChange = { textFieldState.edit { replace(0, length, it) } },
                                    onSearch = { expanded = false },
                                    expanded = expanded,
                                    onExpandedChange = { expanded = it },
                                    placeholder = { Text(stringResource(MR.strings.search)) },
                                    leadingIcon = {
                                        if (expanded) {
                                            IconButton(onClick = { expanded = false }) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.strings.back))
                                            }
                                        } else {
                                            IconButton(onClick = onNavigationIconClicked) {
                                                Icon(navigationIcon, contentDescription = navigationIconLabel)
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (query.isNotEmpty()) {
                                                IconButton(onClick = { textFieldState.edit { replace(0, length, "") } }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                                }
                                            }
                                            if (!expanded) {
                                                actions()
                                            }
                                        }
                                    }
                                )
                            },
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (expanded) 0.dp else 8.dp),
                            content = searchResult ?: {},
                        )
                    } else {
                        // Display the standard app bar
                        when (appBarType) {
                            AppBarType.SMALL -> {
                                TopAppBar(
                                    title = { Text(text = title) },
                                    colors = topAppBarColors(
                                        containerColor = color,
                                        scrolledContainerColor = scrolledColor,
                                    ),
                                    navigationIcon = {
                                        ToolTipButton(
                                            toolTipLabel = navigationIconLabel,
                                            icon = navigationIcon,
                                            buttonClicked = onNavigationIconClicked,
                                        )
                                    },
                                    actions = actions,
                                    scrollBehavior = m3ScrollBehavior,
                                )
                            }
                            AppBarType.LARGE -> {
                                LargeTopAppBar(
                                    title = { Text(text = title) },
                                    colors = topAppBarColors(
                                        containerColor = color,
                                        scrolledContainerColor = scrolledColor,
                                    ),
                                    navigationIcon = {
                                        ToolTipButton(
                                            toolTipLabel = navigationIconLabel,
                                            icon = navigationIcon,
                                            buttonClicked = onNavigationIconClicked,
                                        )
                                    },
                                    actions = actions,
                                    scrollBehavior = m3ScrollBehavior,
                                )
                            }
                            else -> {}
                        }

                        // For large app bar, if search is enabled, show the SearchBar below it
                        if (textFieldState != null) {
                            var expanded by rememberSaveable { mutableStateOf(false) }
                            val query = textFieldState.text.toString()
                            SearchBar(
                                inputField = {
                                    SearchBarDefaults.InputField(
                                        query = query,
                                        onQueryChange = { textFieldState.edit { replace(0, length, it) } },
                                        onSearch = { expanded = false },
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                        placeholder = { Text(stringResource(MR.strings.search)) },
                                        leadingIcon = {
                                            if (expanded) {
                                                IconButton(onClick = { expanded = false }) {
                                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.strings.back))
                                                }
                                            } else {
                                                Icon(Icons.Default.Search, contentDescription = "Search")
                                            }
                                        },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (query.isNotEmpty()) {
                                                    IconButton(onClick = { textFieldState.edit { replace(0, length, "") } }) {
                                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                                    }
                                                }
                                            }
                                        }
                                    )
                                },
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = if (expanded) 0.dp else 8.dp),
                                content = searchResult ?: {},
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = snackbarHost,
        content = { innerPadding ->
            val topPadding = with(density) { appBarHeight.toDp() }
            content(PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                top = topPadding,
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding()
            ))
        },
    )
}

@Composable
fun getTopAppBarColor(title: String): Pair<Color, Color> {
    return when (title.isEmpty()) {
        true -> Color.Transparent to Color.Transparent
        false -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.primaryContainer
    }
}

enum class AppBarType {
    NONE,
    SMALL,
    LARGE,
}
