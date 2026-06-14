package yokai.presentation

import android.app.Activity
import android.os.Build
import androidx.annotation.AttrRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.R as materialR
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlin.math.roundToInt
import yokai.i18n.MR
import yokai.presentation.component.ToolTipButton
import yokai.presentation.core.AppBarScrollBehavior
import yokai.presentation.core.pinnedAppBarScrollBehavior

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
    val (color, scrolledColor) = getTopAppBarColor(title)
    val appBarContentColor = getTopAppBarContentColor()
    val appBarBackgroundProgress = when (appBarType) {
        AppBarType.LARGE -> if (m3ScrollBehavior.state.collapsedFraction >= 0.99f) 1f else 0f
        AppBarType.SMALL -> maxOf(
            m3ScrollBehavior.state.collapsedFraction,
            m3ScrollBehavior.state.overlappedFraction,
        )
        AppBarType.NONE -> 0f
    }.coerceIn(0f, 1f)
    val appBarSurfaceColor = lerp(color, scrolledColor, appBarBackgroundProgress)
    val statusBarContrastColor = appBarSurfaceColor.takeUnless { it == Color.Transparent }
        ?: MaterialTheme.colorScheme.surface
    val useDarkIcons = statusBarContrastColor.luminance() > .5

    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                activity.window.statusBarColor = ColorUtils.setAlphaComponent(
                    appBarSurfaceColor.toArgb(),
                    (0.87f * 255).roundToInt(),
                )
            }
            WindowInsetsControllerCompat(activity.window, view).isAppearanceLightStatusBars = useDarkIcons
        }
    }

    val noWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)

    Scaffold(
        modifier = modifier.nestedScroll(m3ScrollBehavior.nestedScrollConnection),
        floatingActionButton = fab,
        topBar = {
            if (appBarType != AppBarType.NONE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(appBarSurfaceColor)
                        .statusBarsPadding()
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
                                CenterAlignedTopAppBar(
                                    title = {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = topAppBarColors(
                                        containerColor = appBarSurfaceColor,
                                        scrolledContainerColor = appBarSurfaceColor,
                                        navigationIconContentColor = appBarContentColor,
                                        titleContentColor = appBarContentColor,
                                        actionIconContentColor = appBarContentColor,
                                    ),
                                    navigationIcon = {
                                        Box(Modifier.padding(start = 4.dp)) {
                                            ToolTipButton(
                                                toolTipLabel = navigationIconLabel,
                                                icon = navigationIcon,
                                                enabledTint = appBarContentColor,
                                                buttonClicked = onNavigationIconClicked,
                                            )
                                        }
                                    },
                                    actions = actions,
                                    scrollBehavior = m3ScrollBehavior,
                                    windowInsets = noWindowInsets,
                                )
                            }
                            AppBarType.LARGE -> {
                                LargeTopAppBar(
                                    title = {
                                        Text(
                                            text = title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = topAppBarColors(
                                        containerColor = appBarSurfaceColor,
                                        scrolledContainerColor = appBarSurfaceColor,
                                        navigationIconContentColor = appBarContentColor,
                                        titleContentColor = appBarContentColor,
                                        actionIconContentColor = appBarContentColor,
                                    ),
                                    navigationIcon = {
                                        ToolTipButton(
                                            toolTipLabel = navigationIconLabel,
                                            icon = navigationIcon,
                                            enabledTint = appBarContentColor,
                                            buttonClicked = onNavigationIconClicked,
                                        )
                                    },
                                    actions = actions,
                                    scrollBehavior = m3ScrollBehavior,
                                    windowInsets = noWindowInsets,
                                )
                            }
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
        content = content,
    )
}

@Composable
fun getTopAppBarColor(title: String): Pair<Color, Color> {
    val expandedColor = rememberThemeColor(materialR.attr.colorSurface)
    val collapsedColor = rememberThemeColor(materialR.attr.colorPrimaryVariant)
    return when (title.isEmpty()) {
        true -> Color.Transparent to Color.Transparent
        false -> expandedColor to collapsedColor
    }
}

@Composable
private fun getTopAppBarContentColor(): Color =
    rememberThemeColor(R.attr.actionBarTintColor)

@Composable
private fun rememberThemeColor(@AttrRes attrRes: Int): Color {
    val context = LocalContext.current
    return remember(context, attrRes, context.resources.configuration.uiMode) {
        Color(context.getResourceColor(attrRes))
    }
}

enum class AppBarType {
    NONE,
    SMALL,
    LARGE,
}
