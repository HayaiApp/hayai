package yokai.presentation.extension.repo

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import yokai.presentation.theme.LocalReducedMotion
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.i18n.MR

class ExtensionRepoController(private val repoUrl: String? = null) :
    BaseComposeController() {

    override val hostsOwnAppBar: Boolean = true

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = ExtensionRepoScreen(
                title = stringResource(MR.strings.repositories),
                repoUrl = repoUrl,
            ),
            content = { navigator ->
                this.navigator = navigator
                if (LocalReducedMotion.current) CurrentScreen()
                else CrossfadeTransition(navigator = navigator)
            },
        )
    }
}
