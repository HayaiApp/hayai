package exh.ui.settings

import yokai.util.koin.get
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import exh.md.utils.MdConstants
import exh.md.utils.MdUtil
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings
import yokai.util.lang.getString

object SettingsMangadexScreen : ComposableSettings() {

    private fun readResolve(): Any = SettingsMangadexScreen

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes(): StringResource = MR.strings.pref_category_mangadex

    @Composable
    override fun getPreferences(): List<Preference> {
        return buildList {
            add(getLoginPreference())
            add(getSyncMangaDexIntoThis())
            add(getSyncLibraryToMangaDex())
        }.toPersistentList()
    }

    @Composable
    private fun getLoginPreference(): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val preferenceStore = remember { get<PreferenceStore>() }
        var isLoggedIn by remember(preferenceStore) { mutableStateOf(MdUtil.isOAuthSet(preferenceStore)) }
        var logoutDialogOpen by remember { mutableStateOf(false) }

        DisposableEffect(lifecycleOwner, preferenceStore) {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    isLoggedIn = MdUtil.isOAuthSet(preferenceStore)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        if (logoutDialogOpen) {
            AlertDialog(
                onDismissRequest = { logoutDialogOpen = false },
                title = { Text(text = stringResource(MR.strings.logout)) },
                confirmButton = {
                    TextButton(onClick = {
                        logoutDialogOpen = false
                        scope.launch(Dispatchers.IO) {
                            val mangaDex = MdUtil.getEnabledMangaDex(get<SourceManager>())
                            if (mangaDex != null) {
                                val success = mangaDex.logout()
                                withUIContext {
                                    if (success) {
                                        isLoggedIn = false
                                        context.toast(MR.strings.mangadex_logged_out)
                                    } else {
                                        context.toast(MR.strings.unknown_error)
                                    }
                                }
                            }
                        }
                    }) {
                        Text(text = stringResource(MR.strings.logout))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { logoutDialogOpen = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.mangadex_login_title),
            subtitle = if (isLoggedIn) {
                stringResource(MR.strings.mangadex_logged_in)
            } else {
                stringResource(MR.strings.log_in_to_mangadex)
            },
            onClick = {
                if (isLoggedIn) {
                    logoutDialogOpen = true
                } else {
                    context.openInBrowser(
                        url = MdConstants.Login.authUrl(MdUtil.getPkceChallengeCode()),
                        toolbarColor = null,
                        forceBrowser = true,
                    )
                }
            },
        )
    }

    @Composable
    private fun getSyncMangaDexIntoThis(): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var dialogOpen by remember { mutableStateOf(false) }

        if (dialogOpen) {
            val items = remember {
                listOf("Reading", "Plan to Read", "Completed", "Re-Reading", "Dropped", "On Hold")
            }
            val selection = remember {
                List(items.size) { index -> index == 0 || index == 5 }.toMutableStateList()
            }
            AlertDialog(
                onDismissRequest = { dialogOpen = false },
                title = { Text(text = stringResource(MR.strings.mangadex_sync_follows_to_library)) },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        items.forEachIndexed { index, followOption ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val checked = selection.getOrNull(index) ?: false
                                        selection[index] = !checked
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selection.getOrNull(index) ?: false,
                                    onCheckedChange = null,
                                )
                                Text(
                                    text = followOption,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialogOpen = false
                        scope.launch(Dispatchers.IO) {
                            val mangaDex = MdUtil.getEnabledMangaDex(get<SourceManager>())
                            if (mangaDex == null) {
                                withUIContext { context.toast(MR.strings.mangadex_not_enabled) }
                                return@launch
                            }
                            if (!mangaDex.isLogged()) {
                                withUIContext { context.toast(MR.strings.mangadex_not_logged_in) }
                                return@launch
                            }
                            try {
                                val result = mangaDex.fetchFollows(1)
                                withUIContext {
                                    context.toast(
                                        context.getString(
                                            MR.strings.mangadex_sync_follows_complete,
                                            result.mangas.size,
                                        ),
                                    )
                                }
                            } catch (e: Exception) {
                                withUIContext {
                                    context.toast(e.message ?: context.getString(MR.strings.unknown_error))
                                }
                            }
                        }
                    }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialogOpen = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.mangadex_sync_follows_to_library),
            subtitle = stringResource(MR.strings.mangadex_sync_follows_to_library_summary),
            onClick = { dialogOpen = true },
        )
    }

    @Composable
    private fun getSyncLibraryToMangaDex(): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.mangadex_push_favorites_to_mangadex),
            subtitle = stringResource(MR.strings.mangadex_push_favorites_to_mangadex_summary),
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val mangaDex = MdUtil.getEnabledMangaDex(get<SourceManager>())
                    if (mangaDex == null) {
                        withUIContext { context.toast(MR.strings.mangadex_not_enabled) }
                        return@launch
                    }
                    if (!mangaDex.isLogged()) {
                        withUIContext { context.toast(MR.strings.mangadex_not_logged_in) }
                        return@launch
                    }
                    withUIContext {
                        context.toast(MR.strings.mangadex_push_started)
                    }
                }
            },
        )
    }
}
